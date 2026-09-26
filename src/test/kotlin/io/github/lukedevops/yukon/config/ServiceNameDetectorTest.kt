package io.github.lukedevops.yukon.config

import io.github.lukedevops.yukon.dependencies.TestJars
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceNameDetectorTest {
    @TempDir
    lateinit var dir: Path

    private fun detector(
        arguments: List<String> = emptyList(),
        properties: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
    ) = ServiceNameDetector(env::get, properties::get, { arguments }, dir)

    private fun write(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.createDirectories(it.parent) }.also { Files.writeString(it, text) }

    private fun jar(
        name: String,
        entries: List<Pair<String, ByteArray>> = emptyList(),
        manifest: Map<String, String>? = null,
    ): Path = TestJars.write(dir.resolve(name), entries, manifest)

    private fun entry(
        name: String,
        text: String,
    ) = name to text.toByteArray()

    @Test
    fun `nothing to detect gives null`() {
        assertNull(detector(properties = mapOf("sun.java.command" to "com.acme.Main --port 1")).detect())
    }

    @Test
    fun `a spring application name argument on the process command line is read`() {
        val detector = detector(arguments = listOf("-jar", "missing.jar", "--spring.application.name=from-args"))

        assertEquals("from-args", detector.detect())
    }

    @Test
    fun `a spring application name argument in sun java command is read`() {
        val detector = detector(properties = mapOf("sun.java.command" to "com.acme.Main --spring.application.name=from-command --x"))

        assertEquals("from-command", detector.detect())
    }

    @Test
    fun `the spring application name system property is read`() {
        assertEquals("from-property", detector(properties = mapOf("spring.application.name" to "from-property")).detect())
    }

    @Test
    fun `the SPRING_APPLICATION_NAME environment variable is read`() {
        assertEquals("from-env", detector(env = mapOf("SPRING_APPLICATION_NAME" to "from-env")).detect())
    }

    @Test
    fun `the Spring sources rank in OpenTelemetry's order`() {
        val all =
            detector(
                arguments = listOf("--spring.application.name=from-args"),
                properties =
                    mapOf(
                        "sun.java.command" to "com.acme.Main --spring.application.name=from-command",
                        "spring.application.name" to "from-property",
                    ),
                env = mapOf("SPRING_APPLICATION_NAME" to "from-env"),
            )
        assertEquals("from-args", all.detect())

        val noArguments =
            detector(
                properties =
                    mapOf(
                        "sun.java.command" to "com.acme.Main --spring.application.name=from-command",
                        "spring.application.name" to "from-property",
                    ),
                env = mapOf("SPRING_APPLICATION_NAME" to "from-env"),
            )
        assertEquals("from-command", noArguments.detect())

        val propertyAndEnv =
            detector(properties = mapOf("spring.application.name" to "from-property"), env = mapOf("SPRING_APPLICATION_NAME" to "from-env"))
        assertEquals("from-property", propertyAndEnv.detect())

        write("application.properties", "spring.application.name=from-file\n")
        assertEquals("from-env", detector(env = mapOf("SPRING_APPLICATION_NAME" to "from-env")).detect())
    }

    @Test
    fun `a blank Spring source falls through to the next one`() {
        val detector =
            detector(properties = mapOf("spring.application.name" to "  "), env = mapOf("SPRING_APPLICATION_NAME" to "from-env"))

        assertEquals("from-env", detector.detect())
    }

    @Test
    fun `application properties in the working directory is read`() {
        write("application.properties", "# comment\nspring.application.name = from-properties \n")

        assertEquals("from-properties", detector().detect())
    }

    @Test
    fun `application yml in the working directory is read, and properties ranks above it`() {
        write("application.yml", "spring:\n  application:\n    name: from-yml\n")
        assertEquals("from-yml", detector().detect())

        write("application.properties", "spring.application.name=from-properties\n")
        assertEquals("from-properties", detector().detect())
    }

    @Test
    fun `application yaml in the working directory is read after application yml`() {
        write("application.yaml", "spring:\n  application:\n    name: from-yaml\n")
        assertEquals("from-yaml", detector().detect())

        write("application.yml", "spring:\n  application:\n    name: from-yml\n")
        assertEquals("from-yml", detector().detect())
    }

    @Test
    fun `a working directory file ranks above the class path`() {
        val classes = dir.resolve("classes")
        write("classes/application.properties", "spring.application.name=from-classpath\n")
        write("application.yaml", "spring:\n  application:\n    name: from-working-directory\n")

        assertEquals("from-working-directory", detector(properties = mapOf("java.class.path" to classes.toString())).detect())
    }

    @Test
    fun `class path files are read from directories and jars in class path order`() {
        val classes = dir.resolve("classes")
        Files.createDirectories(classes)
        val lib = jar("lib/app.jar", listOf(entry("application.yml", "spring:\n  application:\n    name: from-jar\n")))
        val classPath = listOf(classes, lib).joinToString(File.pathSeparator)
        assertEquals("from-jar", detector(properties = mapOf("java.class.path" to classPath)).detect())

        write("classes/application.properties", "spring.application.name=from-directory\n")
        assertEquals("from-directory", detector(properties = mapOf("java.class.path" to classPath)).detect())
    }

    @Test
    fun `a relative class path entry resolves against the working directory`() {
        write("classes/application.properties", "spring.application.name=relative\n")

        assertEquals("relative", detector(properties = mapOf("java.class.path" to "classes")).detect())
    }

    @Test
    fun `bootstrap files are read after every application file on the class path`() {
        val classes = dir.resolve("classes")
        write("classes/bootstrap.properties", "spring.application.name=from-bootstrap\n")
        assertEquals("from-bootstrap", detector(properties = mapOf("java.class.path" to classes.toString())).detect())

        write("classes/application.yaml", "spring:\n  application:\n    name: from-application\n")
        assertEquals("from-application", detector(properties = mapOf("java.class.path" to classes.toString())).detect())
    }

    @Test
    fun `bootstrap yml and yaml on the class path are read`() {
        val classes = dir.resolve("classes")
        write("classes/bootstrap.yaml", "spring:\n  application:\n    name: from-bootstrap-yaml\n")
        assertEquals("from-bootstrap-yaml", detector(properties = mapOf("java.class.path" to classes.toString())).detect())

        write("classes/bootstrap.yml", "spring:\n  application:\n    name: from-bootstrap-yml\n")
        assertEquals("from-bootstrap-yml", detector(properties = mapOf("java.class.path" to classes.toString())).detect())
    }

    @Test
    fun `a class path scan opens each jar once and finds every candidate file in that pass`() {
        val plain = jar("plain.jar", listOf(entry("application.properties", "a"), entry("bootstrap.yml", "b")))
        val boot =
            jar(
                "boot.jar",
                listOf("BOOT-INF/classes/" to ByteArray(0), entry("BOOT-INF/classes/application.yml", "c"), entry("BOOT-INF/classes/bootstrap.yml", "d")),
            )
        val classes = dir.resolve("classes")
        write("classes/BOOT-INF/classes/application.yaml", "e")
        val opened = mutableListOf<Path>()

        val files =
            ClasspathFiles.scan(
                listOf(plain, classes, boot).joinToString(File.pathSeparator),
                dir,
                listOf("application.properties", "application.yml", "application.yaml", "bootstrap.yml"),
            ) { path ->
                opened.add(path)
                JarFile(path.toFile(), false)
            }

        assertEquals(listOf(plain, boot), opened)
        assertNull(files.read("application.properties"), "the BOOT-INF/classes/ prefix applies to every entry once any entry holds it")
        assertEquals("c", files.read("application.yml")?.toString(Charsets.UTF_8))
        assertEquals("e", files.read("application.yaml")?.toString(Charsets.UTF_8))
        assertEquals("d", files.read("bootstrap.yml")?.toString(Charsets.UTF_8))
    }

    @Test
    fun `a Spring Boot executable jar is read under BOOT-INF classes`() {
        val boot =
            jar(
                "shop.jar",
                listOf(
                    "BOOT-INF/classes/" to ByteArray(0),
                    entry("BOOT-INF/classes/application.properties", "spring.application.name=from-boot-jar\n"),
                    entry("application.properties", "spring.application.name=outside-boot-inf\n"),
                ),
            )

        val detector = detector(arguments = listOf("-jar", boot.toString()), properties = mapOf("java.class.path" to boot.toString()))

        assertEquals("from-boot-jar", detector.detect())
    }

    @Test
    fun `the main jar's manifest title ranks above the jar's file name`() {
        val app = jar("checkout-1.0.jar", manifest = mapOf("Implementation-Title" to "Checkout Service"))

        assertEquals("Checkout Service", detector(arguments = listOf("-jar", app.toString())).detect())
    }

    @Test
    fun `a Spring source ranks above the main jar`() {
        val app = jar("checkout-1.0.jar", manifest = mapOf("Implementation-Title" to "Checkout Service"))

        val detector = detector(arguments = listOf("-jar", app.toString()), env = mapOf("SPRING_APPLICATION_NAME" to "from-spring"))

        assertEquals("from-spring", detector.detect())
    }

    @Test
    fun `the jar's file name without its extension names a jar with no manifest title`() {
        val app = jar("checkout-1.0.jar", manifest = mapOf("Main-Class" to "com.acme.Main"))

        assertEquals("checkout-1.0", detector(arguments = listOf("-jar", app.toString())).detect())
    }

    @Test
    fun `flags between -jar and the jar path are skipped`() {
        val app = jar("checkout.jar")

        assertEquals("checkout", detector(arguments = listOf("-Xmx1g", "-jar", "-Dx=y", app.toString(), "--port=1")).detect())
    }

    @Test
    fun `a relative jar path after -jar resolves against the working directory`() {
        jar("checkout.jar", manifest = mapOf("Implementation-Title" to "relative-title"))

        assertEquals("relative-title", detector(arguments = listOf("-jar", "checkout.jar")).detect())
    }

    @Test
    fun `a jar path after -jar that does not exist still names the service by its file name`() {
        assertEquals("gone", detector(arguments = listOf("-jar", "gone.jar")).detect())
    }

    @Test
    fun `sun java command names the main jar when the command line has no -jar`() {
        val app = jar("with space/orders.jar", manifest = mapOf("Implementation-Title" to "Orders"))

        val detector = detector(properties = mapOf("sun.java.command" to "$app --port 1"))

        assertEquals(app, detector.mainJar())
        assertEquals("Orders", detector.detect())
    }

    @Test
    fun `a class launch has no main jar`() {
        assertNull(detector(properties = mapOf("sun.java.command" to "com.acme.Main arg")).mainJar())
    }

    @Test
    fun `an unreadable jar falls through to its file name, and an unreadable class path entry is skipped`() {
        val broken = write("broken.jar", "not a zip")
        val detector = detector(arguments = listOf("-jar", broken.toString()), properties = mapOf("java.class.path" to broken.toString()))

        assertEquals("broken", detector.detect())
    }

    @Test
    fun `a malformed properties file falls through to the next source`() {
        write("application.properties", "spring.application.name=\\u12")
        write("application.yml", "spring:\n  application:\n    name: from-yml\n")

        assertEquals("from-yml", detector().detect())
    }

    @Test
    fun `a failing source never throws out of detect`() {
        val detector = ServiceNameDetector({ throw IllegalStateException("env") }, { null }, { throw IllegalStateException("args") }, dir)

        assertNull(detector.detect())
    }
}

class SpringYamlNameTest {
    @Test
    fun `nested block keys name the application`() {
        assertEquals("shop", SpringYamlName.of("spring:\n  application:\n    name: shop\n"))
    }

    @Test
    fun `sibling keys, comments and blank lines are skipped`() {
        val yaml =
            """
            # top comment
            server:
              port: 8080
            spring:
              datasource:
                url: jdbc:x

              application:
                # the name
                name: shop # trailing comment
            """.trimIndent()

        assertEquals("shop", SpringYamlName.of(yaml))
    }

    @Test
    fun `quoted scalars lose their quotes`() {
        assertEquals("my shop", SpringYamlName.of("spring:\n  application:\n    name: \"my shop\"\n"))
        assertEquals("a # b", SpringYamlName.of("spring:\n  application:\n    name: 'a # b'\n"))
    }

    @Test
    fun `a name key outside spring application is not read`() {
        assertNull(SpringYamlName.of("spring:\n  datasource:\n    name: db\napplication:\n  name: other\nname: top\n"))
    }

    @Test
    fun `a name key after spring application closes is not read`() {
        assertNull(SpringYamlName.of("spring:\n  application:\n    admin: true\n  name: not-this\n"))
    }

    @Test
    fun `the first document that names the application wins`() {
        val yaml = "server:\n  port: 1\n---\nspring:\n  application:\n    name: first\n---\nspring:\n  application:\n    name: second\n"

        assertEquals("first", SpringYamlName.of(yaml))
    }

    @Test
    fun `a dotted key is not read, as in OpenTelemetry's detector`() {
        assertNull(SpringYamlName.of("spring.application.name: shop\n"))
    }

    @Test
    fun `flow mappings and block scalars are not read`() {
        assertNull(SpringYamlName.of("spring: {application: {name: shop}}\n"))
        assertNull(SpringYamlName.of("spring:\n  application:\n    name: |\n      shop\n"))
    }

    @Test
    fun `sequence items do not become keys`() {
        assertEquals("shop", SpringYamlName.of("spring:\n  profiles:\n    - name: dev\n  application:\n    name: shop\n"))
    }
}
