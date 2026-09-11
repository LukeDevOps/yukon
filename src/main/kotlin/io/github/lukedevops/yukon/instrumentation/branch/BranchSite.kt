package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One tracked conditional jump found in a class. [siteIndex] is assigned once per class, in
 * bytecode encounter order across all its methods, so [BranchSiteAnalyzer] and
 * [BranchProbeAsmVisitorWrapper] agree on it independently: both walk the same method set in
 * the same order and apply the same [ConditionalJump] filter, without needing to share state.
 */
data class BranchSite(
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val siteIndex: Int,
)
