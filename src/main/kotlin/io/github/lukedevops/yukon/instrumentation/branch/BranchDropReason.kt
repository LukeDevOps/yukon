package io.github.lukedevops.yukon.instrumentation.branch

/**
 * Why a tracked branch site got no probe. A dropped site still consumes its
 * [BranchSite.siteIndex] and its outcome positions in [io.github.lukedevops.yukon.registry.ProbeMeta.branchIndex]
 * numbering; only its probe slot is left out. See ADR 0025.
 */
enum class BranchDropReason {
    /**
     * The site is a copy of a Kotlin inline function's body, planted by kotlinc's inliner at the
     * call site, whose origin class is outside `includePackages`/`excludePackages`.
     */
    INLINED_OUT_OF_SCOPE,

    /**
     * The site is part of the state machine kotlinc weaves into a suspend function or suspend
     * lambda: the switch on the continuation's `label`, a compare against the suspended marker,
     * or the preamble's re-entry tests. See ADR 0025.
     */
    COROUTINE_MACHINERY,
}
