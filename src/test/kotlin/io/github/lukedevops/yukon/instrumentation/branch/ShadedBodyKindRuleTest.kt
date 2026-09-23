package io.github.lukedevops.yukon.instrumentation.branch

import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs [BranchSiteAnalyzer]'s `body_kind` rule from the shaded agent jar. `shadowJar` relocates
 * every string in the agent's own classes that starts with `kotlin`, so a rule that names a
 * Kotlin runtime class passes against the unshaded classes and fails in the jar an adopter runs.
 * The jar is loaded apart from the test classpath, under the platform class loader, so every
 * class here comes from the shaded copy. The fixture bytes are real kotlinc and javac output.
 */
class ShadedBodyKindRuleTest {
    private val loader =
        URLClassLoader(
            arrayOf(File(checkNotNull(System.getProperty("yukon.agent.shadedJar")) { "yukon.agent.shadedJar is not set" }).toURI().toURL()),
            ClassLoader.getPlatformClassLoader(),
        )

    @AfterTest
    fun close() = loader.close()

    /** A shaded Kotlin function interface whose `invoke` always answers [result]. */
    private fun shadedFunction(
        arity: Int,
        result: Any?,
    ): Any {
        val type = loader.loadClass("io.github.lukedevops.yukon.shaded.kotlin.jvm.functions.Function$arity")
        return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> result
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> "shadedFunction$arity"
            }
        }
    }

    /** The shaded analyser's body kind and source name for one fixture class. */
    private fun shadedBodyKind(path: String): Pair<String, String?> {
        val analyzerClass = loader.loadClass("io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer")
        val analyzer = analyzerClass.getField("INSTANCE").get(null)
        val analyze = analyzerClass.methods.single { it.name == "analyze" && it.parameterCount == 7 }
        val analysis =
            analyze.invoke(
                analyzer,
                File("build/classes/$path.class").readBytes(),
                shadedFunction(1, null),
                listOf("com.example.target"),
                emptyList<String>(),
                null,
                emptySet<String>(),
                shadedFunction(2, true),
            )
        val kind = analysis.javaClass.getMethod("getBodyKind").invoke(analysis) as Enum<*>
        return kind.name to analysis.javaClass.getMethod("getSourceName").invoke(analysis) as String?
    }

    @Test
    fun `the shaded analyser still knows the Kotlin runtime's lambda base classes`() {
        assertEquals("LAMBDA_CLASS" to null, shadedBodyKind("kotlin/test/com/example/target/BodyKindTarget\$serializableLambda\$1"))
        assertEquals("LAMBDA_CLASS" to null, shadedBodyKind("kotlin/test/com/example/target/BodyKindTarget\$suspendLambda\$1"))
        assertEquals("LAMBDA_CLASS" to null, shadedBodyKind("kotlin/test/com/example/target/BodyKindTarget\$restrictedSuspendLambda\$1"))
    }

    @Test
    fun `the shaded analyser still sees kotlin Metadata, so it tells an object expression from an anonymous class`() {
        assertEquals("OBJECT_EXPRESSION" to null, shadedBodyKind("kotlin/test/com/example/target/ObjectExpressionTarget\$makeHandler\$1"))
        assertEquals("ANONYMOUS_CLASS" to null, shadedBodyKind("java/test/com/example/target/BodyKindJavaTarget\$1"))
        assertEquals("LOCAL_CLASS" to "Local", shadedBodyKind("kotlin/test/com/example/target/BodyKindTarget\$localClass\$Local"))
    }
}
