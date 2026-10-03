package dev.otherlode.instrumentation.branch

import dev.otherlode.export.ConditionPart

/**
 * One tracked branch point found in a class: either a two-outcome [ConditionalJump] or a
 * `TABLESWITCH`/`LOOKUPSWITCH`.
 *
 * [siteIndex] is assigned once per class by [BranchSiteAnalyzer], in bytecode encounter order
 * across all its methods. [BranchProbeMethodVisitor] numbers the same sites by their ordinal
 * within each method, with no shared state; the two agree because both walk the same methods in
 * the same order and track the same instructions.
 *
 * [outcomeCount] is the number of outcomes this site numbers. A conditional jump has 2 (taken,
 * not-taken). A switch has the case count plus one for the default.
 *
 * [dropReason] is set when the site gets no probe at all; see [BranchDropReason].
 * [line] and [inlinedFromClassName] describe an inlined copy: a site inside code kotlinc copied
 * from an inline function's body into this site's method. [inlinedFromClassName] is null for the
 * class's own code, and set to the origin class, dotted, otherwise; [line] is the origin's own
 * source line for a kept copy, and this method's own line otherwise. A dropped copy carries its
 * origin too, so that [BranchKeys] tells it apart from the class's own code whatever the scope.
 *
 * [conditionFingerprint] is the site's canonical fingerprint text, built by [ConditionFingerprinter]
 * from the instructions between the last point the operand stack was empty and the site's own jump
 * or switch. It is null when the site cannot be fingerprinted with confidence.
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
 * write.
 *
 * [caseLabels] is set only for a switch [SwitchLowering] rebuilt from a string or enum lowering:
 * one label per case outcome, in the same order as [caseKeys], each the constant, literal or type
 * the source names. [throwingDefault] is true when that switch's default only throws an exception
 * the compiler added. The default then keeps its branch index but gets no probe, and the site does
 * not list it.
 *
 * [unprobedOutcome] is set for a conditional whose outcome at that offset only a hash collision can
 * reach: the not-equal side of the last `equals` check in a hash bucket of kotlinc's string `when`
 * or scalac's string `match`. It is treated the same way as a throwing default.
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
    val unprobedOutcome: Int? = null,
) {
    /**
     * The offsets of the outcomes that get a probe, in offset order: every outcome less a
     * throwing default and an [unprobedOutcome]. Empty for a dropped site.
     */
    val probedPositions: List<Int>
        get() =
            when {
                dropReason != null -> emptyList()
                throwingDefault -> (0 until outcomeCount - 1).toList()
                else -> (0 until outcomeCount).filter { it != unprobedOutcome }
            }

    /** How many probe slots the site takes: the size of [probedPositions]. */
    val probedOutcomeCount: Int
        get() = probedPositions.size
}
