package io.github.lukedevops.yukon.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures the transform-time analysis: one call of
 * [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer.analyze] per class,
 * over every class in one [BenchmarkCorpus]. The score is the average time to analyse the whole
 * corpus once. Divide it by the class count that [load] prints to get the time per class.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
open class BranchSiteAnalyzerBenchmark {
    /** The corpus to analyse, by [BenchmarkCorpus.name]. */
    @Param("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")
    lateinit var corpus: String

    private lateinit var loaded: BenchmarkCorpus

    /** Reads the corpus once per trial, so file reads stay out of the score. */
    @Setup(Level.Trial)
    fun load() {
        loaded = BenchmarkCorpus.load(corpus)
        println()
        println("corpus $corpus:${loaded.classCount} classes, include packages ${loaded.includePackages}")
    }

    /** Analyses every class in the corpus once. */
    @Benchmark
    fun analyzeCorpus(blackhole: Blackhole) {
        loaded.analyzeAll { blackhole.consume(it) }
    }
}
