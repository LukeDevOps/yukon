package io.github.lukedevops.yukon.benchmark

import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointModules
import java.io.File
import java.util.zip.ZipFile

/**
 * A named body of class files that [BranchSiteAnalyzerBenchmark] analyses. The build passes each
 * corpus in as system properties, and [load] reads it once.
 *
 * A corpus holds one or more class sets. A class set is one module's compiled output or one jar.
 * A class resolves other classes only inside its own class set, as it would through its own class
 * loader at transform time. The `scala` corpus needs this: its two modules declare classes with
 * the same names.
 */
class BenchmarkCorpus(
    val name: String,
    val classSets: List<ClassSet>,
) {
    /** The number of classes across every class set. */
    val classCount: Int get() = classSets.sumOf { it.classes.size }

    /** The fewest package prefixes that cover every class in the corpus. */
    val includePackages: List<String> =
        classSets
            .flatMap { it.classes.keys }
            .map { it.substringBeforeLast('/', "").replace('/', '.') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .fold(mutableListOf()) { kept, pkg ->
                if (kept.none { pkg == it || pkg.startsWith("$it.") }) kept += pkg
                kept
            }

    /**
     * Runs [BranchSiteAnalyzer.analyze] once over every class and hands each result to [consume].
     * The arguments follow `YukonInstrumentation.analyzeBytecode`, except that the method filter
     * accepts every method. Each class set gets a fresh table cache on every call, as each class
     * loader has its own cache at transform time.
     */
    fun analyzeAll(consume: (BranchSiteAnalyzer.Analysis) -> Unit) {
        for (classSet in classSets) {
            val tableCache = BranchSiteAnalyzer.CrossClassTableCache(TABLE_CACHE_ENTRIES)
            val lookup: (String) -> ByteArray? = classSet.classes::get
            for (bytes in classSet.classes.values) {
                consume(
                    BranchSiteAnalyzer.analyze(
                        bytes,
                        lookup,
                        includePackages,
                        emptyList(),
                        tableCache,
                        handlerInterfaces,
                    ) { _, _ -> true },
                )
            }
        }
    }

    companion object {
        /**
         * Each system property under this prefix names one class set, as
         * `yukon.benchmark.corpus.<corpus>.<class set>`. Its value is a path list of class
         * directories and jars.
         */
        const val PROPERTY_PREFIX = "yukon.benchmark.corpus."

        /** The size `YukonInstrumentation` gives each class loader's table cache. */
        private const val TABLE_CACHE_ENTRIES = 2048

        /** The handler interfaces the agent passes to the analyser, from the same endpoint modules. */
        private val handlerInterfaces: Set<String> by lazy {
            EndpointModules.discover().flatMapTo(sortedSetOf()) { it.handlerInterfaces }
        }

        /** Reads every class set of the corpus called [name]. Fails when the build passed none. */
        fun load(name: String): BenchmarkCorpus {
            val prefix = "$PROPERTY_PREFIX$name."
            val classSets =
                System
                    .getProperties()
                    .stringPropertyNames()
                    .filter { it.startsWith(prefix) }
                    .sorted()
                    .map { key -> ClassSet(key.removePrefix(prefix), readClasses(System.getProperty(key))) }
            check(classSets.isNotEmpty()) { "no system property starts with $prefix; run the benchmark through Gradle" }
            return BenchmarkCorpus(name, classSets)
        }

        private fun readClasses(pathList: String): Map<String, ByteArray> {
            val classes = sortedMapOf<String, ByteArray>()
            for (path in pathList.split(File.pathSeparator).filter { it.isNotEmpty() }) {
                val file = File(path)
                when {
                    file.isDirectory -> {
                        file
                            .walkTopDown()
                            .filter { it.isFile }
                            .forEach { entry ->
                                val relative = entry.relativeTo(file).invariantSeparatorsPath
                                if (isClassEntry(relative)) classes[relative.removeSuffix(".class")] = entry.readBytes()
                            }
                    }

                    file.isFile -> {
                        ZipFile(file).use { zip ->
                            for (entry in zip.entries()) {
                                if (!entry.isDirectory && isClassEntry(entry.name)) {
                                    classes[entry.name.removeSuffix(".class")] = zip.getInputStream(entry).use { it.readBytes() }
                                }
                            }
                        }
                    }

                    else -> {
                        error("corpus path $path does not exist")
                    }
                }
            }
            return classes
        }

        /** A class the JVM would define: not a module descriptor, and not a versioned copy under `META-INF`. */
        private fun isClassEntry(path: String): Boolean =
            path.endsWith(".class") && !path.startsWith("META-INF/") && !path.endsWith("module-info.class")
    }
}

/** One class set of a [BenchmarkCorpus]: class bytes by internal name, in name order. */
class ClassSet(
    val name: String,
    val classes: Map<String, ByteArray>,
)
