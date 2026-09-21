package io.github.lukedevops.yukon.dependencies

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A class's code-source location, reduced to where its bytes live on disk. See [parse].
 */
internal sealed interface CodeSourceLocation {
    /** A file or directory, from a `file:` URL or a `jar:file:<jar>!/` URL naming no entry. */
    data class OnDisk(
        val path: Path,
    ) : CodeSourceLocation

    /**
     * An entry inside a jar on disk: a nested jar such as `BOOT-INF/lib/x.jar`, or a classes
     * directory such as `BOOT-INF/classes/`.
     */
    data class InJar(
        val outerJar: Path,
        val entryName: String,
    ) : CodeSourceLocation {
        /** Whether the entry is a directory of classes rather than a jar. */
        val isDirectory: Boolean
            get() = entryName.endsWith("/") || entryName in CLASSES_DIRECTORIES
    }

    /** A scheme this agent does not read, such as `jrt:` or `jar:http:`. */
    data object Unsupported : CodeSourceLocation

    companion object {
        private val CLASSES_DIRECTORIES = setOf("BOOT-INF/classes", "WEB-INF/classes")
        private const val NESTED_PREFIX = "jar:nested:"
        private const val JAR_PREFIX = "jar:"
        private const val JAR_SEPARATOR = "!/"

        /**
         * Parses [location], a code-source URL's string form. Three shapes name bytes on disk:
         *
         * - `file:<path>`, a flat jar or a classes directory.
         * - `jar:nested:<outer>/!<entry>!/`, Spring Boot 3.2 and later. The path is percent-decoded
         *   and split at the last `/!`, and on Windows loses the slash before its drive letter, as
         *   Boot's `NestedLocation` does.
         * - `jar:file:<outer>!/<entry>!/`, Spring Boot 2 to 3.1, or `jar:file:<jar>!/` from a
         *   plain `URLClassLoader` given a `jar:` URL.
         *
         * Anything else is [Unsupported]. A malformed `file:` URI throws.
         */
        fun parse(location: String): CodeSourceLocation =
            when {
                location.startsWith("file:") -> OnDisk(Paths.get(URI(location)))
                location.startsWith(NESTED_PREFIX) -> parseNested(location.removePrefix(NESTED_PREFIX))
                location.startsWith(JAR_PREFIX) -> parseJarUrl(location.removePrefix(JAR_PREFIX))
                else -> Unsupported
            }

        private fun parseNested(rest: String): CodeSourceLocation {
            // Boot's UrlDecoder decodes percent escapes only, so a literal '+' must survive URLDecoder.
            val decoded = URLDecoder.decode(rest.removeSuffix(JAR_SEPARATOR).replace("+", "%2B"), Charsets.UTF_8)
            val split = decoded.lastIndexOf("/!")
            if (split < 0) return OnDisk(nestedPath(decoded))
            return InJar(nestedPath(decoded.substring(0, split)), decoded.substring(split + 2))
        }

        private fun nestedPath(path: String): Path = Path.of(if (File.separatorChar == '\\') windowsNestedPath(path) else path)

        /**
         * Drops the leading slash a `nested:` URL puts before a Windows drive letter (`/C:/app.jar`,
         * `///C:/app.jar`), as Boot's `NestedLocation` does before building a path on Windows.
         */
        internal fun windowsNestedPath(path: String): String =
            when {
                path.length > 2 && path[2] == ':' -> path.substring(1)
                path.startsWith("///") && path.length > 4 && path[4] == ':' -> path.substring(3)
                else -> path
            }

        private fun parseJarUrl(rest: String): CodeSourceLocation {
            val split = rest.indexOf(JAR_SEPARATOR)
            if (split < 0) return Unsupported
            val outer = rest.substring(0, split)
            if (!outer.startsWith("file:")) return Unsupported
            val outerPath = Paths.get(URI(outer))
            val entry = rest.substring(split + JAR_SEPARATOR.length).removeSuffix(JAR_SEPARATOR)
            return if (entry.isEmpty()) OnDisk(outerPath) else InJar(outerPath, entry)
        }
    }
}
