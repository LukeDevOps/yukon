package io.github.lukedevops.yukon.instrumentation

import java.io.File

/**
 * Reads compiled bytes and builds a child-first classloader for the
 * `:fixtures-kotlin-jvm-default-disable` module, compiled with `-jvm-default=disable`, whose output
 * directory and runtime classpath the root build passes in as system properties (never as a test
 * dependency, so JUnit discovery never loads them before a test installs instrumentation).
 */
object JvmDefaultDisableFixtures {
    /** The fixture classes' package, dotted with a trailing dot. */
    const val PACKAGE_PREFIX = "com.example.target.jvmdefaultdisable."

    /** The fixture output directory, holding `com/example/target/jvmdefaultdisable/…` class files. */
    val outputDir: File
        get() =
            File(
                System.getProperty("yukon.fixtures.jvmdefaultdisable.dir")
                    ?: error("system property yukon.fixtures.jvmdefaultdisable.dir is not set; run tests through the root Gradle build"),
            )

    private val runtimeClasspath: List<File>
        get() =
            System
                .getProperty("yukon.fixtures.jvmdefaultdisable.classpath")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.map { File(it) }
                ?: error("system property yukon.fixtures.jvmdefaultdisable.classpath is not set; run tests through the root Gradle build")

    /** Raw class bytes for `com.example.target.jvmdefaultdisable.<simpleName>`. */
    fun classBytes(simpleName: String): ByteArray = File(outputDir, "com/example/target/jvmdefaultdisable/$simpleName.class").readBytes()

    /**
     * A child-first loader over the fixture module's own output plus its runtime classpath, so the
     * fixture classes are defined for the first time only after instrumentation is installed.
     */
    fun classLoader(parent: ClassLoader): FixtureClassLoader {
        val urls = (listOf(outputDir) + runtimeClasspath).map { it.toURI().toURL() }.toTypedArray()
        return FixtureClassLoader(urls, parent, PACKAGE_PREFIX)
    }
}
