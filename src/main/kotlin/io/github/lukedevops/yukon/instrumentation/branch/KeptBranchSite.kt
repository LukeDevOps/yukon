package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.BranchOutcome
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.LineRange
import io.github.lukedevops.yukon.export.BranchSite as BranchSitePayload

/**
 * One outcome of a [KeptBranchSite]: its class-wide [branchIndex], its [role] in the site, the
 * [caseKey] of a [BranchRole.CASE] when the switch's case keys are known, and its [branchKey] when
 * [BranchKeys] can name it. [guardedLines] and [partlyGuardedLines] are its guarded code, from
 * [GuardAnalysis]. See ADR 0037.
 *
 * [caseLabel] is the source's label for a [BranchRole.CASE] of a rebuilt switch, and empty
 * otherwise. Such a case has no [caseKey]. See ADR 0038.
 */
data class KeptBranchOutcome(
    val branchIndex: Int,
    val role: BranchRole,
    val caseKey: Int?,
    val branchKey: String?,
    val guardedLines: List<LineRange> = emptyList(),
    val partlyGuardedLines: List<LineRange> = emptyList(),
    val caseLabel: List<ConditionPart> = emptyList(),
)

/**
 * One kept branch site of a class, with its [outcomes] numbered and keyed. [siteKey] is null
 * exactly when every outcome's [KeptBranchOutcome.branchKey] is null. [guard] is the branch index
 * of the innermost kept outcome that dominates the site, or null. See ADRs 0025, 0031 and 0037.
 */
data class KeptBranchSite(
    val site: BranchSite,
    val siteKey: String?,
    val outcomes: List<KeptBranchOutcome>,
    val guard: Int? = null,
) {
    /** This site as the manifest and the static baseline send it. */
    fun toPayload(): BranchSitePayload =
        BranchSitePayload(
            siteIndex = site.siteIndex,
            siteKey = siteKey,
            line = site.line,
            outcomes =
                outcomes.map {
                    BranchOutcome(
                        it.branchIndex,
                        it.role,
                        it.caseKey,
                        it.guardedLines,
                        it.partlyGuardedLines,
                        it.caseLabel,
                    )
                },
            guard = guard,
            condition = site.condition,
        )

    companion object {
        /**
         * The kept sites of [sites], one class's analysed branch sites in [BranchSite.siteIndex]
         * order, with each outcome numbered and keyed. [className] is the class's own name,
         * dotted. [guards] holds what [GuardAnalysis] found for each site, by site index. A site
         * it has no entry for has no guard and guards no lines.
         *
         * Each site's first branch index comes from [firstBranchIndexes]. A dropped site is not
         * listed, and neither is a throwing default (ADR 0038), whose branch index stays unused.
         */
        internal fun of(
            sites: List<BranchSite>,
            className: String,
            guards: Map<Int, SiteGuards> = emptyMap(),
        ): List<KeptBranchSite> {
            val branchKeys = BranchKeys.compute(sites, className)
            val siteKeys = BranchKeys.computeSiteKeys(sites, className)
            val firstBranchIndexes = firstBranchIndexes(sites)
            val kept = mutableListOf<KeptBranchSite>()
            for ((position, site) in sites.withIndex()) {
                if (site.dropReason != null) continue
                val siteGuards = guards[site.siteIndex]
                val outcomes =
                    rolesOf(site).mapIndexed { offset, role ->
                        KeptBranchOutcome(
                            firstBranchIndexes[position] + offset,
                            role.role,
                            role.caseKey,
                            branchKeys[site.siteIndex to offset],
                            siteGuards?.guardedLines?.getOrNull(offset).orEmpty(),
                            siteGuards?.partlyGuardedLines?.getOrNull(offset).orEmpty(),
                            role.caseLabel,
                        )
                    }
                kept += KeptBranchSite(site, siteKeys[site.siteIndex], outcomes, siteGuards?.guard)
            }
            return kept
        }

        /**
         * Each site's first branch index, in [sites] order: the sum of [BranchSite.outcomeCount]
         * over every earlier site in the class. Dropped sites count, so a kept site's branch
         * indexes do not shift when an earlier site is dropped (ADR 0025). [of] and
         * [GuardAnalysis] both number outcomes from this.
         */
        internal fun firstBranchIndexes(sites: List<BranchSite>): IntArray {
            val result = IntArray(sites.size)
            var next = 0
            for ((position, site) in sites.withIndex()) {
                result[position] = next
                next += site.outcomeCount
            }
            return result
        }

        /** One outcome's role, and for a case its key or its label. */
        private class Role(
            val role: BranchRole,
            val caseKey: Int? = null,
            val caseLabel: List<ConditionPart> = emptyList(),
        )

        /**
         * Each outcome's role and case key or label, in offset order. [BranchProbeMethodVisitor]
         * counts a conditional's taken edge at offset 0 and its fall-through at offset 1. It
         * counts a switch's cases in [BranchSite.caseKeys] order and the default last. A switch
         * whose case keys are unknown or do not match its outcome count still lists its cases, with
         * no case key. A rebuilt switch names each case by its label and no key, and leaves out a
         * throwing default, which is always the last offset.
         */
        private fun rolesOf(site: BranchSite): List<Role> {
            val caseKeys = site.caseKeys
            if (!site.isSwitch && caseKeys == null) return listOf(Role(BranchRole.TAKEN), Role(BranchRole.FALL_THROUGH))
            val caseCount = site.outcomeCount - 1
            val labels = site.caseLabels?.takeIf { it.size == caseCount }
            val knownKeys = caseKeys?.takeIf { it.size == caseCount && labels == null }
            val cases = List(caseCount) { Role(BranchRole.CASE, knownKeys?.get(it), listOfNotNull(labels?.get(it))) }
            return if (site.throwingDefault) cases else cases + Role(BranchRole.DEFAULT)
        }
    }
}
