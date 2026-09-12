package io.github.lukedevops.yukon.advice;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into every instrumented method's entry point.
 *
 * {@code probes} resolves to a synthetic static field. The instrumenting code injects this
 * field onto the same class. It holds the exact {@code long[]} that class's
 * {@link io.github.lukedevops.yukon.registry.ProbeRegistry} entry owns.
 *
 * {@code index} is a per-method constant, bound at weave time. Both {@code probes} and
 * {@code index} are direct reads, so a hit is one array store with no lookup of any kind.
 */
public class MethodEntryAdvice {

    public static final String PROBE_ARRAY_FIELD = "$yukonProbeCounts";

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.FieldValue(PROBE_ARRAY_FIELD) long[] probes,
            @ProbeIndex int index) {
        probes[index]++;
    }
}
