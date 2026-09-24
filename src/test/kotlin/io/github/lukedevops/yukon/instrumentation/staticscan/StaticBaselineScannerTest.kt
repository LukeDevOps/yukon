package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BodyKind
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.FixtureClassLoader
import io.github.lukedevops.yukon.instrumentation.JvmDefaultDisableFixtures
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticBaselineScannerTest {
    /**
     * Include prefixes that cover the names a multi-release jar's versioned entries and its module
     * descriptor would read as if taken for classes (`META-INF.versions.9.com.example...`,
     * `module-info`), alongside the fixture's own package.
     */
    private val phantomCoveringPrefixes = listOf("META-INF", "module-info", "com.example.target")

    private fun classBytes(path: String): ByteArray = File("build/classes/$path").readBytes()

    private val sampleTargetBytes = classBytes("java/test/com/example/target/SampleTarget.class")
    private val otherTargetBytes = classBytes("java/test/com/example/other/OtherTarget.class")
    private val weirdNameBytes = classBytes("kotlin/test/com/example/target/WeirdName.class")
    private val inlineTargetBytes = classBytes("kotlin/test/com/example/target/InlineTarget.class")
    private val lambdaTargetBytes = classBytes("java/test/com/example/target/LambdaTarget.class")
    private val staticInitTargetBytes = classBytes("java/test/com/example/target/StaticInitTarget.class")
    private val callEdgeTargetBytes = classBytes("kotlin/test/com/example/target/CallEdgeTarget.class")
    private val callEdgeTargetKtBytes = classBytes("kotlin/test/com/example/target/CallEdgeTargetKt.class")
    private val defaultArgumentTargetBytes = classBytes("kotlin/test/com/example/target/DefaultArgumentTarget.class")
    private val classifierBytes = classBytes("kotlin/test/com/example/target/Classifier.class")
    private val classifierImplBytes = classBytes("kotlin/test/com/example/target/ClassifierImpl.class")
    private val staticUseTargetBytes = classBytes("kotlin/test/com/example/target/StaticUseTarget.class")
    private val suitBytes = classBytes("kotlin/test/com/example/target/Suit.class")
    private val configBytes = classBytes("kotlin/test/com/example/target/Config.class")
    private val finalMethodTargetBytes = classBytes("kotlin/test/com/example/target/FinalMethodTarget.class")
    private val functionReferenceTargetBytes = classBytes("kotlin/test/com/example/target/FunctionReferenceTarget.class")
    private val functionReferenceBodyClassBytes =
        classBytes("kotlin/test/com/example/target/FunctionReferenceTarget\$viaReference\$f\$1.class")
    private val generatedPointBytes = classBytes("kotlin/test/com/example/target/GeneratedPoint.class")
    private val generatedPointCustomEqualsBytes = classBytes("kotlin/test/com/example/target/GeneratedPointCustomEquals.class")
    private val generatedColourBytes = classBytes("kotlin/test/com/example/target/GeneratedColour.class")
    private val generatedInterfaceDefaultImplsBytes =
        classBytes("kotlin/test/com/example/target/GeneratedInterface\$DefaultImpls.class")
    private val recordTargetBytes = classBytes("java/test/com/example/target/RecordTarget.class")
    private val referenceTargetBytes = classBytes("java/test/com/example/target/ReferenceTarget.class")

    /** The fixture root [CallEdgeAnalyzerTest][io.github.lukedevops.yukon.instrumentation.branch.CallEdgeAnalyzerTest] exercises directly. */
    private fun callEdgeFixtureRoot(): File =
        directoryRoot(
            "com/example/target/CallEdgeTarget.class" to callEdgeTargetBytes,
            "com/example/target/CallEdgeTargetKt.class" to callEdgeTargetKtBytes,
            "com/example/target/DefaultArgumentTarget.class" to defaultArgumentTargetBytes,
            "com/example/target/Classifier.class" to classifierBytes,
            "com/example/target/ClassifierImpl.class" to classifierImplBytes,
            "com/example/target/StaticUseTarget.class" to staticUseTargetBytes,
            "com/example/target/Suit.class" to suitBytes,
            "com/example/target/Config.class" to configBytes,
            "com/example/target/FinalMethodTarget.class" to finalMethodTargetBytes,
            "com/example/target/FunctionReferenceTarget.class" to functionReferenceTargetBytes,
            "com/example/target/FunctionReferenceTarget\$viaReference\$f\$1.class" to functionReferenceBodyClassBytes,
            "com/example/other/OtherTarget.class" to otherTargetBytes,
        )

    private fun directoryRoot(vararg entries: Pair<String, ByteArray>): File {
        val root =
            kotlin.io.path
                .createTempDirectory("yukon-static-scan")
                .toFile()
        for ((relativePath, bytes) in entries) {
            val file = File(root, relativePath)
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        return root
    }

    /** A minimal `module-info.class`, the shape javac emits for `module <name> {}`. */
    private fun moduleInfoBytes(moduleName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
        writer.visitModule(moduleName, 0, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun jarRoot(vararg entries: Pair<String, ByteArray>): File {
        val jarFile = File.createTempFile("yukon-static-scan", ".jar")
        jarFile.deleteOnExit()
        writeJar(jarFile, entries.toList())
        return jarFile
    }

    /** Writes [entries] into [jarFile], with a manifest carrying [classPath] as `Class-Path` when given. */
    private fun writeJar(
        jarFile: File,
        entries: List<Pair<String, ByteArray>>,
        classPath: String? = null,
    ) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        if (classPath != null) manifest.mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        JarOutputStream(jarFile.outputStream(), manifest).use { out ->
            for ((entryName, bytes) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    @Test
    fun `finds a declared class and its methods in a plain directory root`() {
        val root = directoryRoot("com/example/target/SampleTarget.class" to sampleTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val declared = result.declaredClasses.single { it.className == "com.example.target.SampleTarget" }
        val methodNames = declared.methods.map { it.methodName }
        assertTrue("ping" in methodNames)
        assertTrue("neverCalled" in methodNames)
        assertEquals("()Ljava/lang/String;", declared.methods.single { it.methodName == "ping" }.methodDescriptor)
    }

    @Test
    fun `marks a declared method inline when its bytecode carries the LocalVariableTable marker`() {
        val root = directoryRoot("com/example/target/InlineTarget.class" to inlineTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val declared = result.declaredClasses.single { it.className == "com.example.target.InlineTarget" }
        assertTrue(declared.methods.single { it.methodName == "member" }.inline)
        assertFalse(declared.methods.single { it.methodName == "plain" }.inline)
        assertFalse(declared.methods.single { it.methodName == "same" && it.methodDescriptor == "(I)I" }.inline, "not itself inline")
        assertTrue(declared.methods.single { it.methodName == "same" && it.methodDescriptor == "(II)I" }.inline)
    }

    @Test
    fun `finds a declared class in a flat jar root`() {
        val jar = jarRoot("com/example/target/SampleTarget.class" to sampleTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(jar))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
    }

    @Test
    fun `a multi-release jar's versioned entries and its module descriptor are not classes of their own`() {
        val jar =
            jarRoot(
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
                "META-INF/versions/9/com/example/target/SampleTarget.class" to sampleTargetBytes,
                "module-info.class" to moduleInfoBytes("com.example"),
            )
        // Include prefixes covering the phantom names, so the prefix pre-filter cannot save the
        // scan from one here.
        val scanner = StaticBaselineScanner(phantomCoveringPrefixes)

        val result = scanner.scan(listOf(jar))

        assertEquals(listOf("com.example.target.SampleTarget"), result.allClassNames().sorted())
    }

    @Test
    fun `a multi-release jar's versioned entries under BOOT-INF slash classes are not classes of their own either`() {
        val jar =
            jarRoot(
                "BOOT-INF/classes/com/example/target/SampleTarget.class" to sampleTargetBytes,
                "BOOT-INF/classes/META-INF/versions/9/com/example/target/SampleTarget.class" to sampleTargetBytes,
            )
        // Prefixes covering the phantom name, as above: only the entry check itself can keep it out.
        val scanner = StaticBaselineScanner(phantomCoveringPrefixes)

        val result = scanner.scan(listOf(jar))

        assertEquals(listOf("com.example.target.SampleTarget"), result.allClassNames().sorted())
    }

    @Test
    fun `a root named with an uppercase JAR extension or a zip extension is scanned like any jar`() {
        val dir = directoryRoot()
        val upper = File(dir, "app.JAR").also { writeJar(it, listOf("com/example/target/SampleTarget.class" to sampleTargetBytes)) }
        val zip = File(dir, "lib.zip").also { writeJar(it, listOf("com/example/other/OtherTarget.class" to otherTargetBytes)) }
        val scanner = StaticBaselineScanner(listOf("com.example"))

        val result = scanner.scan(listOf(upper, zip))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
        assertTrue(result.declaredClasses.any { it.className == "com.example.other.OtherTarget" })
    }

    @Test
    fun `jars named by a manifest Class-Path are scanned too, following the chain once each`() {
        // java -jar app.jar puts only app.jar on java.class.path; the launcher loads lib/a.jar
        // from the manifest, and a.jar's own manifest names b.jar. b.jar names app.jar back,
        // which must not loop.
        val dir = directoryRoot()
        File(dir, "lib").mkdirs()
        val app = File(dir, "app.jar")
        val a = File(dir, "lib/a.jar")
        val b = File(dir, "lib/b.jar")
        writeJar(app, emptyList(), classPath = "lib/a.jar missing.jar")
        writeJar(a, listOf("com/example/target/SampleTarget.class" to sampleTargetBytes), classPath = "b.jar")
        writeJar(b, listOf("com/example/other/OtherTarget.class" to otherTargetBytes), classPath = "../app.jar")
        val scanner = StaticBaselineScanner(listOf("com.example"))

        val result = scanner.scan(listOf(app))

        assertEquals(
            listOf("com.example.other.OtherTarget", "com.example.target.SampleTarget"),
            result.declaredClasses.map { it.className }.sorted(),
        )
    }

    @Test
    fun `finds a declared class nested under BOOT-INF slash classes, and ignores BOOT-INF slash lib nested jars`() {
        val jar =
            jarRoot(
                "BOOT-INF/classes/com/example/target/SampleTarget.class" to sampleTargetBytes,
                "BOOT-INF/lib/some-dependency.jar" to byteArrayOf(1, 2, 3, 4),
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(jar))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
        assertTrue(result.unreadableClasses.isEmpty())
    }

    @Test
    fun `finds a declared class nested under WEB-INF slash classes`() {
        val jar = jarRoot("WEB-INF/classes/com/example/target/SampleTarget.class" to sampleTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(jar))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
    }

    @Test
    fun `reports a class with an illegal-target annotation as statically unsafe, not declared`() {
        val root = directoryRoot("com/example/target/WeirdName.class" to weirdNameBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val unsafe = result.staticallyUnsafeClasses.single { it.className == "com.example.target.WeirdName" }
        assertTrue("JvmName" in unsafe.reason)
        assertTrue(result.declaredClasses.none { it.className == "com.example.target.WeirdName" })
    }

    @Test
    fun `an annotation whose type cannot be resolved leaves the class declared, not unsafe or unreadable`() {
        // Stands in for a Spring Boot fat jar, where the system loader cannot see the annotation
        // types packed under BOOT-INF/lib: ByteBuddy's type pool drops the unresolvable annotation
        // rather than failing, so @JvmName is invisible here and the class is judged safe.
        val root = directoryRoot("com/example/target/WeirdName.class" to weirdNameBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"), supportingTypesLocator = ClassFileLocator.NoOp.INSTANCE)

        val result = scanner.scan(listOf(root))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.WeirdName" })
        assertTrue(result.staticallyUnsafeClasses.isEmpty())
        assertTrue(result.unreadableClasses.isEmpty())
    }

    @Test
    fun `a suspend function's continuation class is absent from every bucket, and its facade and a suspend lambda class are declared`() {
        val root =
            directoryRoot(
                "com/example/target/CoroutineTargetKt.class" to classBytes("kotlin/test/com/example/target/CoroutineTargetKt.class"),
                "com/example/target/CoroutineTargetKt\$twoPoints\$1.class" to
                    classBytes("kotlin/test/com/example/target/CoroutineTargetKt\$twoPoints\$1.class"),
                "com/example/target/CoroutineTargetKt\$runLambda\$1.class" to
                    classBytes("kotlin/test/com/example/target/CoroutineTargetKt\$runLambda\$1.class"),
                "com/example/target/Holder.class" to classBytes("kotlin/test/com/example/target/Holder.class"),
                "com/example/target/Holder\$member\$1.class" to
                    classBytes("kotlin/test/com/example/target/Holder\$member\$1.class"),
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val declaredNames = result.declaredClasses.map { it.className }.toSet()
        assertTrue("com.example.target.CoroutineTargetKt" in declaredNames)
        assertTrue(
            "com.example.target.CoroutineTargetKt\$runLambda\$1" in declaredNames,
            "a suspend lambda's own class holds the adopter's body",
        )
        assertTrue("com.example.target.Holder" in declaredNames)
        assertTrue(
            "com.example.target.CoroutineTargetKt\$twoPoints\$1" !in declaredNames,
            "a top-level suspend function's continuation class",
        )
        assertTrue("com.example.target.Holder\$member\$1" !in declaredNames, "a member suspend function's continuation class")

        val everyBucketName =
            declaredNames +
                result.staticallyUnsafeClasses.map { it.className } +
                result.unreadableClasses.map { it.className } +
                result.unprobedClasses.map { it.className }
        assertTrue(
            "com.example.target.CoroutineTargetKt\$twoPoints\$1" !in everyBucketName,
            "a continuation class is absent from every bucket, not just the declared one",
        )
        assertTrue("com.example.target.Holder\$member\$1" !in everyBucketName)
    }

    @Test
    fun `a continuation class is still left out when nothing can resolve the Kotlin stdlib`() {
        // The fat-jar shape: the stdlib sits in a nested dependency jar the scan never opens, so
        // ContinuationImpl cannot be found anywhere. The scan's lazily resolving pool still gives
        // the superclass's name, which is all the type matcher's check needs.
        val root =
            directoryRoot(
                "com/example/target/CoroutineTargetKt\$twoPoints\$1.class" to
                    classBytes("kotlin/test/com/example/target/CoroutineTargetKt\$twoPoints\$1.class"),
                "com/example/target/Holder.class" to classBytes("kotlin/test/com/example/target/Holder.class"),
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"), supportingTypesLocator = ClassFileLocator.NoOp.INSTANCE)

        val result = scanner.scan(listOf(root))

        val everyBucketName =
            result.declaredClasses.map { it.className } +
                result.staticallyUnsafeClasses.map { it.className } +
                result.unreadableClasses.map { it.className } +
                result.unprobedClasses.map { it.className }
        assertTrue("com.example.target.Holder" in everyBucketName)
        assertTrue("com.example.target.CoroutineTargetKt\$twoPoints\$1" !in everyBucketName)
    }

    @Test
    fun `an in-scope class with no concrete methods is reported as unprobed, not declared`() {
        val root =
            directoryRoot(
                "com/example/target/AbstractOnlyInterface.class" to classBytes("java/test/com/example/target/AbstractOnlyInterface.class"),
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val unprobed = result.unprobedClasses.single()
        assertEquals("com.example.target.AbstractOnlyInterface", unprobed.className)
        assertTrue(result.declaredClasses.none { it.className == "com.example.target.AbstractOnlyInterface" })
        assertTrue(result.declaredClasses.all { it.methods.isNotEmpty() }, "a declared class always has something to probe")
        assertTrue("com.example.target.AbstractOnlyInterface" in result.allClassNames())
    }

    @Test
    fun `declares a javac lambda body alongside the class's ordinary methods`() {
        val root = directoryRoot("com/example/target/LambdaTarget.class" to lambdaTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val declared = result.declaredClasses.single { it.className == "com.example.target.LambdaTarget" }
        val methodNames = declared.methods.map { it.methodName }
        assertTrue("lambda\$classifyViaLambda\$0" in methodNames, "the lambda body itself must be declared, not just its caller")
        assertTrue("ship" in methodNames, "a method reference's own target stays an ordinary declared method")
    }

    @Test
    fun `declares a javac lambda body as one, and a method reference's target and its creator as not`() {
        val root = directoryRoot("com/example/target/LambdaTarget.class" to lambdaTargetBytes)

        val declared =
            StaticBaselineScanner(listOf("com.example.target"))
                .scan(listOf(root))
                .declaredClasses
                .single { it.className == "com.example.target.LambdaTarget" }

        assertTrue(declared.methods.single { it.methodName == "lambda\$classifyViaLambda\$0" }.lambdaBody)
        assertFalse(declared.methods.single { it.methodName == "ship" }.lambdaBody)
        assertFalse(declared.methods.single { it.methodName == "classifyViaLambda" }.lambdaBody)
        assertEquals("LambdaTarget.java", declared.sourceFile)
    }

    @Test
    fun `declares kotlinc lambda bodies, nested ones included, with the class's source file`() {
        val root =
            directoryRoot(
                "com/example/target/CreationEdgeTarget.class" to classBytes("kotlin/test/com/example/target/CreationEdgeTarget.class"),
            )

        val declared =
            StaticBaselineScanner(listOf("com.example.target"))
                .scan(listOf(root))
                .declaredClasses
                .single { it.className == "com.example.target.CreationEdgeTarget" }

        assertEquals(
            setOf(
                "plain\$lambda\$0",
                "capturing\$lambda\$0",
                "capturingThis\$lambda\$0",
                "nested\$lambda\$0",
                "nested\$lambda\$0\$0",
                "withDefault\$lambda\$0",
            ),
            declared.methods
                .filter { it.lambdaBody }
                .map { it.methodName }
                .toSet(),
        )
        assertEquals("CreationEdgeTarget.kt", declared.sourceFile)
    }

    @Test
    fun `declares each class's body kind and a local class's source name, and passes through each synthetic reference class`() {
        val kotlinClasses =
            listOf(
                "BodyKindTarget",
                "BodyKindTarget\$localClass\$Local",
                "BodyKindTarget\$suspendLambda\$1",
                "BodyKindTarget\$functionReference\$1",
                "BodyKindTarget\$unboundPropertyReference\$1",
                "ObjectExpressionTarget\$makeHandler\$1",
            )
        val javaClasses = listOf("BodyKindJavaTarget", "BodyKindJavaTarget\$1", "BodyKindJavaTarget\$1Local")
        val root =
            directoryRoot(
                *(
                    kotlinClasses.map { "com/example/target/$it.class" to classBytes("kotlin/test/com/example/target/$it.class") } +
                        javaClasses.map { "com/example/target/$it.class" to classBytes("java/test/com/example/target/$it.class") }
                ).toTypedArray(),
            )

        val declaredClasses =
            StaticBaselineScanner(listOf("com.example.target"))
                .scan(listOf(root))
                .declaredClasses
        val declared = declaredClasses.associate { it.className.removePrefix("com.example.target.") to (it.bodyKind to it.sourceName) }
        val creator = declaredClasses.single { it.className == "com.example.target.BodyKindTarget" }

        assertEquals(
            mapOf(
                "BodyKindTarget" to (BodyKind.NONE to null),
                "BodyKindTarget\$localClass\$Local" to (BodyKind.LOCAL_CLASS to "Local"),
                "BodyKindTarget\$suspendLambda\$1" to (BodyKind.LAMBDA_CLASS to null),
                "ObjectExpressionTarget\$makeHandler\$1" to (BodyKind.OBJECT_EXPRESSION to null),
                "BodyKindJavaTarget" to (BodyKind.NONE to null),
                "BodyKindJavaTarget\$1" to (BodyKind.ANONYMOUS_CLASS to null),
                "BodyKindJavaTarget\$1Local" to (BodyKind.LOCAL_CLASS to "Local"),
            ),
            declared,
            "kotlinc marks a reference class synthetic, and the type matcher turns every synthetic class away",
        )
        assertEquals(
            listOf(CallEdge("com.example.target.BodyKindTarget", "twice", "(I)I", virtual = false, kind = CallEdgeKind.CREATES)),
            creator.methods.single { it.methodName == "functionReference" }.calls,
        )
        assertEquals(
            listOf(CallEdge("com.example.target.BodyKindTarget", "getBase", "()I", virtual = false, kind = CallEdgeKind.CREATES)),
            creator.methods.single { it.methodName == "unboundPropertyReference" }.calls,
        )
    }

    @Test
    fun `declares clinit for a fixture that has one, and not for one that does not`() {
        val root =
            directoryRoot(
                "com/example/target/StaticInitTarget.class" to staticInitTargetBytes,
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val withClinit = result.declaredClasses.single { it.className == "com.example.target.StaticInitTarget" }
        assertTrue(withClinit.methods.any { it.methodName == "<clinit>" && it.methodDescriptor == "()V" })

        val withoutClinit = result.declaredClasses.single { it.className == "com.example.target.SampleTarget" }
        assertTrue(withoutClinit.methods.none { it.methodName == "<clinit>" })
    }

    @Test
    fun `declares a disable-mode DefaultImpls body method NONE and a default-mode DefaultImpls forwarder DEFAULT_IMPLS`() {
        val disabledDefaultImpls = "com/example/target/jvmdefaultdisable/DisabledDefaultInterface\$DefaultImpls.class"
        val root =
            directoryRoot(
                "com/example/target/GeneratedInterface\$DefaultImpls.class" to generatedInterfaceDefaultImplsBytes,
                disabledDefaultImpls to File(JvmDefaultDisableFixtures.outputDir, disabledDefaultImpls).readBytes(),
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val forwarders = result.declaredClasses.single { it.className == "com.example.target.GeneratedInterface\$DefaultImpls" }.methods
        assertEquals(GeneratedBy.DEFAULT_IMPLS, forwarders.single { it.methodName == "withBody" }.generatedBy)
        assertEquals(GeneratedBy.DEFAULT_IMPLS, forwarders.single { it.methodName == "getLabel" }.generatedBy)

        val bodies =
            result.declaredClasses
                .single { it.className == "com.example.target.jvmdefaultdisable.DisabledDefaultInterface\$DefaultImpls" }
                .methods
        assertEquals(GeneratedBy.NONE, bodies.single { it.methodName == "withBranch" }.generatedBy)
        assertEquals(GeneratedBy.NONE, bodies.single { it.methodName == "withBody" }.generatedBy)
        assertEquals(GeneratedBy.NONE, bodies.single { it.methodName == "getLabel" }.generatedBy)
        assertEquals(GeneratedBy.NONE, bodies.single { it.methodName == "callsPrivate" }.generatedBy)
    }

    @Test
    fun `declares the same generated-method marks as the manifest, and clinit is NONE`() {
        val root =
            directoryRoot(
                "com/example/target/GeneratedPoint.class" to generatedPointBytes,
                "com/example/target/GeneratedPointCustomEquals.class" to generatedPointCustomEqualsBytes,
                "com/example/target/GeneratedColour.class" to generatedColourBytes,
                "com/example/target/GeneratedInterface\$DefaultImpls.class" to generatedInterfaceDefaultImplsBytes,
                "com/example/target/RecordTarget.class" to recordTargetBytes,
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        val point = result.declaredClasses.single { it.className == "com.example.target.GeneratedPoint" }.methods
        assertEquals(GeneratedBy.DATA_CLASS, point.single { it.methodName == "component1" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, point.single { it.methodName == "copy" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, point.single { it.methodName == "equals" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, point.single { it.methodName == "hashCode" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, point.single { it.methodName == "toString" }.generatedBy)
        assertEquals(GeneratedBy.NONE, point.single { it.methodName == "<init>" }.generatedBy)
        assertEquals(GeneratedBy.NONE, point.single { it.methodName == "getX" }.generatedBy)

        val customEquals = result.declaredClasses.single { it.className == "com.example.target.GeneratedPointCustomEquals" }.methods
        assertEquals(GeneratedBy.NONE, customEquals.single { it.methodName == "equals" }.generatedBy)
        assertEquals(GeneratedBy.NONE, customEquals.single { it.methodName == "hashCode" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, customEquals.single { it.methodName == "toString" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, customEquals.single { it.methodName == "copy" }.generatedBy)

        val colour = result.declaredClasses.single { it.className == "com.example.target.GeneratedColour" }.methods
        assertEquals(GeneratedBy.ENUM, colour.single { it.methodName == "values" }.generatedBy)
        assertEquals(GeneratedBy.ENUM, colour.single { it.methodName == "valueOf" }.generatedBy)
        assertEquals(GeneratedBy.ENUM, colour.single { it.methodName == "getEntries" }.generatedBy)
        assertEquals(GeneratedBy.NONE, colour.single { it.methodName == "<clinit>" }.generatedBy)

        val defaultImpls = result.declaredClasses.single { it.className == "com.example.target.GeneratedInterface\$DefaultImpls" }.methods
        assertEquals(GeneratedBy.DEFAULT_IMPLS, defaultImpls.single { it.methodName == "withBody" }.generatedBy)

        val record = result.declaredClasses.single { it.className == "com.example.target.RecordTarget" }.methods
        assertEquals(GeneratedBy.RECORD, record.single { it.methodName == "equals" }.generatedBy)
        assertEquals(GeneratedBy.RECORD, record.single { it.methodName == "hashCode" }.generatedBy)
        assertEquals(GeneratedBy.RECORD, record.single { it.methodName == "toString" }.generatedBy)
        assertEquals(GeneratedBy.NONE, record.single { it.methodName == "x" }.generatedBy)
        assertEquals(GeneratedBy.NONE, record.single { it.methodName == "extra" }.generatedBy)
    }

    @Test
    fun `reports an unreadable class file without losing the rest of the scan`() {
        val root =
            directoryRoot(
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
                "com/example/target/Garbage.class" to byteArrayOf(1, 2, 3, 4, 5),
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        assertTrue(result.unreadableClasses.any { it.className == "com.example.target.Garbage" })
        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
    }

    @Test
    fun `excludes the agent's own package even when includePackages covers it`() {
        val agentClassBytes = classBytes("kotlin/main/io/github/lukedevops/yukon/Agent.class")
        val root =
            directoryRoot(
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
                "io/github/lukedevops/yukon/Agent.class" to agentClassBytes,
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target", "io.github.lukedevops"))

        val result = scanner.scan(listOf(root))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
        assertTrue(result.declaredClasses.none { it.className.startsWith("io.github.lukedevops.yukon") })
    }

    @Test
    fun `only scans classes under includePackages when it is non-empty`() {
        val root =
            directoryRoot(
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
                "com/example/other/OtherTarget.class" to otherTargetBytes,
            )
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(root))

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
        assertTrue(result.declaredClasses.none { it.className == "com.example.other.OtherTarget" })
    }

    @Test
    fun `a class under excludePackages lands in no bucket, even though it matches includePackages`() {
        val root = directoryRoot("com/example/target/SampleTarget.class" to sampleTargetBytes)
        val scanner =
            StaticBaselineScanner(listOf("com.example.target"), excludedPackagePrefixes = listOf("com.example.target.SampleTarget"))

        val result = scanner.scan(listOf(root))

        assertTrue(result.declaredClasses.none { it.className == "com.example.target.SampleTarget" })
        assertTrue("com.example.target.SampleTarget" !in result.allClassNames())
    }

    @Test
    fun `with no roots given, the scan walks this JVM's own java-class-path`() {
        // The test classpath carries this module's compiled test fixtures, so a scan of the
        // default roots must find one of them without being told where to look.
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan()

        assertTrue(result.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
    }

    @Test
    fun `a nonexistent classpath entry is skipped rather than failing the whole scan`() {
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(File("build/does-not-exist-at-all")))

        assertTrue(result.declaredClasses.isEmpty())
        assertTrue(result.unreadableClasses.isEmpty())
    }

    @Test
    fun `a cross-class Kotlin default pass-through resolves to its target method, not to dollar-default`() {
        val root = callEdgeFixtureRoot()
        val scanner = StaticBaselineScanner(listOf("com.example.target", "com.example.other"))

        val result = scanner.scan(listOf(root))

        val declared = result.declaredClasses.single { it.className == "com.example.target.CallEdgeTarget" }
        val method =
            declared.methods.single {
                it.methodName == "callsWithDefaultArgument" && it.methodDescriptor == "(Lcom/example/target/DefaultArgumentTarget;)I"
            }
        assertEquals(
            listOf(CallEdge("com.example.target.DefaultArgumentTarget", "f", "(IILjava/lang/String;J)I", virtual = false)),
            method.calls,
        )
    }

    @Test
    fun `declares CallEdgeTarget with the same call edges and supertypes the manifest carries for the loaded class`() {
        val root = callEdgeFixtureRoot()
        val scanner = StaticBaselineScanner(listOf("com.example.target", "com.example.other"))

        val result = scanner.scan(listOf(root))
        val declared = result.declaredClasses.single { it.className == "com.example.target.CallEdgeTarget" }

        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target;com.example.other")
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        val transformer = yukon.install(instrumentation)
        try {
            val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
            Class.forName("com.example.target.CallEdgeTarget", true, loader)

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            val methodProbes =
                manifest.probes.filter { it.className == "com.example.target.CallEdgeTarget" && it.kind == ProbeKind.METHOD }
            assertTrue(methodProbes.isNotEmpty())
            for (probe in methodProbes) {
                val declaredMethod =
                    declared.methods.single {
                        it.methodName == probe.methodName &&
                            it.methodDescriptor == probe.methodDescriptor
                    }
                assertEquals(probe.calls, declaredMethod.calls, "mismatch for ${probe.methodName}${probe.methodDescriptor}")
            }
            // Both out-of-scope callees (a JDK call, a Kotlin stdlib call) are absent from the
            // methods that make them, on both sides.
            assertEquals(emptyList(), declared.methods.single { it.methodName == "callsJdkMethod" }.calls)
            assertEquals(emptyList(), declared.methods.single { it.methodName == "callsKotlinStdlib" }.calls)

            val supertypes = manifest.classLocations.single { it.classId == methodProbes.first().classId }
            assertEquals(supertypes.superClassName, declared.superClassName)
            assertEquals(supertypes.interfaceNames, declared.interfaceNames)
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `declares each method's branch sites exactly as the manifest's METHOD probes list them for the loaded class`() {
        val javaClasses = listOf("BranchTarget", "SwitchFillerTarget", "StaticInitBranchTarget")
        val kotlinClasses = listOf("InlinedCopyTargetKt", "CoroutineTargetKt")
        val root =
            directoryRoot(
                *(
                    javaClasses.map { "com/example/target/$it.class" to classBytes("java/test/com/example/target/$it.class") } +
                        kotlinClasses.map { "com/example/target/$it.class" to classBytes("kotlin/test/com/example/target/$it.class") }
                ).toTypedArray(),
            )
        val result = StaticBaselineScanner(listOf("com.example.target")).scan(listOf(root))

        val registry = ProbeRegistry()
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        val transformer = yukon.install(instrumentation)
        try {
            val javaLoader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
            val kotlinLoader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
            javaClasses.forEach { Class.forName("com.example.target.$it", true, javaLoader) }
            kotlinClasses.forEach { Class.forName("com.example.target.$it", true, kotlinLoader) }

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            for (simpleName in javaClasses + kotlinClasses) {
                val className = "com.example.target.$simpleName"
                val declared = result.declaredClasses.single { it.className == className }
                val methodProbes = manifest.probes.filter { it.className == className && it.kind == ProbeKind.METHOD }
                assertEquals(declared.methods.size, methodProbes.size, "$className declares one entry per METHOD probe")
                assertTrue(methodProbes.any { it.branchSites.isNotEmpty() }, "$className has at least one listed site")
                for (probe in methodProbes) {
                    val declaredMethod =
                        declared.methods.single { it.methodName == probe.methodName && it.methodDescriptor == probe.methodDescriptor }
                    assertEquals(probe.branchSites, declaredMethod.branchSites, "mismatch for $className#${probe.methodName}")
                }
            }
            val staticInit = result.declaredClasses.single { it.className == "com.example.target.StaticInitBranchTarget" }
            assertEquals(emptyList(), staticInit.methods.single { it.methodName == "<clinit>" }.branchSites)
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `declares each method's guarded sites and guarded call edges exactly as the manifest carries them for the loaded class`() {
        val kotlinClasses =
            listOf(
                "GuardTarget",
                "GuardTarget\$objectInArm\$1",
                "GuardTargetKt",
                "GuardTargetKt\$suspendInArm\$1",
                "GuardConfig",
                "GuardInlineKt",
                "CoroutineTargetKt",
            )
        val javaClasses = listOf("GuardSwitchTarget")
        val root =
            directoryRoot(
                *(
                    kotlinClasses.map { "com/example/target/$it.class" to classBytes("kotlin/test/com/example/target/$it.class") } +
                        javaClasses.map { "com/example/target/$it.class" to classBytes("java/test/com/example/target/$it.class") }
                ).toTypedArray(),
            )
        val result = StaticBaselineScanner(listOf("com.example.target")).scan(listOf(root))

        val registry = ProbeRegistry()
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        val transformer = yukon.install(instrumentation)
        try {
            val kotlinLoader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
            val javaLoader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
            val compared = listOf("GuardTarget", "GuardTargetKt")
            compared.forEach { Class.forName("com.example.target.$it", true, kotlinLoader) }
            javaClasses.forEach { Class.forName("com.example.target.$it", true, javaLoader) }

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            var guardedEdges = 0
            var guardedSites = 0
            for (simpleName in compared + javaClasses) {
                val className = "com.example.target.$simpleName"
                val declared = result.declaredClasses.single { it.className == className }
                val methodProbes = manifest.probes.filter { it.className == className && it.kind == ProbeKind.METHOD }
                assertEquals(declared.methods.size, methodProbes.size, "$className declares one entry per METHOD probe")
                for (probe in methodProbes) {
                    val declaredMethod =
                        declared.methods.single { it.methodName == probe.methodName && it.methodDescriptor == probe.methodDescriptor }
                    assertEquals(probe.branchSites, declaredMethod.branchSites, "sites of $className#${probe.methodName}")
                    assertEquals(probe.calls, declaredMethod.calls, "edges of $className#${probe.methodName}")
                    guardedEdges += probe.calls.count { it.guard != null }
                    guardedSites +=
                        probe.branchSites.count { site -> site.guard != null || site.outcomes.any { it.guardedLines.isNotEmpty() } }
                }
            }
            assertTrue(guardedEdges > 10, "enough guarded edges to mean something: $guardedEdges")
            assertTrue(guardedSites > 10, "enough sites with a guard or guarded lines to mean something: $guardedSites")
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `declares each site's condition exactly as the manifest carries it for the loaded class`() {
        val kotlinClasses = listOf("ConditionTarget", "ConditionTargetKt")
        val javaClasses = listOf("ConditionJavaTarget")
        val root =
            directoryRoot(
                *(
                    kotlinClasses.map { "com/example/target/$it.class" to classBytes("kotlin/test/com/example/target/$it.class") } +
                        javaClasses.map { "com/example/target/$it.class" to classBytes("java/test/com/example/target/$it.class") }
                ).toTypedArray(),
            )
        val result = StaticBaselineScanner(listOf("com.example.target")).scan(listOf(root))

        val registry = ProbeRegistry()
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        val transformer = yukon.install(instrumentation)
        try {
            val kotlinLoader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
            val javaLoader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
            kotlinClasses.forEach { Class.forName("com.example.target.$it", true, kotlinLoader) }
            javaClasses.forEach { Class.forName("com.example.target.$it", true, javaLoader) }

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            var sitesWithConditions = 0
            for (simpleName in kotlinClasses + javaClasses) {
                val className = "com.example.target.$simpleName"
                val declared = result.declaredClasses.single { it.className == className }
                val methodProbes = manifest.probes.filter { it.className == className && it.kind == ProbeKind.METHOD }
                assertEquals(declared.methods.size, methodProbes.size, "$className declares one entry per METHOD probe")
                for (probe in methodProbes) {
                    val declaredMethod =
                        declared.methods.single { it.methodName == probe.methodName && it.methodDescriptor == probe.methodDescriptor }
                    assertEquals(probe.branchSites, declaredMethod.branchSites, "sites of $className#${probe.methodName}")
                    sitesWithConditions += probe.branchSites.count { it.condition.isNotEmpty() }
                }
            }
            assertTrue(sitesWithConditions > 40, "enough sites with a condition to mean something: $sitesWithConditions")
            val enumCheck =
                manifest.probes.single {
                    it.className == "com.example.target.ConditionTarget" && it.kind == ProbeKind.METHOD && it.methodName == "enumCheck"
                }
            assertEquals(
                listOf(ConditionPart(ConditionPartKind.CODE, "mode == ConditionMode.FAST")),
                enumCheck.branchSites.single().condition,
                "the transform reads the enum's class through the loader",
            )
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `declares StaticUseTarget's static field use edges the same way the manifest carries them for the loaded class`() {
        val root = callEdgeFixtureRoot()
        val scanner = StaticBaselineScanner(listOf("com.example.target", "com.example.other"))

        val result = scanner.scan(listOf(root))
        val declared = result.declaredClasses.single { it.className == "com.example.target.StaticUseTarget" }

        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target;com.example.other")
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        val transformer = yukon.install(instrumentation)
        try {
            val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
            Class.forName("com.example.target.StaticUseTarget", true, loader)

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            val methodProbes =
                manifest.probes.filter { it.className == "com.example.target.StaticUseTarget" && it.kind == ProbeKind.METHOD }
            assertTrue(methodProbes.isNotEmpty())
            for (probe in methodProbes) {
                val declaredMethod =
                    declared.methods.single {
                        it.methodName == probe.methodName &&
                            it.methodDescriptor == probe.methodDescriptor
                    }
                assertEquals(probe.calls, declaredMethod.calls, "mismatch for ${probe.methodName}${probe.methodDescriptor}")
            }
            assertEquals(
                listOf(CallEdge("com.example.target.Suit", "<clinit>", "()V", virtual = false)),
                declared.methods.single { it.methodName == "readEnumConstant" }.calls,
            )
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `declares a bound function reference's pass-through edge the same way the manifest carries it for the loaded class`() {
        val root = callEdgeFixtureRoot()
        val scanner = StaticBaselineScanner(listOf("com.example.target", "com.example.other"))

        val result = scanner.scan(listOf(root))
        val declared = result.declaredClasses.single { it.className == "com.example.target.FunctionReferenceTarget" }

        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target;com.example.other")
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        val transformer = yukon.install(instrumentation)
        try {
            val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
            val targetClass = Class.forName("com.example.target.FunctionReferenceTarget", true, loader)
            val target = targetClass.getDeclaredConstructor().newInstance()
            targetClass.getMethod("viaReference").invoke(target)

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            val viaReferenceProbe =
                manifest.probes.single {
                    it.className == "com.example.target.FunctionReferenceTarget" &&
                        it.methodName == "viaReference" &&
                        it.kind == ProbeKind.METHOD
                }
            assertEquals(
                viaReferenceProbe.calls,
                declared.methods.single { it.methodName == "viaReference" }.calls,
            )
            assertEquals(
                listOf(
                    CallEdge("com.example.target.FunctionReferenceTarget", "secret", "()I", virtual = false, kind = CallEdgeKind.CREATES),
                ),
                declared.methods.single { it.methodName == "viaReference" }.calls,
            )
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `an implementing class's declared supertypes name both its superclass and its interface`() {
        val root = callEdgeFixtureRoot()
        val scanner = StaticBaselineScanner(listOf("com.example.target", "com.example.other"))

        val result = scanner.scan(listOf(root))

        val declared = result.declaredClasses.single { it.className == "com.example.target.ClassifierImpl" }
        assertEquals("java.lang.Object", declared.superClassName)
        assertEquals(listOf("com.example.target.Classifier"), declared.interfaceNames)
    }

    @Test
    fun `a class scanned with a narrower includePackages loses its edges to classes outside that scope`() {
        val root = callEdgeFixtureRoot()
        val wideScanner = StaticBaselineScanner(listOf("com.example.target", "com.example.other"))
        val narrowScanner = StaticBaselineScanner(listOf("com.example.target"))

        val wideResult = wideScanner.scan(listOf(root))
        val narrowResult = narrowScanner.scan(listOf(root))

        val wideMethod = wideResult.declaredClasses.single { it.className == "com.example.target.CallEdgeTarget" }.methods
        val narrowMethod = narrowResult.declaredClasses.single { it.className == "com.example.target.CallEdgeTarget" }.methods

        assertTrue(wideMethod.single { it.methodName == "callsOtherClass" }.calls.isNotEmpty())
        assertTrue(narrowMethod.single { it.methodName == "callsOtherClass" }.calls.isEmpty())
    }

    /** A class in `com.example.target` whose own `<clinit>` calls `Lib.StaticOwner.compute()`, and which has no other method. */
    private fun clinitReferenceBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/target/ClinitReference", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd()
        val clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/library/Lib\$StaticOwner", "compute", "()I", false)
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "com/example/target/ClinitReference", "value", "I")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `declares each method's references and the class's own references from the analysis`() {
        val root = directoryRoot("com/example/target/ReferenceTarget.class" to referenceTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val declared = scanner.scan(listOf(root)).declaredClasses.single { it.className == "com.example.target.ReferenceTarget" }

        val newInstance = declared.methods.single { it.methodName == "newInstance" }
        assertEquals(listOf("com.example.library.Lib\$New"), newInstance.referencedClasses.filter { it.startsWith("com.example.") })
        assertTrue("com.example.library.Lib\$Base" in declared.referencedClasses)
        assertTrue("com.example.library.Lib\$Iface" in declared.referencedClasses)
    }

    @Test
    fun `declares the references a class's own static initializer holds`() {
        val root = directoryRoot("com/example/target/ClinitReference.class" to clinitReferenceBytes())
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val declared = scanner.scan(listOf(root)).declaredClasses.single { it.className == "com.example.target.ClinitReference" }

        assertEquals(
            listOf("com.example.library.Lib\$StaticOwner"),
            declared.methods.single { it.methodName == "<clinit>" }.referencedClasses,
        )
    }

    @Test
    fun `with no include rules nothing is in scope, so no class is declared`() {
        val root =
            directoryRoot(
                "com/example/target/ReferenceTarget.class" to referenceTargetBytes,
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
            )
        val scanner = StaticBaselineScanner(emptyList())

        val result = scanner.scan(listOf(root))

        assertEquals(emptySet(), result.allClassNames())
    }

    @Test
    fun `records every class name in a directory root or a nested classes root as the adopter's own, in scope or not`() {
        val directory =
            directoryRoot(
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
                "com/example/other/OtherTarget.class" to otherTargetBytes,
            )
        val fatJar =
            jarRoot(
                "BOOT-INF/classes/com/example/boot/OutOfScope.class" to otherTargetBytes,
                "BOOT-INF/lib/some-dependency.jar" to byteArrayOf(1, 2, 3, 4),
                "org/springframework/boot/loader/Launcher.class" to otherTargetBytes,
            )
        val flatJar = jarRoot("org/flat/Library.class" to otherTargetBytes)
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(directory, fatJar, flatJar))

        assertEquals(
            setOf("com.example.target.SampleTarget", "com.example.other.OtherTarget", "com.example.boot.OutOfScope"),
            result.ownClassNames,
        )
        assertEquals(setOf("org.springframework.boot.loader.Launcher", "org.flat.Library"), result.flatJarClassNames)
    }
}
