package io.github.lukedevops.yukon.dependencies

import net.bytebuddy.ByteBuddy
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * Builds jars for the dependency listing tests. Class entries need no real bytecode, since the
 * listing reads only entry names. A `.jar` entry is written `STORED`, the way Spring Boot's Gradle
 * plugin writes `BOOT-INF/lib`.
 */
internal object TestJars {
    fun pom(
        group: String?,
        artifact: String?,
        version: String?,
    ): Pair<String, ByteArray> {
        val lines =
            listOfNotNull(
                group?.let { "groupId=$it" },
                artifact?.let { "artifactId=$it" },
                version?.let { "version=$it" },
            )
        val name = "META-INF/maven/${group ?: "g"}/${artifact ?: "a"}/pom.properties"
        return name to lines.joinToString("\n").toByteArray()
    }

    fun classEntry(className: String): Pair<String, ByteArray> = className.replace('.', '/') + ".class" to ByteArray(4)

    /** A class entry holding real bytecode for an empty class, for tests that load it. */
    fun loadableClassEntry(className: String): Pair<String, ByteArray> =
        className.replace('.', '/') + ".class" to
            ByteBuddy()
                .subclass(Any::class.java)
                .name(className)
                .make()
                .bytes

    fun bytes(
        entries: List<Pair<String, ByteArray>>,
        manifest: Map<String, String>? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val jar =
            if (manifest != null) {
                val m = Manifest()
                m.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                manifest.forEach { (k, v) -> m.mainAttributes.putValue(k, v) }
                JarOutputStream(out, m)
            } else {
                JarOutputStream(out)
            }
        jar.use {
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                if (name.endsWith(".jar")) {
                    val crc = CRC32().apply { update(content) }
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.compressedSize = content.size.toLong()
                    entry.crc = crc.value
                }
                it.putNextEntry(entry)
                it.write(content)
                it.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun write(
        path: Path,
        entries: List<Pair<String, ByteArray>>,
        manifest: Map<String, String>? = null,
    ): Path {
        Files.createDirectories(path.parent)
        Files.write(path, bytes(entries, manifest))
        return path
    }
}
