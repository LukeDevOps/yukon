package io.github.lukedevops.yukon.advice;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into every woven Kotlin {@code $default} method's entry point.
 *
 * {@code probes} is the same counts array {@link MethodEntryAdvice} writes to: an omission probe
 * shares its class's one array with the method and branch tiers, rather than keeping a separate
 * structure. {@code mask} is the caller's actual argument mask, read live. {@code base} and
 * {@code optional} are per-method constants bound at weave time: {@code base} is this
 * {@code $default} method's first omission probe's slot, and {@code optional} has one set bit per
 * optional parameter.
 *
 * Slots are packed, not one per value parameter: bit {@code i}'s slot is {@code base +
 * bitCount(optional & ((1 << i) - 1))}, the count of set optional bits below {@code i}. Only a
 * caller-omitted bit that is also a real optional parameter increments anything, so a required
 * parameter's bit, which is never set in {@code optional}, can never advance the count and never
 * corrupts another parameter's slot.
 */
public class OptionalArgumentAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.FieldValue(MethodEntryAdvice.PROBE_ARRAY_FIELD) long[] probes,
            @MaskArgument int mask,
            @OmissionBase int base,
            @OptionalBits int optional) {
        int omitted = mask & optional;
        while (omitted != 0) {
            int lowest = omitted & -omitted;
            probes[base + Integer.bitCount(optional & (lowest - 1))]++;
            omitted ^= lowest;
        }
    }
}
