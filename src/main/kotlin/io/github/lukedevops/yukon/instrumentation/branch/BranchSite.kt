package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.ConditionPart

/**
 * One tracked branch point found in a class: either a two-outcome [ConditionalJump] or a
 * `TABLESWITCH`/`LOOKUPSWITCH`.
 *
 * [siteIndex] is assigned once per class, in bytecode encounter order across all its methods.
 * [BranchSiteAnalyzer] and [BranchProbeAsmVisitorWrapper] each assign it independently, with no
 * shared state, because both walk the same method set in the same order and apply the same
 * tracking rules.
 *
 * [outcomeCount] is the number of outcomes this site numbers. A conditional jump has 2 (taken,
 * not-taken). A switch has the case count plus one for the default.
 *
 * [dropReason] is set when the site gets no probe at all; see [BranchDropReason] and ADR 0025.
 * [line] and [inlinedFromClassName] describe an inlined copy: a site inside code kotlinc copied
 * from an inline function's body into this site's method. [inlinedFromClassName] is null for the
 * class's own code, and set to the origin class, dotted, otherwise; [line] is the origin's own
 * source line for a kept copy, and this method's own line otherwise. A dropped copy from another
 * class carries no origin, since nothing about it reaches the manifest.
 *
 * [conditionFingerprint] is the site's canonical fingerprint text, built by [ConditionFingerprinter]
 * from the instructions between the last point the operand stack was empty and the site's own jump
 * or switch. It is null when the site cannot be fingerprinted with confidence. See ADR 0031.
 *
 * [caseKeys] is set only for a switch: one entry per case outcome, in the exact order
 * [BranchProbeMethodVisitor] numbers case outcomes, so `caseKeys.size + 1 == outcomeCount` holds.
 * Null for a conditional, and null for a switch in a method [ConditionFingerprinter] could not
 * read.
 *
 * [isSwitch] is true for a `TABLESWITCH` or `LOOKUPSWITCH`, and false for a conditional jump. A
 * switch with one case also has two outcomes, so [outcomeCount] alone cannot tell the two apart.
 *
 * [condition] is the expression the site tests, written by [ConditionWriter] from the same window
 * as [conditionFingerprint]. It is empty for a dropped site and for a site the writer could not
 * write. See ADR 0037.
 *
 * [caseLabels] is set only for a switch [SwitchLowering] rebuilt from a string or enum lowering:
 * one label per case outcome, in the same order as [caseKeys], each the constant, literal or type
 * the source names. [throwingDefault] is true when that switch's default only throws an exception
 * the compiler added. The default then keeps its branch index but gets no probe, and the site does
 * not list it. See ADR 0038.
 */
data class BranchSite(
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val siteIndex: Int,
    val outcomeCount: Int = 2,
    val dropReason: BranchDropReason? = null,
    val inlinedFromClassName: String? = null,
    val conditionFingerprint: String? = null,
    val caseKeys: List<Int>? = null,
    val isSwitch: Boolean = false,
    val condition: List<ConditionPart> = emptyList(),
    val caseLabels: List<ConditionPart>? = null,
    val throwingDefault: Boolean = false,
) {
    /** How many probe slots the site takes: every outcome, less a throwing default. Zero for a dropped site. */
    val probedOutcomeCount: Int
        get() =
            when {
                dropReason != null -> 0
                throwingDefault -> outcomeCount - 1
                else -> outcomeCount
            }
}
