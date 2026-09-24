package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.BranchOutcome
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.BranchSite as BranchSitePayload

/**
 * One outcome of a [KeptBranchSite]: its class-wide [branchIndex], its [role] in the site, the
 * [caseKey] of a [BranchRole.CASE] when the switch's case keys are known, and its [branchKey] when
 * [BranchKeys] can name it.
 */
data class KeptBranchOutcome(
    val branchIndex: Int,
    val role: BranchRole,
    val caseKey: Int?,
    val branchKey: String?,
)

/**
 * One kept branch site of a class, with its [outcomes] numbered and keyed. [siteKey] is null
 * exactly when every outcome's [KeptBranchOutcome.branchKey] is null. See ADRs 0025, 0031 and 0037.
 */
data class KeptBranchSite(
    val site: BranchSite,
    val siteKey: String?,
    val outcomes: List<KeptBranchOutcome>,
) {
    /** This site as the manifest and the static baseline send it. */
    fun toPayload(): BranchSitePayload =
        BranchSitePayload(
            siteIndex = site.siteIndex,
            siteKey = siteKey,
            line = site.line,
            outcomes = outcomes.map { BranchOutcome(it.branchIndex, it.role, it.caseKey) },
        )

    companion object {
        /**
         * The kept sites of [sites], one class's analysed branch sites in [BranchSite.siteIndex]
         * order, with each outcome numbered and keyed. [className] is the class's own name,
         * dotted.
         *
         * A site's first branch index is the sum of [BranchSite.outcomeCount] over every earlier
         * site in the class. Dropped sites count, so a kept site's branch indexes do not shift when
         * an earlier site is dropped (ADR 0025). A dropped site is not listed.
         */
        fun of(
            sites: List<BranchSite>,
            className: String,
        ): List<KeptBranchSite> {
            val branchKeys = BranchKeys.compute(sites, className)
            val siteKeys = BranchKeys.computeSiteKeys(sites, className)
            val kept = mutableListOf<KeptBranchSite>()
            var firstBranchIndex = 0
            for (site in sites) {
                if (site.dropReason == null) {
                    val outcomes =
                        rolesOf(site).mapIndexed { offset, (role, caseKey) ->
                            KeptBranchOutcome(firstBranchIndex + offset, role, caseKey, branchKeys[site.siteIndex to offset])
                        }
                    kept += KeptBranchSite(site, siteKeys[site.siteIndex], outcomes)
                }
                firstBranchIndex += site.outcomeCount
            }
            return kept
        }

        /**
         * Each outcome's role and case key, in offset order. [BranchProbeMethodVisitor] counts a
         * conditional's taken edge at offset 0 and its fall-through at offset 1. It counts a
         * switch's cases in [BranchSite.caseKeys] order and the default last. A switch whose case
         * keys are unknown or do not match its outcome count still lists its cases, with no case
         * key.
         */
        private fun rolesOf(site: BranchSite): List<Pair<BranchRole, Int?>> {
            val caseKeys = site.caseKeys
            if (!site.isSwitch && caseKeys == null) {
                return listOf(BranchRole.TAKEN to null, BranchRole.FALL_THROUGH to null)
            }
            val caseCount = site.outcomeCount - 1
            val knownKeys = caseKeys?.takeIf { it.size == caseCount }
            return List(caseCount) { BranchRole.CASE to knownKeys?.get(it) } + (BranchRole.DEFAULT to null)
        }
    }
}
