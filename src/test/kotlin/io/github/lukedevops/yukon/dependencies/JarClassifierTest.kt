package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.registry.DependencyOrigin
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JarClassifierTest {
    private val jarPath = Path.of("/libs/lib-1.0.jar").toAbsolutePath()

    private fun contents(vararg classNames: String) = JarContents(classNames.toSet(), emptyList(), null)

    private fun classifyFlat(
        classifier: JarClassifier,
        contents: JarContents,
    ): ListedDependency? = classifier.classifyFlat(contents, "lib-1.0.jar", jarPath.toString(), DependencyOrigin.FlatJar(jarPath))

    /**
     * ADR 0033: an empty include list admits no class, so no jar is the adopter's own and every
     * ordinary jar, whatever packages it holds, is a dependency.
     */
    @Test
    fun `with an empty include list every ordinary jar is a dependency, flat or nested`() {
        val classifier = JarClassifier(emptyList(), emptyList())
        val jars =
            listOf(
                contents("org.lib.L"),
                contents("com.acme.shop.App", "com.acme.shop.Cart"),
                contents("com.acme.shop.App", "org.lib.L"),
                contents("com.sun.net.httpserver.HttpServer"),
            )

        for (jar in jars) {
            assertNotNull(classifyFlat(classifier, jar), "flat ${jar.classNames}")
            assertNotNull(
                classifier.classifyNested(
                    jar,
                    "lib-1.0.jar",
                    "app.jar!/BOOT-INF/lib/lib-1.0.jar",
                    "BOOT-INF/lib/lib-1.0.jar",
                    DependencyOrigin.NestedJar(Path.of("/app.jar").toAbsolutePath(), "BOOT-INF/lib/lib-1.0.jar"),
                ),
                "nested ${jar.classNames}",
            )
        }
    }

    @Test
    fun `with an empty include list and excludes set every ordinary jar is still a dependency`() {
        val classifier = JarClassifier(emptyList(), listOf("com.acme.generated"))

        val listed = classifyFlat(classifier, contents("com.acme.shop.App"))

        assertEquals("lib", listed?.identities?.single()?.artifactId)
    }

    @Test
    fun `with an include list set a jar holding an included class is the adopter's own`() {
        val classifier = JarClassifier(listOf("com.acme"), emptyList())

        assertNull(classifyFlat(classifier, contents("com.acme.shop.App")))
        assertNotNull(classifyFlat(classifier, contents("org.lib.L")))
    }
}
