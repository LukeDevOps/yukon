package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One tracked branch point found in a class: either a two-outcome [ConditionalJump] or a
 * `TABLESWITCH`/`LOOKUPSWITCH`. [siteIndex] is assigned once per class, in bytecode encounter
 * order across all its methods, so [BranchSiteAnalyzer] and [BranchProbeAsmVisitorWrapper] agree
 * on it independently: both walk the same method set in the same order and apply the same
 * tracking rules, without needing to share state.
 *
 * [outcomeCount] is the number of distinct probe slots this site owns: 2 for a conditional jump
 * (taken, not-taken), or the case count plus one for a switch (one slot per case, plus the
 * default).
 */
data class BranchSite(
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val siteIndex: Int,
    val outcomeCount: Int = 2,
)
