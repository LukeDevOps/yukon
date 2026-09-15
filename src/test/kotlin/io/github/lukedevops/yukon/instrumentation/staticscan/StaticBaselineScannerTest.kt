package io.github.lukedevops.yukon.instrumentation.staticscan

import net.bytebuddy.dynamic.ClassFileLocator
import java.io.File
import java.util.jar.JarOutputStream
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

    private fun jarRoot(vararg entries: Pair<String, ByteArray>): File {
        val jarFile = File.createTempFile("yukon-static-scan", ".jar")
        jarFile.deleteOnExit()
        JarOutputStream(jarFile.outputStream()).use { out ->
            for ((entryName, bytes) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jarFile
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
    fun `a nonexistent classpath entry is skipped rather than failing the whole scan`() {
        val scanner = StaticBaselineScanner(listOf("com.example.target"))

        val result = scanner.scan(listOf(File("build/does-not-exist-at-all")))

        assertTrue(result.declaredClasses.isEmpty())
        assertTrue(result.unreadableClasses.isEmpty())
    }
}
