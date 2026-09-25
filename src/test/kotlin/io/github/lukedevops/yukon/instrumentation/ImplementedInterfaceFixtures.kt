package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind

/**
 * The creation edges the ADR 0042 fixtures give each creating method, keyed by class and then by
 * method name. The manifest and the baseline tests both check against these.
 */
object ImplementedInterfaceFixtures {
    const val KOTLIN_CLASS = "com.example.target.ImplementedInterfaceTarget"
    const val JAVA_CLASS = "com.example.target.ImplementedInterfaceJavaTarget"

    /** Each fixture class with its creating methods and their `CREATES` edges. */
    val creationEdges: Map<String, Map<String, List<CallEdge>>> =
        mapOf(
            KOTLIN_CLASS to
                mapOf(
                    "register" to
                        listOf(
                            creates(
                                KOTLIN_CLASS,
                                "register\$lambda\$0",
                                "(Lcom/sun/net/httpserver/HttpExchange;)V",
                                "com.sun.net.httpserver.HttpHandler",
                            ),
                        ),
                    "startsThread" to
                        listOf(
                            creates(
                                KOTLIN_CLASS,
                                "startsThread\$lambda\$0",
                                "(Lcom/example/target/ImplementedInterfaceTarget;)V",
                                "java.lang.Runnable",
                                capturedCount = 1,
                            ),
                        ),
                    "funInterface" to listOf(creates(KOTLIN_CLASS, "funInterface\$lambda\$0", "(I)I", "com.example.target.IntStep")),
                    "functionType" to listOf(creates(KOTLIN_CLASS, "functionType\$lambda\$0", "(I)I", "kotlin.jvm.functions.Function1")),
                    "objectExpression" to
                        listOf(CallEdge("$KOTLIN_CLASS\$objectExpression\$1", "run", "()V", virtual = true, kind = CallEdgeKind.CREATES)),
                ),
            JAVA_CLASS to
                mapOf(
                    "lambda" to listOf(creates(JAVA_CLASS, "lambda\$lambda\$0", "()V", "java.lang.Runnable")),
                    "anonymous" to listOf(CallEdge("$JAVA_CLASS\$1", "run", "()V", virtual = true, kind = CallEdgeKind.CREATES)),
                    "twoInterfaces" to
                        listOf(
                            creates(JAVA_CLASS, "touch", "()I", "java.util.function.IntSupplier", virtual = true),
                            creates(JAVA_CLASS, "touch", "()I", "java.util.function.Supplier", virtual = true),
                        ),
                ),
        )

    private fun creates(
        className: String,
        methodName: String,
        descriptor: String,
        implementedInterface: String,
        capturedCount: Int = 0,
        virtual: Boolean = false,
    ) = CallEdge(className, methodName, descriptor, virtual, CallEdgeKind.CREATES, capturedCount, implementedInterface = implementedInterface)
}
