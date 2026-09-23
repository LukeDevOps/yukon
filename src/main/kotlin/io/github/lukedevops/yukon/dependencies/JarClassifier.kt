package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import io.github.lukedevops.yukon.registry.DependencyOrigin
import java.lang.System.Logger.Level
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * The ADR 0030 rules for whether a jar is a dependency, shared by the startup listing and by the
 * sweep when it meets a jar the listing never saw.
 *
 * A jar on its own (on the classpath, or opened through a `file:` URL) is never a dependency when
 * its manifest names an agent or marks it as a Spring Boot fat jar or executable war
 * ([kindOf]). A jar stored inside a fat jar is judged by the adopter's-own rule alone. The agent
 * rule exists because a `-javaagent` jar sits on the classpath; a jar under `BOOT-INF/lib` that
 * carries `Premain-Class`, such as `byte-buddy-agent`, is a library the application calls, not a
 * running agent, so it stays a dependency.
 *
 * A jar holding a class in the agent's own package ([TypeMatchPolicy.AGENT_PACKAGE_PREFIX]) is
 * never a dependency either, flat or nested. The agent-jar rule reads the manifest, and an unshaded
 * build of the agent carries no `Premain-Class`: the plain demo puts one on its `-cp` beside the
 * shaded agent jar, and it read as a dependency of the application it was measuring.
 *
 * A jar holding any class [TypeMatchPolicy.isIncluded] admits is the adopter's own.
 */
internal class JarClassifier(
    private val includes: List<String>,
    private val excludes: List<String>,
) {
    // Named after the lister, whose INFO line this is, so it reads the same wherever the rule ran.
    private val log = System.getLogger(StartupClasspathLister::class.java.name)

    /** What a jar's manifest says it is. */
    enum class Kind {
        /** Names `Premain-Class` or `Launcher-Agent-Class`: never a dependency. */
        AGENT,

        /** A Spring Boot fat jar or executable war: the application, never a dependency. */
        BOOT_APPLICATION,

        /** Anything else, judged by the adopter's-own rule. */
        ORDINARY,
    }

    fun kindOf(manifest: Manifest?): Kind {
        val attributes = manifest?.mainAttributes ?: Attributes()
        return when {
            AGENT_ATTRIBUTES.any { attributes.getValue(it) != null } -> Kind.AGENT
            BOOT_ATTRIBUTES.any { attributes.getValue(it) != null } -> Kind.BOOT_APPLICATION
            else -> Kind.ORDINARY
        }
    }

    /**
     * Judges a jar that stands on its own: null unless [kindOf] its manifest is [Kind.ORDINARY]
     * and it passes [classifyNested]. [fileName] is the jar's own name, used for the filename
     * identity; [location] is the display string the wire carries.
     */
    fun classifyFlat(
        contents: JarContents,
        fileName: String,
        location: String,
        origin: DependencyOrigin,
    ): ListedDependency? {
        if (kindOf(contents.manifest) != Kind.ORDINARY) return null
        return classifyNested(contents, fileName, location, location, origin)
    }

    /**
     * Judges a jar stored inside a fat jar: null when it is the agent's or the adopter's own, else the dependency
     * with its identity read. [displayName] names the jar in the INFO line the adopter's-own rule
     * may log.
     */
    fun classifyNested(
        contents: JarContents,
        fileName: String,
        location: String,
        displayName: String,
        origin: DependencyOrigin,
    ): ListedDependency? {
        if (contents.classNames.any { it.startsWith(TypeMatchPolicy.AGENT_PACKAGE_PREFIX) }) return null
        if (isAdoptersOwn(contents, displayName)) return null
        val identity = DependencyIdentityReader.identify(contents, fileName)
        return ListedDependency(identity.identities, identity.identitySource, location, identity.classCount, origin, contents.classNames)
    }

    /**
     * Whether [contents] holds a class the include rules admit, logging one INFO line when it also
     * holds classes they do not: the shaded single-jar shape, where bundled libraries are reported
     * as the adopter's own rather than as a dependency.
     */
    private fun isAdoptersOwn(
        contents: JarContents,
        displayName: String,
    ): Boolean {
        val outside = contents.classNames.count { !TypeMatchPolicy.isIncluded(it, includes, excludes) }
        if (outside == contents.classNames.size) return false
        if (outside > 0) {
            log.log(
                Level.INFO,
                "yukon: $displayName holds classes under the include rules, so it is treated as the adopter's own; " +
                    "its $outside classes outside the include rules are not reported as a dependency",
            )
        }
        return true
    }

    private companion object {
        val AGENT_ATTRIBUTES = listOf("Premain-Class", "Launcher-Agent-Class")
        val BOOT_ATTRIBUTES = listOf("Spring-Boot-Classpath-Index", "Spring-Boot-Lib")
    }
}
