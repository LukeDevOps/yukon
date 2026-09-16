package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.instrumentation.FixtureClassLoader
import java.io.File

/**
 * Reads compiled bytes and builds a child-first classloader for the `:fixtures-scala3` and
 * `:fixtures-scala2` modules, whose output directory and runtime classpath the root build passes
 * in as system properties (never as a test dependency, so JUnit discovery never loads them before
 * a test installs instrumentation).
 */
object ScalaFixtures {
    private const val SCALA_TARGET_PACKAGE_PREFIX = "com.example.scalatarget."

    private fun outputDir(module: String): File =
        File(
            System.getProperty("yukon.fixtures.$module.dir")
                ?: error("system property yukon.fixtures.$module.dir is not set; run tests through the root Gradle build"),
        )

    private fun runtimeClasspath(module: String): List<File> =
        System
            .getProperty("yukon.fixtures.$module.classpath")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.map { File(it) }
            ?: error("system property yukon.fixtures.$module.classpath is not set; run tests through the root Gradle build")

    /** Raw class bytes for `com.example.scalatarget.<simpleName>`, compiled by fixture module `module` (`scala3` or `scala2`). */
    fun classBytes(
        module: String,
        simpleName: String,
    ): ByteArray = File(outputDir(module), "com/example/scalatarget/$simpleName.class").readBytes()

    /**
     * A child-first loader over the fixture module's own output plus its resolved Scala library
     * jars, so `com.example.scalatarget.*` is defined for the first time only after
     * instrumentation is installed.
     */
    fun classLoader(
        module: String,
        parent: ClassLoader,
    ): FixtureClassLoader {
        val urls = (listOf(outputDir(module)) + runtimeClasspath(module)).map { it.toURI().toURL() }.toTypedArray()
        return FixtureClassLoader(urls, parent, SCALA_TARGET_PACKAGE_PREFIX)
    }
}
