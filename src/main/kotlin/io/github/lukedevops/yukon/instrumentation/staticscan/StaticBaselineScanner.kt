package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.StaticallyUnsafeClass
import io.github.lukedevops.yukon.export.UnprobedClass
import io.github.lukedevops.yukon.export.UnreadableClass
import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.io.File
import java.io.IOException
import java.lang.System.Logger.Level
import java.util.jar.JarFile

data class StaticScanResult(
    val declaredClasses: List<DeclaredClass>,
    val staticallyUnsafeClasses: List<StaticallyUnsafeClass>,
    val unreadableClasses: List<UnreadableClass>,
    val unprobedClasses: List<UnprobedClass> = emptyList(),
) {
    /** Every class name the scan saw, in any bucket. */
    fun allClassNames(): Set<String> =
        buildSet {
            declaredClasses.mapTo(this) { it.className }
            staticallyUnsafeClasses.mapTo(this) { it.className }
            unreadableClasses.mapTo(this) { it.className }
            unprobedClasses.mapTo(this) { it.className }
        }
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
        val declared = mutableListOf<DeclaredClass>()
        val unsafe = mutableListOf<StaticallyUnsafeClass>()
        val unreadable = mutableListOf<UnreadableClass>()
        val unprobed = mutableListOf<UnprobedClass>()

        fun toResult() = StaticScanResult(declared, unsafe, unreadable, unprobed)
    }

    fun scan(classpathRoots: List<File> = defaultClasspathRoots()): StaticScanResult {
        val buckets = Buckets()
        for (root in classpathRoots) {
            try {
                scanRoot(root, buckets)
            } catch (e: Exception) {
                log.log(Level.WARNING, "yukon: could not scan classpath entry $root for the static baseline", e)
            }
        }
        return buckets.toResult()
    }

    private fun scanRoot(
        root: File,
        buckets: Buckets,
    ) {
        if (!root.exists()) return
        if (root.isDirectory) {
            val locator = withSupportingTypesFallback(ClassFileLocator.ForFolder(root))
            val pool = TypePool.Default.of(locator)
            candidateClassNamesInFolder(root).forEach { className -> classify(className, pool, locator, buckets) }
            return
        }
        if (root.extension != "jar") return
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
        val flatPool = TypePool.Default.of(flatLocator)
        val nestedLocators =
            NESTED_CLASSES_PREFIXES.associateWith { prefix ->
                withSupportingTypesFallback(PrefixedJarClassFileLocator(jarFile, prefix))
            }
        val nestedPools = nestedLocators.mapValues { (_, locator) -> TypePool.Default.of(locator) }
        val entries = jarFile.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
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
            val className = relativeName.removeSuffix(".class").replace('/', '.')
            classify(className, pool, locator, buckets)
        }
    }

    private fun candidateClassNamesInFolder(root: File): List<String> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
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
            val methods = declaredMethodsOf(typeDescription, className, locator)
            if (methods.isEmpty()) {
                buckets.unprobed += UnprobedClass(className, "no concrete methods to probe")
                return
            }
            buckets.declared += DeclaredClass(className, methods)
        } catch (e: Exception) {
            // Covers a corrupt class file, or a failure resolving a supporting type (e.g. an
            // annotation's own definition) while describing this one. Either way, this class
            // could not be safely classified, so it is reported rather than silently dropped.
            buckets.unreadable += UnreadableClass(className, e.message ?: e.toString())
        }
    }

    /**
     * Reads [className]'s bytes through [locator] to detect inline functions with the same
     * LocalVariableTable rule [BranchSiteAnalyzer] uses at transform time, and merges the result
     * into each method it declares.
     *
     * A class whose bytes cannot be resolved here is not itself unreadable: its [TypeDescription]
     * already resolved successfully through [pool][TypePool], so it is still declared, just with
     * every method's [DeclaredMethod.inline] left false. This can only happen if the two disagree
     * about what is readable, which does not happen for any locator this scanner builds today.
     */
    private fun declaredMethodsOf(
        typeDescription: TypeDescription,
        className: String,
        locator: ClassFileLocator,
    ): List<DeclaredMethod> {
        val methods = typeDescription.declaredMethods.filter(TypeMatchPolicy.methodMatcher())
        if (methods.isEmpty()) return emptyList()
        val eligible = methods.map { it.internalName to it.descriptor }.toSet()
        val analysis =
            try {
                val resolution = locator.locate(className)
                if (resolution.isResolved) {
                    BranchSiteAnalyzer.analyze(resolution.resolve()) { name, descriptor -> (name to descriptor) in eligible }
                } else {
                    BranchSiteAnalyzer.Analysis.EMPTY
                }
            } catch (_: IOException) {
                BranchSiteAnalyzer.Analysis.EMPTY
            }
        return methods.map { DeclaredMethod(it.internalName, it.descriptor, analysis.isInline(it.internalName, it.descriptor)) }
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
     */
    private fun withSupportingTypesFallback(locator: ClassFileLocator): ClassFileLocator =
        ClassFileLocator.Compound(locator, supportingTypesLocator)

    private companion object {
        val NESTED_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
        val NESTED_JAR_PREFIXES = listOf("BOOT-INF/lib/", "WEB-INF/lib/")

        fun defaultClasspathRoots(): List<File> =
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { File(it) }
    }
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
