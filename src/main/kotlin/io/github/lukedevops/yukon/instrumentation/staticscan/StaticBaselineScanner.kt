package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.StaticallyUnsafeClass
import io.github.lukedevops.yukon.export.UnprobedClass
import io.github.lukedevops.yukon.export.UnreadableClass
import io.github.lukedevops.yukon.instrumentation.ScalaClassDetector
import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.io.File
import java.io.IOException
import java.lang.System.Logger.Level
import java.util.jar.JarFile

/**
 * What one static baseline scan found. [ownClassNames] is every class name the scan saw in a
 * directory root or under `BOOT-INF/classes`/`WEB-INF/classes`, in scope or not: the adopter's own
 * code. [flatJarClassNames] is every class name it saw at the root of a jar, which may be the
 * adopter's own jar or a dependency on a flat classpath. Both are kept only to filter the scan's
 * references and are never sent.
 */
data class StaticScanResult(
    val declaredClasses: List<DeclaredClass>,
    val staticallyUnsafeClasses: List<StaticallyUnsafeClass>,
    val unreadableClasses: List<UnreadableClass>,
    val unprobedClasses: List<UnprobedClass> = emptyList(),
    val ownClassNames: Set<String> = emptySet(),
    val flatJarClassNames: Set<String> = emptySet(),
) {
    /** Every class name the scan saw, in any bucket. */
    fun allClassNames(): Set<String> =
        buildSet {
            declaredClasses.mapTo(this) { it.className }
            staticallyUnsafeClasses.mapTo(this) { it.className }
            unreadableClasses.mapTo(this) { it.className }
            unprobedClasses.mapTo(this) { it.className }
        }

    /** This result with every method's and every class's reference list emptied. */
    fun withoutReferences(): StaticScanResult =
        copy(
            declaredClasses =
                declaredClasses.map { declared ->
                    declared.copy(
                        methods = declared.methods.map { it.copy(referencedClasses = emptyList()) },
                        referencedClasses = emptyList(),
                    )
                },
        )
}

/**
 * Builds a load-independent inventory of what exists on the classpath under
 * [instrumentedPackagePrefixes], by reading bytecode directly instead of waiting for the JVM to
 * load it. See "Static baseline" in this project's `CLAUDE.md` for the full design.
 *
 * Applies the exact same [TypeMatchPolicy] a loaded class would be matched against, so a class
 * this scanner declares as dead-code-eligible is one the reactive tier would also have
 * instrumented, had it loaded. A class the reactive tier would match but never register, because
 * it has no concrete method to probe, lands in [StaticScanResult.unprobedClasses] rather than
 * being declared: declaring it would invite a collector to report it "never loaded" when the
 * agent simply had nothing to say about it.
 *
 * [excludedPackagePrefixes] is threaded through to [TypeMatchPolicy] the same way
 * [instrumentedPackagePrefixes] is, so a class excluded from the reactive tier never lands in any
 * bucket of the baseline either.
 *
 * [supportingTypesLocator] resolves types a scanned class refers to but its own root does not
 * contain, such as an annotation's own class; see [withSupportingTypesFallback].
 */
class StaticBaselineScanner(
    private val instrumentedPackagePrefixes: List<String>,
    private val excludedPackagePrefixes: List<String> = emptyList(),
    private val supportingTypesLocator: ClassFileLocator = ClassFileLocator.ForClassLoader.ofSystemLoader(),
) {
    private val log = System.getLogger(StaticBaselineScanner::class.java.name)
    private val typeNameMatcher = TypeMatchPolicy.typeNameMatcher(instrumentedPackagePrefixes, excludedPackagePrefixes)

    private class Buckets {
        /** One parsed-table cache per scan: a class many others reference is parsed once, not once per referencing class. */
        val tableCache = BranchSiteAnalyzer.CrossClassTableCache(SCAN_TABLE_CACHE_ENTRIES)
        val declared = mutableListOf<DeclaredClass>()
        val unsafe = mutableListOf<StaticallyUnsafeClass>()
        val unreadable = mutableListOf<UnreadableClass>()
        val unprobed = mutableListOf<UnprobedClass>()
        val ownClassNames = HashSet<String>()
        val flatJarClassNames = HashSet<String>()

        fun toResult() = StaticScanResult(declared, unsafe, unreadable, unprobed, ownClassNames, flatJarClassNames)
    }

    /**
     * Scans [classpathRoots] plus every jar they reach through a manifest `Class-Path` attribute.
     * A `java -jar app.jar` launch puts only `app.jar` on `java.class.path`; the dependencies the
     * launcher actually loads are named in its manifest, relative to the jar's own directory, and
     * can name further jars with manifests of their own. Without following them, an adopter's
     * code in one of those jars would never be declared.
     */
    fun scan(classpathRoots: List<File> = defaultClasspathRoots()): StaticScanResult {
        val buckets = Buckets()
        for (root in withManifestClassPath(classpathRoots)) {
            try {
                scanRoot(root, buckets)
            } catch (e: Exception) {
                log.log(Level.WARNING, "yukon: could not scan classpath entry $root for the static baseline", e)
            }
        }
        return buckets.toResult()
    }

    /**
     * [roots] followed by every jar reachable through manifest `Class-Path` entries, in
     * discovery order, each file once. An entry is resolved the way the JDK's launcher resolves
     * it, as a relative path against the referencing jar's parent directory; an entry that does
     * not exist is dropped, and a jar whose manifest cannot be read contributes nothing beyond
     * itself.
     */
    private fun withManifestClassPath(roots: List<File>): List<File> {
        val ordered = LinkedHashMap<File, File>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val root = queue.removeFirst()
            val canonical = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
            if (ordered.putIfAbsent(canonical, root) != null) continue
            if (!isJarFile(root) || !root.isFile) continue
            for (entry in manifestClassPathEntries(root)) {
                val referenced = File(root.absoluteFile.parentFile, entry)
                if (referenced.exists()) queue.addLast(referenced)
            }
        }
        return ordered.values.toList()
    }

    private fun manifestClassPathEntries(jar: File): List<String> =
        try {
            JarFile(jar).use { jarFile ->
                jarFile.manifest
                    ?.mainAttributes
                    ?.getValue("Class-Path")
                    .orEmpty()
                    .split(' ')
                    .filter { it.isNotBlank() }
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: could not read the manifest of $jar for Class-Path entries", e)
            emptyList()
        }

    /** A jar or zip, by extension, case-insensitively: the launcher accepts `Foo.JAR` and `lib.zip` alike. */
    private fun isJarFile(root: File): Boolean = root.extension.lowercase() in JAR_EXTENSIONS

    private fun scanRoot(
        root: File,
        buckets: Buckets,
    ) {
        if (!root.exists()) return
        if (root.isDirectory) {
            val locator = withSupportingTypesFallback(ClassFileLocator.ForFolder(root))
            val pool = TypePool.Default.WithLazyResolution.of(locator)
            candidateClassNamesInFolder(root).forEach { className ->
                buckets.ownClassNames += className
                classify(className, pool, locator, buckets)
            }
            return
        }
        if (!isJarFile(root)) return
        JarFile(root).use { jarFile -> scanJar(jarFile, buckets) }
    }

    /**
     * A flat jar (this project's own shaded jar shape) has classes at its own root. Spring
     * Boot's executable jar and a WAR nest the deployed application's own code one level in,
     * under `BOOT-INF/classes`/`WEB-INF/classes` respectively; each dependency there is its own
     * jar-inside-a-jar under `BOOT-INF/lib`/`WEB-INF/lib`, deliberately not opened here.
     */
    private fun scanJar(
        jarFile: JarFile,
        buckets: Buckets,
    ) {
        val flatLocator = withSupportingTypesFallback(ClassFileLocator.ForJarFile(jarFile))
        val flatPool = TypePool.Default.WithLazyResolution.of(flatLocator)
        val nestedLocators =
            NESTED_CLASSES_PREFIXES.associateWith { prefix ->
                withSupportingTypesFallback(PrefixedJarClassFileLocator(jarFile, prefix))
            }
        val nestedPools = nestedLocators.mapValues { (_, locator) -> TypePool.Default.WithLazyResolution.of(locator) }
        val entries = jarFile.entries().asSequence().filter { !it.isDirectory && isClassEntry(it.name) }
        for (entry in entries) {
            val nestedPrefix = NESTED_CLASSES_PREFIXES.firstOrNull { entry.name.startsWith(it) }
            val (relativeName, pool, locator) =
                when {
                    nestedPrefix != null -> {
                        Triple(
                            entry.name.removePrefix(nestedPrefix),
                            nestedPools.getValue(nestedPrefix),
                            nestedLocators.getValue(nestedPrefix),
                        )
                    }

                    NESTED_JAR_PREFIXES.any { entry.name.startsWith(it) } -> {
                        continue
                    }

                    else -> {
                        Triple(entry.name, flatPool, flatLocator)
                    }
                }
            // Checked again on the name inside the nested root, not only on the whole entry name:
            // a multi-release jar packed under BOOT-INF/classes keeps its versioned variants at
            // BOOT-INF/classes/META-INF/versions/N/, which only the stripped name reveals.
            if (!isClassEntry(relativeName)) continue
            val className = relativeName.removeSuffix(".class").replace('/', '.')
            if (nestedPrefix != null) buckets.ownClassNames += className else buckets.flatJarClassNames += className
            classify(className, pool, locator, buckets)
        }
    }

    private fun candidateClassNamesInFolder(root: File): List<String> =
        root
            .walkTopDown()
            .filter { it.isFile && isClassEntry(it.relativeTo(root).invariantSeparatorsPath) }
            .map {
                it
                    .relativeTo(root)
                    .path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }.toList()

    private fun classify(
        className: String,
        pool: TypePool,
        locator: ClassFileLocator,
        buckets: Buckets,
    ) {
        if (!looksInScope(className)) return
        try {
            val resolution = pool.describe(className)
            if (!resolution.isResolved) {
                buckets.unreadable += UnreadableClass(className, "class file could not be resolved")
                return
            }
            val typeDescription = resolution.resolve()
            if (!typeNameMatcher.matches(typeDescription)) return
            val unsafeAnnotation = TypeMatchPolicy.unsafeAnnotation(typeDescription)
            if (unsafeAnnotation != null) {
                buckets.unsafe +=
                    StaticallyUnsafeClass(
                        className,
                        "@${unsafeAnnotation.annotationType.name} is not a legal annotation on a class per its own @Target",
                    )
                return
            }
            val scanned = declaredMethodsOf(typeDescription, className, locator, buckets.tableCache)
            if (scanned.methods.isEmpty()) {
                buckets.unprobed += UnprobedClass(className, "no concrete methods to probe")
                return
            }
            buckets.declared +=
                DeclaredClass(
                    className,
                    scanned.methods,
                    scanned.superClassName,
                    scanned.interfaceNames,
                    scanned.classReferences,
                    scanned.sourceFile,
                )
        } catch (e: Exception) {
            // Covers a corrupt class file, or a failure resolving a supporting type (e.g. an
            // annotation's own definition) while describing this one. Either way, this class
            // could not be safely classified, so it is reported rather than silently dropped.
            buckets.unreadable += UnreadableClass(className, e.message ?: e.toString())
        }
    }

    /** [DeclaredMethod]s for one class, plus the supertypes, class-level references and source file read from the same analysis pass. */
    private class ScannedMethods(
        val methods: List<DeclaredMethod>,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val classReferences: List<String>,
        val sourceFile: String?,
    )

    /**
     * Reads [className]'s bytes through [locator] once, ahead of filtering: whether a synthetic
     * method is a probed lambda body depends on whether the class carries a `Scala`/`ScalaSig`
     * attribute ([ScalaClassDetector]), and the same bytes are also used to detect inline
     * functions with the same LocalVariableTable rule [BranchSiteAnalyzer] uses at transform time,
     * merged into each declared method, to detect a `<clinit>` of the class's own, to read its
     * in-scope call edges, its out-of-scope references and its lambda bodies, and to read its
     * superclass, interfaces and source file. References are listed as the analyser records them, JDK names included;
     * [BaselineReferenceFilter] drops those before the baseline is sent.
     *
     * A class whose bytes cannot be resolved here is not itself unreadable: its [TypeDescription]
     * already resolved successfully through [pool][TypePool], so it is still declared, just
     * treated as a non-Scala class, with every method's [DeclaredMethod.inline] and
     * [DeclaredMethod.calls] and references left empty, no method marked as a lambda body,
     * [DeclaredClass.superClassName] and [DeclaredClass.sourceFile] left null, no `<clinit>` entry
     * added, and [DeclaredClass.interfaceNames] left empty. This can only happen
     * if the two disagree about what is readable, which no locator this scanner builds does.
     *
     * A `<clinit>` entry is appended after every other declared method, mirroring
     * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation]'s own placement of the
     * type initializer's probe after every other slot category. This can make the returned list
     * non-empty even when [TypeMatchPolicy.methodMatcher] admits none of the type's own methods,
     * so a class whose only probe-worthy content is its own static initializer is still declared
     * rather than reported as unprobed.
     */
    private fun declaredMethodsOf(
        typeDescription: TypeDescription,
        className: String,
        locator: ClassFileLocator,
        tableCache: BranchSiteAnalyzer.CrossClassTableCache,
    ): ScannedMethods {
        val classBytes =
            try {
                val resolution = locator.locate(className)
                if (resolution.isResolved) resolution.resolve() else null
            } catch (_: IOException) {
                null
            }
        val isScalaClass = classBytes?.let(ScalaClassDetector::isScalaClass) ?: false
        val methods = typeDescription.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass))
        val eligible = methods.map { it.internalName to it.descriptor }.toSet()
        val analysis =
            if (classBytes != null) {
                BranchSiteAnalyzer.analyze(
                    classBytes,
                    crossClassLookup(locator),
                    instrumentedPackagePrefixes,
                    excludedPackagePrefixes,
                    tableCache,
                ) { name, descriptor -> (name to descriptor) in eligible }
            } else {
                BranchSiteAnalyzer.Analysis.EMPTY
            }
        val declaredMethods =
            methods.map {
                DeclaredMethod(
                    it.internalName,
                    it.descriptor,
                    analysis.isInline(it.internalName, it.descriptor),
                    analysis.callsOf(it.internalName, it.descriptor),
                    analysis.generatedBy(it.internalName, it.descriptor),
                    analysis.referencesOf(it.internalName, it.descriptor),
                    analysis.isLambdaBody(it.internalName, it.descriptor),
                )
            }
        val typeInitializer =
            if (analysis.hasTypeInitializer) {
                listOf(
                    DeclaredMethod(
                        "<clinit>",
                        "()V",
                        calls = analysis.callsOf("<clinit>", "()V"),
                        referencedClasses = analysis.referencesOf("<clinit>", "()V"),
                    ),
                )
            } else {
                emptyList()
            }
        return ScannedMethods(
            declaredMethods + typeInitializer,
            analysis.superClassName,
            analysis.interfaceNames,
            analysis.classReferences,
            analysis.sourceFile,
        )
    }

    /**
     * Resolves another class's bytes by internal name through [locator], the same locator
     * [declaredMethodsOf] reads the scanned class's own bytes through, supporting-types fallback
     * included. [BranchSiteAnalyzer] uses this to resolve a cross-class Kotlin `$default` pass-
     * through to its real target, the same mechanism
     * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] uses at transform time; a
     * baseline edge and a manifest edge must agree on the target, not one naming `$default` and
     * the other naming the function it fills in for. Any failure, including a class the locator
     * cannot find, is swallowed and reported as an unresolved pass-through rather than as a scan
     * failure.
     */
    private fun crossClassLookup(locator: ClassFileLocator): (String) -> ByteArray? =
        { internalName ->
            try {
                val resolution = locator.locate(internalName.replace('/', '.'))
                if (resolution.isResolved) resolution.resolve() else null
            } catch (_: Exception) {
                null
            }
        }

    /** Cheap, string-only pre-filter, applied before resolving a [TypeDescription] at all. */
    private fun looksInScope(className: String): Boolean =
        TypeMatchPolicy.isIncluded(className, instrumentedPackagePrefixes, excludedPackagePrefixes)

    /**
     * A root's own locator only has the bytes for classes physically inside that root. Resolving
     * a type can still need a supporting type's own bytecode. Checking whether a declared
     * annotation's `@Target` permits [java.lang.annotation.ElementType.TYPE] needs the
     * annotation's own class, which usually lives in a library jar elsewhere on the classpath, not
     * in the root of the class that uses it. Falling back to [supportingTypesLocator] (the system
     * classloader by default) resolves that without ever loading the type actually being scanned:
     * [ClassFileLocator.ForClassLoader] reads bytecode as a classloader resource, the same as any
     * other locator here, rather than calling `Class.forName`.
     *
     * When even the fallback cannot find an annotation's type, ByteBuddy's type pool drops that
     * annotation from the class's declared annotations rather than failing, so the class is judged
     * safe and declared. That is the right outcome: a skipped class always shows up in the
     * manifest's skipped list when it loads, so declaring it here can never produce a false
     * "never loaded". What is lost is only the "statically unsafe" label. This is the situation
     * inside a Spring Boot fat jar, where the system loader sees `BOOT-INF/classes` but not the
     * annotation types packed under `BOOT-INF/lib`.
     *
     * Every pool built over such a locator resolves lazily: a type it cannot find still answers
     * to its name, and only fails when a member or annotation is asked for. The type matcher's
     * continuation-class check reads a superclass's name alone, so a class whose superclass sits
     * in a dependency jar this scan never opens (the Kotlin stdlib under `BOOT-INF/lib`) is still
     * classified correctly. The agent's own transform path already uses ByteBuddy's default lazy
     * pool strategy.
     */
    private fun withSupportingTypesFallback(locator: ClassFileLocator): ClassFileLocator =
        ClassFileLocator.Compound(locator, supportingTypesLocator)

    private companion object {
        val NESTED_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
        val NESTED_JAR_PREFIXES = listOf("BOOT-INF/lib/", "WEB-INF/lib/")
        val JAR_EXTENSIONS = setOf("jar", "zip")

        /**
         * Tables held at once during a scan. Every in-scope class another one references is parsed
         * once while it stays within this many most recently used; a scan larger than this parses
         * the least recently referenced again rather than holding a whole classpath's tables.
         */
        const val SCAN_TABLE_CACHE_ENTRIES = 8192

        fun defaultClasspathRoots(): List<File> =
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { File(it) }
    }
}

/**
 * Whether a path inside a classpath root names a class. A `.class` file under `META-INF/` is never
 * one: a multi-release jar keeps its per-JDK variants under `META-INF/versions/N/`, and the JVM
 * loads those under the same name as the base entry, so counting the entry by its path would
 * invent a second, phantom class named `META-INF.versions.9.com.acme.Foo`. A class present only in
 * a versioned directory is therefore not counted at all, which can only lose a class, never invent
 * one. `module-info` and `package-info` carry no methods and are not types an adopter's code refers
 * to, so they are left out too.
 *
 * Shared by the static baseline scan and the dependency listing, so both count a jar's classes by
 * one rule.
 */
internal fun isClassEntry(path: String): Boolean {
    if (!path.endsWith(".class") || path.startsWith("META-INF/")) return false
    val simpleName = path.substringAfterLast('/').removeSuffix(".class")
    return simpleName != "module-info" && simpleName != "package-info"
}

/** Reads a class's bytes from a fixed prefix inside [jarFile], for nested classpath roots such as `BOOT-INF/classes/`. */
private class PrefixedJarClassFileLocator(
    private val jarFile: JarFile,
    private val prefix: String,
) : ClassFileLocator {
    override fun locate(typeName: String): ClassFileLocator.Resolution {
        val entry =
            jarFile.getJarEntry(prefix + typeName.replace('.', '/') + ".class") ?: return ClassFileLocator.Resolution.Illegal(typeName)
        val bytes = jarFile.getInputStream(entry).use { it.readBytes() }
        return ClassFileLocator.Resolution.Explicit(bytes)
    }

    override fun close() {
        // jarFile's lifecycle is owned by the caller, which may share it across multiple prefixed locators.
    }
}
