package io.github.lukedevops.yukon.instrumentation

import java.io.IOException
import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.util.jar.JarFile

/** The agent cannot run without its bootstrap holder; see [BootstrapHolder.install]. */
class BootstrapInstallException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Puts the `:bootstrap` module's one class onto the target JVM's bootstrap classloader.
 *
 * The holder jar ships embedded in the agent jar as a resource, under a name that does not end
 * in `.jar` because the shadow plugin would otherwise explode it into loose classes at build
 * time. `Instrumentation` only accepts a jar on disk, so it is copied to a temp file first, the
 * same approach OpenTelemetry's agent takes. A read-only filesystem or a full `java.io.tmpdir`
 * therefore makes the agent unusable, and [install] says so with a [BootstrapInstallException]
 * rather than leaving classes to fail in their own `<clinit>` later.
 */
object BootstrapHolder {
    const val DEFAULT_RESOURCE = "META-INF/yukon/bootstrap-jar.bin"
    const val HOLDER_CLASS_NAME = "io.github.lukedevops.yukon.bootstrap.YukonProbeArrays"
    const val ENDPOINTS_CLASS_NAME = "io.github.lukedevops.yukon.bootstrap.YukonEndpoints"

    /**
     * Idempotent: a JVM where the holder is already bootstrap-visible (a second install call, a
     * test suite installing repeatedly) is left alone.
     */
    @Synchronized
    fun install(
        instrumentation: Instrumentation,
        resourceName: String = DEFAULT_RESOURCE,
    ) {
        if (isInstalled()) return
        val bytes = readEmbeddedJar(resourceName)
        val jar =
            try {
                val temp = Files.createTempFile("yukon-bootstrap-", ".jar")
                temp.toFile().deleteOnExit()
                Files.write(temp, bytes)
                JarFile(temp.toFile())
            } catch (e: IOException) {
                throw BootstrapInstallException("yukon: could not write the bootstrap holder jar to a temp file", e)
            }
        instrumentation.appendToBootstrapClassLoaderSearch(jar)
        if (!isInstalled()) {
            throw BootstrapInstallException("yukon: appended the bootstrap holder jar but $HOLDER_CLASS_NAME is still not loadable")
        }
    }

    /** The embedded holder jar's bytes, or a [BootstrapInstallException] naming the resource that was not there. */
    internal fun readEmbeddedJar(resourceName: String): ByteArray =
        BootstrapHolder::class.java.classLoader
            .getResourceAsStream(resourceName)
            ?.use { it.readBytes() }
            ?: throw BootstrapInstallException("yukon: bootstrap holder jar not found on the agent classpath at $resourceName")

    /**
     * True only when every bootstrap-resident class is loadable from the bootstrap loader, not
     * just the first one. A stale embedded jar missing a class this agent added later would
     * otherwise pass this check and fail as a [NoClassDefFoundError] inside a framework class much
     * further downstream.
     */
    fun isInstalled(): Boolean = isLoadableFromBootstrap(HOLDER_CLASS_NAME) && isLoadableFromBootstrap(ENDPOINTS_CLASS_NAME)

    private fun isLoadableFromBootstrap(className: String): Boolean =
        try {
            Class.forName(className, false, null)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
}
