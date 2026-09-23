package io.github.lukedevops.yukon.config

import org.junit.jupiter.api.io.TempDir
import java.lang.module.ModuleDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainClassSuggestionTest {
    private val noJars: (String) -> Manifest? = { error("no jar should be read for this command, asked for $it") }

    private fun manifest(vararg attributes: Pair<String, String>): Manifest =
        Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            for ((name, value) in attributes) mainAttributes[Attributes.Name(name)] = value
        }

    private fun jars(vararg manifests: Pair<String, Manifest?>): (String) -> Manifest? = { path -> manifests.toMap()[path] }

    @Test
    fun `a plain class launch suggests the main class's package`() {
        val suggestion = MainClassSuggestion.fromCommand("com.acme.shop.ShopApplication --port 1", noJars)

        assertEquals(MainClassSuggestion("com.acme.shop.ShopApplication", "com.acme.shop"), suggestion)
    }

    @Test
    fun `a Kotlin file facade suggests its package`() {
        assertEquals("com.acme.shop", MainClassSuggestion.fromCommand("com.acme.shop.MainKt", noJars)?.prefix)
    }

    @Test
    fun `a jar with a Start-Class suggests that class's package, not the launcher's`() {
        val readManifest =
            jars(
                "app.jar" to
                    manifest(
                        "Main-Class" to "org.springframework.boot.loader.launch.JarLauncher",
                        "Start-Class" to "com.acme.shop.ShopApplication",
                    ),
            )

        val suggestion = MainClassSuggestion.fromCommand("app.jar --server.port=0", readManifest)

        assertEquals(MainClassSuggestion("com.acme.shop.ShopApplication", "com.acme.shop"), suggestion)
    }

    @Test
    fun `a jar with only a Main-Class suggests that class's package`() {
        val readManifest = jars("/opt/app/App.JAR" to manifest("Main-Class" to "com.acme.Main"))

        assertEquals("com.acme", MainClassSuggestion.fromCommand("/opt/app/App.JAR", readManifest)?.prefix)
    }

    @Test
    fun `a Boot war is read like a jar`() {
        val readManifest =
            jars(
                "shop.war" to
                    manifest(
                        "Main-Class" to "org.springframework.boot.loader.launch.WarLauncher",
                        "Start-Class" to "com.acme.shop.ShopApplication",
                    ),
            )

        assertEquals("com.acme.shop", MainClassSuggestion.fromCommand("shop.war", readManifest)?.prefix)
    }

    @Test
    fun `a jar whose manifest cannot be read, or names no main class, suggests nothing`() {
        val readManifest = jars("broken.jar" to null, "library.jar" to manifest("Implementation-Title" to "library"))

        assertNull(MainClassSuggestion.fromCommand("broken.jar", readManifest))
        assertNull(MainClassSuggestion.fromCommand("library.jar", readManifest))
    }

    @Test
    fun `a jar reader that throws suggests nothing`() {
        assertNull(MainClassSuggestion.fromCommand("app.jar", { throw IllegalStateException("simulated read failure") }))
    }

    @Test
    fun `a module launch suggests the package of the class after the slash`() {
        assertEquals("com.acme.shop", MainClassSuggestion.fromCommand("shop.module/com.acme.shop.Main arg", noJars)?.prefix)
    }

    /**
     * `java -m com.acme.shop` names the module alone, and `sun.java.command` is then the module
     * name (checked on JDK 22), so the main class is the one the module's descriptor declares.
     */
    @Test
    fun `a module launch with no class suggests the package of the module's declared main class`() {
        val shop = ModuleDescriptor.newModule("com.acme.shop").mainClass("com.acme.shop.web.ShopMain").build()
        val suggestion = MainClassSuggestion.fromCommand("com.acme.shop arg", noJars) { name -> shop.takeIf { name == "com.acme.shop" } }

        assertEquals("com.acme.shop.web.ShopMain", suggestion?.mainClass)
        assertEquals("com.acme.shop.web", suggestion?.prefix)
    }

    @Test
    fun `a module launch whose module declares no main class suggests nothing`() {
        val shop = ModuleDescriptor.newModule("com.acme.shop").build()

        assertNull(MainClassSuggestion.fromCommand("com.acme.shop", noJars) { name -> shop.takeIf { name == "com.acme.shop" } })
    }

    @Test
    fun `a source-file launch, a default-package class, and a missing command suggest nothing`() {
        assertNull(MainClassSuggestion.fromCommand("Main.java", noJars))
        assertNull(MainClassSuggestion.fromCommand("Main arg", noJars))
        assertNull(MainClassSuggestion.fromCommand(null, noJars))
        assertNull(MainClassSuggestion.fromCommand("", noJars))
        assertNull(MainClassSuggestion.fromCommand("   ", noJars))
    }

    @Test
    fun `a launcher or framework main class suggests nothing`() {
        for (launcher in listOf(
            "org.springframework.boot.loader.launch.JarLauncher",
            "org.springframework.boot.loader.JarLauncher",
            "io.ktor.server.netty.EngineMain",
            "org.apache.catalina.startup.Bootstrap",
        )) {
            assertNull(MainClassSuggestion.fromCommand("$launcher start", noJars), launcher)
        }
    }

    @Test
    fun `a jar whose Main-Class is a Boot launcher and has no Start-Class suggests nothing`() {
        val readManifest = jars("app.jar" to manifest("Main-Class" to "org.springframework.boot.loader.JarLauncher"))

        assertNull(MainClassSuggestion.fromCommand("app.jar", readManifest))
    }

    @Test
    fun `a package that only shares a framework prefix's leading characters still suggests`() {
        assertEquals("io.ktor.serverless", MainClassSuggestion.fromCommand("io.ktor.serverless.Main", noJars)?.prefix)
    }

    @Test
    fun `the production reader returns a real jar's manifest and null for a path it cannot open`(
        @TempDir dir: Path,
    ) {
        val jar = dir.resolve("app.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest("Main-Class" to "com.acme.Main")).close()

        assertEquals("com.acme.Main", MainClassSuggestion.readJarManifest(jar.toString())?.mainAttributes?.getValue("Main-Class"))
        assertNull(MainClassSuggestion.readJarManifest(dir.resolve("missing.jar").toString()))
    }

    @Test
    fun `the refusal names the option, says the agent is disabled, and says how to set it`() {
        val message = IncludeRulesRefusal.message(null)

        assertTrue(message.startsWith("yukon: "), message)
        assertTrue(message.contains("includePackages"), message)
        assertTrue(message.contains("disabled for this JVM"), message)
        assertTrue(message.contains("nothing will be instrumented or exported"), message)
        assertTrue(message.contains("';'-separated"), message)
        assertFalse(message.contains("the main class is"), message)
    }

    @Test
    fun `the refusal states the main class and the value that covers its package when one is known`() {
        val message = IncludeRulesRefusal.message(MainClassSuggestion("com.acme.shop.ShopApplication", "com.acme.shop"))

        assertTrue(message.startsWith("yukon: "), message)
        assertTrue(message.contains("disabled for this JVM"), message)
        assertTrue(
            message.endsWith(
                "the main class is com.acme.shop.ShopApplication; includePackages=com.acme.shop covers its package, " +
                    "or name a broader prefix that also covers your shared libraries",
            ),
            message,
        )
    }
}
