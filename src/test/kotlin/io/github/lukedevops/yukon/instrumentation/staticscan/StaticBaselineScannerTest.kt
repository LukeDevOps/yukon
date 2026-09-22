package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.FixtureClassLoader
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
        // Empty includePackages: the prefix pre-filter cannot save the scan from a phantom name here.
        val scanner = StaticBaselineScanner(emptyList())

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
        // Empty includePackages, as above: only the entry check itself can keep the phantom out.
        val scanner = StaticBaselineScanner(emptyList())

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
    fun `declares the same generated-method marks as the manifest, and clinit is NONE`() {
        val root =
            directoryRoot(
                "com/example/target/GeneratedPoint.class" to generatedPointBytes,
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
    fun `excludes the agent's own package even when includePackages is empty`() {
        val agentClassBytes = classBytes("kotlin/main/io/github/lukedevops/yukon/Agent.class")
        val root =
            directoryRoot(
                "com/example/target/SampleTarget.class" to sampleTargetBytes,
                "io/github/lukedevops/yukon/Agent.class" to agentClassBytes,
            )
        val scanner = StaticBaselineScanner(emptyList())

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

            val supertypes = manifest.classSupertypes.single { it.classId == methodProbes.first().classId }
            assertEquals(supertypes.superClassName, declared.superClassName)
            assertEquals(supertypes.interfaceNames, declared.interfaceNames)
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
    fun `declares a bound function reference's body-class edges the same way the manifest carries them for the loaded class`() {
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
                    CallEdge(
                        "com.example.target.FunctionReferenceTarget\$viaReference\$f\$1",
                        "<init>",
                        "(Ljava/lang/Object;)V",
                        virtual = false,
                    ),
                    CallEdge(
                        "com.example.target.FunctionReferenceTarget\$viaReference\$f\$1",
                        "invoke",
                        "()Ljava/lang/Integer;",
                        virtual = false,
                    ),
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
    fun `with no include rules every class is in scope, so no reference is declared`() {
        val root = directoryRoot("com/example/target/ReferenceTarget.class" to referenceTargetBytes)
        val scanner = StaticBaselineScanner(emptyList())

        val declared = scanner.scan(listOf(root)).declaredClasses.single { it.className == "com.example.target.ReferenceTarget" }

        assertTrue(declared.referencedClasses.isEmpty())
        assertTrue(declared.methods.all { it.referencedClasses.isEmpty() })
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
