package io.github.lukedevops.yukon.export

/**
 * A destination for the two OTLP-style payloads. An implementation should
 * throw on failure. [ExportScheduler] treats a thrown exception as a
 * transient failure, and leaves the registry baseline where it is.
 */
interface Exporter {
    fun exportDeltaBatch(batch: DeltaBatch)

    fun exportManifest(manifest: ProbeManifest)

    fun exportStaticBaseline(baseline: StaticBaseline)
}
