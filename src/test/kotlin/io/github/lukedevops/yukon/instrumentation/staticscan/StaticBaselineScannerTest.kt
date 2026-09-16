package io.github.lukedevops.yukon.instrumentation.staticscan

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
}
