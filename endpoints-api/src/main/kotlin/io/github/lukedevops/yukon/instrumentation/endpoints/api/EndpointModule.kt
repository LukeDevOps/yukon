package io.github.lukedevops.yukon.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher

/**
 * One framework's registration and dispatch hooks, discovered with [java.util.ServiceLoader].
 *
 * A per-framework subproject (Spring MVC, Ktor, JAX-RS, the JDK's own `HttpServer`) implements
 * this to declare which types carry that framework's routing, and which advice turns a match into
 * a call into the endpoint seam. See ADR 0017 for why endpoints are counted at a framework's own
 * dispatch point instead of inferred from the method tier.
 */
interface EndpointModule {
    /** The framework id carried on the wire, and the module name the endpoint seam reports failures under. */
    val name: String

    /**
     * Boot-layer JDK module names this module instruments classes of, for example `"jdk.httpserver"`.
     * Each one is given a read edge to the endpoint seam's own module at install time: a named
     * module cannot otherwise read the seam, which lives on the bootstrap loader's unnamed module.
     */
    val bootModulesNeedingSeamRead: Set<String> get() = emptySet()

    /** Which types this module instruments. */
    fun typeMatcher(): ElementMatcher<in TypeDescription>

    /**
     * Applies this module's advice to [builder] for [typeDescription].
     *
     * Advice is bound by class name through [advice], never referenced as a class literal: an
     * advice class compiled against the framework's own types does not resolve in the
     * classloader this module's own code runs in. See [AdviceBinder].
     */
    fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
    ): DynamicType.Builder<*>

    /**
     * Called with a framework object a module's advice handed to `YukonEndpoints.declare`, on the
     * agent's own loader; the module walks it, typically reflectively, and registers what it
     * finds through `YukonEndpoints.register`.
     *
     * A framework whose routes are only readable from a registration hook's own arguments never
     * needs this and keeps the default no-op body. It exists for a framework such as Spring's
     * functional routing, whose routes are declared as a `RouterFunction` object that reveals its
     * routes only to a visitor implemented in real code, something advice itself cannot define.
     */
    fun declare(frameworkObject: Any) {}
}
