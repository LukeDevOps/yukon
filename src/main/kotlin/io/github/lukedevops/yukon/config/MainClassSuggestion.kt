package io.github.lukedevops.yukon.config

import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import java.lang.module.ModuleDescriptor
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * A value for `includePackages` the refusal can offer an adopter who set none: [prefix] is the
 * package of [mainClass], the application's main class. It is only ever suggested, never applied,
 * since the main class is not always the adopter's own code. See ADR 0033.
 */
internal data class MainClassSuggestion(
    val mainClass: String,
    val prefix: String,
) {
    companion object {
        /**
         * Launcher and framework packages whose main class says nothing about where the adopter's
         * code lives: every Spring Boot launcher, Ktor's `EngineMain` for each engine, and
         * Tomcat's `Bootstrap`. Pasting such a package into `includePackages` would instrument
         * none of the adopter's code, which is worse than no suggestion.
         */
        private val FRAMEWORK_PREFIXES = listOf("org.springframework.boot.loader", "io.ktor.server", "org.apache.catalina")

        private val ARCHIVE_SUFFIXES = listOf(".jar", ".war")

        /**
         * Finds the main class named by [command], the `sun.java.command` value, and suggests its
         * package. The first token of [command] is a jar or war launched with `-jar` (its
         * manifest's `Start-Class`, else its `Main-Class`, read through [readManifest]), a
         * `.java` source file (no main class), a module launch `module/class`, a module launched
         * by name alone (the main class its descriptor declares, looked up through
         * [findBootModule]), or a class name.
         *
         * Returns null when no main class can be found, when it is in the default package or
         * under one of [FRAMEWORK_PREFIXES], and on any failure at all, since this runs on the
         * agent's startup path and must never throw out of it.
         */
        fun fromCommand(
            command: String?,
            readManifest: (String) -> Manifest? = ::readJarManifest,
            findBootModule: (String) -> ModuleDescriptor? = ::bootModule,
        ): MainClassSuggestion? =
            try {
                mainClassOf(command, readManifest, findBootModule)?.let(::suggestFor)
            } catch (e: Exception) {
                null
            }

        /** The manifest of the jar at [path], or null when it cannot be opened or has none. */
        fun readJarManifest(path: String): Manifest? =
            try {
                JarFile(path).use { it.manifest }
            } catch (e: Exception) {
                null
            }

        /**
         * The descriptor of the boot layer's module named [name], or null when there is none. The
         * boot layer is built before any agent's `premain` runs, so this answers on the startup
         * path.
         */
        private fun bootModule(name: String): ModuleDescriptor? =
            ModuleLayer
                .boot()
                .findModule(name)
                .map { it.descriptor }
                .orElse(null)

        private fun mainClassOf(
            command: String?,
            readManifest: (String) -> Manifest?,
            findBootModule: (String) -> ModuleDescriptor?,
        ): String? {
            val token =
                command
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                ARCHIVE_SUFFIXES.any { token.endsWith(it, ignoreCase = true) } -> {
                    val attributes = readManifest(token)?.mainAttributes ?: return null
                    attributes.getValue("Start-Class") ?: attributes.getValue("Main-Class")
                }

                token.endsWith(".java") -> {
                    null
                }

                token.contains('/') -> {
                    token.substringAfterLast('/')
                }

                else -> {
                    // `java -m com.acme.shop` puts the bare module name here, which also reads as a
                    // class name; only the boot layer can tell the two apart.
                    val module = findBootModule(token)
                    if (module != null) module.mainClass().orElse(null) else token
                }
            }?.trim()
        }

        private fun suggestFor(mainClass: String): MainClassSuggestion? {
            if (!isBinaryClassName(mainClass)) return null
            if (FRAMEWORK_PREFIXES.any { TypeMatchPolicy.isUnderPrefix(mainClass, it) }) return null
            if (!mainClass.contains('.')) return null
            return MainClassSuggestion(mainClass, mainClass.substringBeforeLast('.'))
        }

        /**
         * Guards against a first token that is not a class name at all, such as `my-app-1.0.zip`
         * launched with `-jar`, so that nothing but a real package is suggested.
         */
        private fun isBinaryClassName(name: String): Boolean =
            name.split('.').all { segment ->
                segment.isNotEmpty() && Character.isJavaIdentifierStart(segment[0]) && segment.all(Character::isJavaIdentifierPart)
            }
    }
}

/** The ERROR [io.github.lukedevops.yukon.Agent] logs when it refuses to start for want of include rules. See ADR 0033. */
internal object IncludeRulesRefusal {
    /** The refusal, ending with [suggestion] stated as a fact about the main class when there is one. */
    fun message(suggestion: MainClassSuggestion?): String {
        val refusal =
            "yukon: includePackages is not set, so the agent is disabled for this JVM and nothing will be instrumented " +
                "or exported; set includePackages to your application's own package prefixes, ';'-separated"
        if (suggestion == null) return refusal
        return "$refusal; the main class is ${suggestion.mainClass}; includePackages=${suggestion.prefix} covers its " +
            "package, or name a broader prefix that also covers your shared libraries"
    }
}
