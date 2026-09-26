package io.github.lukedevops.yukon.config

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.jar.JarFile

/**
 * Finds a service name for a JVM that names none, from the same sources and in the same order as
 * OpenTelemetry's Java agent. [AgentConfig] asks for it only when no Yukon or OpenTelemetry setting
 * names the service. See ADR 0045.
 *
 * The agent detectors this mirrors run in this order, and the first one that finds a name wins:
 *
 * 1. `SpringBootServiceNameDetector`. [springBootName] lists its sources.
 * 2. `ManifestResourceProvider`: the `Implementation-Title` of the main jar's manifest.
 * 3. `JarServiceNameDetector`: the main jar's file name without its extension.
 *
 * The main jar is the argument after `-jar` on the process command line. Failing that, it is the
 * shortest leading part of `sun.java.command`, cut at a space, that names a regular file.
 *
 * Detection reads files, the command line, system properties and the environment. It never loads
 * an application class. Each source that fails, for any reason, gives no name, and detection moves
 * on to the next source. [detect] never throws.
 *
 * [processArguments] is the full command line after the executable, as
 * `ProcessHandle.Info.arguments` gives it. [workingDirectory] resolves the relative paths on that
 * command line, on `java.class.path`, and for the Spring Boot files read from the working directory.
 */
internal class ServiceNameDetector(
    private val env: (String) -> String?,
    private val systemProperties: (String) -> String?,
    private val processArguments: () -> List<String>,
    private val workingDirectory: Path,
) {
    /** The first name any source finds, trimmed, or null when none finds one. */
    fun detect(): String? =
        try {
            springBootName() ?: mainJar()?.let { manifestTitle(it) ?: jarName(it) }
        } catch (e: Exception) {
            null
        }

    /**
     * The Spring Boot application name, from the first of these that sets `spring.application.name`:
     *
     * 1. a `--spring.application.name=` argument on the process command line;
     * 2. the same argument in `sun.java.command`;
     * 3. the `spring.application.name` system property;
     * 4. the `SPRING_APPLICATION_NAME` environment variable;
     * 5. `application.properties`, `application.yml`, then `application.yaml` in the working
     *    directory;
     * 6. `application.properties`, `application.yml`, `application.yaml`, `bootstrap.properties`,
     *    `bootstrap.yml`, then `bootstrap.yaml` on the class path, all read in one pass by
     *    [ClasspathFiles.scan] the first time a class path file is needed.
     *
     * A YAML file is read by [SpringYamlName], which covers block mappings only.
     */
    fun springBootName(): String? {
        val sources: List<() -> String?> =
            listOf(
                { processArguments().firstOrNull { it.startsWith(SPRING_NAME_ARGUMENT) }?.removePrefix(SPRING_NAME_ARGUMENT) },
                { systemProperties("sun.java.command")?.let { SPRING_NAME_IN_COMMAND.find(it)?.groupValues?.get(1) } },
                { systemProperties("spring.application.name") },
                { env("SPRING_APPLICATION_NAME") },
            ) +
                SPRING_FILES_IN_WORKING_DIRECTORY.map { name ->
                    { readRegularFile(workingDirectory.resolve(name))?.let { springName(name, it) } }
                } +
                SPRING_FILES_ON_CLASSPATH.map { name ->
                    { classpathFiles.read(name)?.let { springName(name, it) } }
                }
        return sources.firstNotNullOfOrNull { source -> textOrNull(source) }
    }

    /** The main jar, or null when the JVM was not launched from one. */
    fun mainJar(): Path? = attempt { jarAfterJarFlag() } ?: attempt { jarAtStartOfCommand() }

    private val classpathFiles by lazy {
        ClasspathFiles.scan(systemProperties("java.class.path"), workingDirectory, SPRING_FILES_ON_CLASSPATH)
    }

    private fun jarAfterJarFlag(): Path? {
        val arguments = processArguments()
        val flag = arguments.indexOf("-jar")
        if (flag < 0) return null
        return arguments.drop(flag + 1).firstOrNull { !it.startsWith("-") }?.let { workingDirectory.resolve(it) }
    }

    private fun jarAtStartOfCommand(): Path? {
        val command = systemProperties("sun.java.command") ?: return null
        var end = command.indexOf(' ')
        while (true) {
            val candidate = if (end < 0) command else command.substring(0, end)
            val path = attempt { workingDirectory.resolve(candidate) }
            if (path != null && Files.isRegularFile(path)) return path
            if (end < 0) return null
            end = command.indexOf(' ', end + 1)
        }
    }

    private fun manifestTitle(jar: Path): String? =
        textOrNull {
            JarFile(jar.toFile(), false).use { it.manifest?.mainAttributes?.getValue("Implementation-Title") }
        }

    private fun jarName(jar: Path): String? = textOrNull { jar.fileName?.toString()?.substringBeforeLast('.') }

    companion object {
        private const val SPRING_NAME_ARGUMENT = "--spring.application.name="
        private val SPRING_NAME_IN_COMMAND = Regex("--spring\\.application\\.name=(\\S+)")
        private val SPRING_FILES_IN_WORKING_DIRECTORY = listOf("application.properties", "application.yml", "application.yaml")
        private val SPRING_FILES_ON_CLASSPATH =
            SPRING_FILES_IN_WORKING_DIRECTORY + listOf("bootstrap.properties", "bootstrap.yml", "bootstrap.yaml")

        /** A detector that reads this JVM's own command line, working directory and class path. */
        fun forThisProcess(
            env: (String) -> String?,
            systemProperties: (String) -> String?,
        ): ServiceNameDetector =
            ServiceNameDetector(
                env = env,
                systemProperties = systemProperties,
                processArguments = {
                    ProcessHandle
                        .current()
                        .info()
                        .arguments()
                        .map { it.toList() }
                        .orElse(emptyList())
                },
                workingDirectory = Paths.get("").toAbsolutePath(),
            )

        private fun springName(
            fileName: String,
            content: ByteArray,
        ): String? =
            if (fileName.endsWith(".properties")) {
                // Properties.load reads ISO 8859-1, as Spring does for a properties file by default.
                Properties().apply { load(ByteArrayInputStream(content)) }.getProperty("spring.application.name")
            } else {
                SpringYamlName.of(content.toString(Charsets.UTF_8))
            }

        private fun readRegularFile(path: Path): ByteArray? = if (Files.isRegularFile(path)) Files.readAllBytes(path) else null

        private inline fun <T : Any> attempt(find: () -> T?): T? =
            try {
                find()
            } catch (e: Exception) {
                null
            }

        private inline fun textOrNull(find: () -> String?): String? = attempt(find)?.trim()?.ifEmpty { null }
    }
}

/**
 * The files named in one [scan] of the class path, found the way the system class loader would
 * find them as resources, without asking a class loader. Each class path entry is a directory or a
 * jar. When any entry holds `BOOT-INF/classes/`, the JVM is running a Spring Boot executable jar,
 * so every name is looked up under that prefix, as OpenTelemetry's detector does. For each name,
 * the first entry in class path order that holds it wins.
 *
 * A jar nested inside another jar, such as one under `BOOT-INF/lib/`, is never opened. The
 * `Class-Path` attribute of a jar's manifest is not followed.
 */
internal class ClasspathFiles private constructor(
    private val found: Map<String, ByteArray>,
) {
    /** The bytes of the file named [name], or null when no class path entry holds it. */
    fun read(name: String): ByteArray? = found[name]

    /** What one class path entry holds: whether it has `BOOT-INF/classes/`, and the files found by path. */
    private class EntryContents(
        val holdsBootInfClasses: Boolean,
        val files: Map<String, ByteArray>,
    )

    companion object {
        private const val BOOT_INF_CLASSES = "BOOT-INF/classes/"
        private val NOTHING = EntryContents(false, emptyMap())

        /**
         * Reads every file in [names] from [classPath] in one pass. Each entry is opened at most
         * once, and both the plain and the `BOOT-INF/classes/` path of every name are read from it
         * then, since the prefix rule needs every entry before it can choose. [openJar] opens a
         * jar entry. Relative entries resolve against [workingDirectory]. An entry that cannot be
         * read holds nothing.
         */
        fun scan(
            classPath: String?,
            workingDirectory: Path,
            names: List<String>,
            openJar: (Path) -> JarFile = { JarFile(it.toFile(), false) },
        ): ClasspathFiles {
            val paths = names.flatMap { listOf(it, BOOT_INF_CLASSES + it) }
            val contents =
                classPath
                    ?.split(File.pathSeparator)
                    ?.filter { it.isNotBlank() }
                    ?.map { entry -> read(entry, workingDirectory, paths, openJar) }
                    ?: emptyList()
            val prefix = if (contents.any { it.holdsBootInfClasses }) BOOT_INF_CLASSES else ""
            val found = LinkedHashMap<String, ByteArray>()
            for (name in names) {
                contents.firstNotNullOfOrNull { it.files[prefix + name] }?.let { found[name] = it }
            }
            return ClasspathFiles(found)
        }

        private fun read(
            entry: String,
            workingDirectory: Path,
            paths: List<String>,
            openJar: (Path) -> JarFile,
        ): EntryContents =
            try {
                val root = workingDirectory.resolve(entry)
                when {
                    Files.isDirectory(root) -> {
                        EntryContents(
                            Files.isDirectory(root.resolve(BOOT_INF_CLASSES)),
                            paths
                                .mapNotNull { path ->
                                    val file = root.resolve(path)
                                    if (Files.isRegularFile(file)) path to Files.readAllBytes(file) else null
                                }.toMap(),
                        )
                    }

                    Files.isRegularFile(root) -> {
                        openJar(root).use { jar ->
                            EntryContents(
                                jar.getEntry(BOOT_INF_CLASSES) != null,
                                paths
                                    .mapNotNull { path -> jar.getJarEntry(path)?.let { path to jar.getInputStream(it).readBytes() } }
                                    .toMap(),
                            )
                        }
                    }

                    else -> {
                        NOTHING
                    }
                }
            } catch (e: Exception) {
                NOTHING
            }
    }
}

/**
 * Reads `spring.application.name` from Spring YAML text, as nested keys: `spring:`, then
 * `application:` under it, then `name:` under that. A file can hold several documents split by
 * `---`, and the first one that names the application wins.
 *
 * This covers block mappings with plain, single-quoted or double-quoted scalars and `#` comments.
 * It does not read flow mappings (`{...}`), block scalars (`|`, `>`), anchors, aliases or escapes.
 * A dotted key such as `spring.application.name:` is not read either, which matches
 * OpenTelemetry's detector.
 */
internal object SpringYamlName {
    private val KEY_LINE = Regex("""^(?:"([^"]*)"|'([^']*)'|([^\s#'"][^:#]*?))\s*:(?:\s+(.*))?$""")
    private val COMMENT = Regex("""\s#""")
    private val TARGET = listOf("spring", "application", "name")

    /** The application name in [text], or null when no document names one. */
    fun of(text: String): String? {
        var path = mutableListOf<Pair<Int, String>>()
        for (rawLine in text.removePrefix("\uFEFF").lines()) {
            val line = rawLine.trimEnd()
            val content = line.trimStart()
            if (content.startsWith("---") && line == content && (content.length == 3 || content[3].isWhitespace())) {
                path = mutableListOf()
                continue
            }
            if (content.isEmpty() || content.startsWith("#")) continue
            val indent = line.length - content.length
            while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.lastIndex)
            if (content == "-" || content.startsWith("- ")) continue
            val match = KEY_LINE.matchEntire(content) ?: continue
            val key = match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }.trim()
            path.add(indent to key)
            if (path.map { it.second } == TARGET) {
                scalar(match.groupValues[4])?.let { return it }
            }
        }
        return null
    }

    private fun scalar(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val quote = value[0]
        if (quote == '"' || quote == '\'') {
            val end = value.indexOf(quote, 1)
            return if (end < 0) null else value.substring(1, end).trim().ifEmpty { null }
        }
        if (value[0] in "{[|>&*!") return null
        val comment = COMMENT.find(value)?.range?.first
        return (if (comment == null) value else value.substring(0, comment)).trim().ifEmpty { null }
    }
}
