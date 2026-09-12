package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One tracked branch point found in a class: either a two-outcome [ConditionalJump] or a
 * `TABLESWITCH`/`LOOKUPSWITCH`.
 *
 * [siteIndex] is assigned once per class, in bytecode encounter order across all its methods.
 * [BranchSiteAnalyzer] and [BranchProbeAsmVisitorWrapper] each assign it independently, with no
 * shared state, because both walk the same method set in the same order and apply the same
 * tracking rules.
 *
 * [outcomeCount] is the number of distinct probe slots this site owns. A conditional jump owns 2
 * (taken, not-taken). A switch owns the case count plus one slot for the default.
 */
data class BranchSite(
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val siteIndex: Int,
    val outcomeCount: Int = 2,
)
