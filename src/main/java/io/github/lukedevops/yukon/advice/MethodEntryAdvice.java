package io.github.lukedevops.yukon.advice;

import io.github.lukedevops.yukon.registry.ProbeDispatch;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into every instrumented method's entry point. The origin string is
 * resolved by ByteBuddy at weave time into a compile-time constant, so this
 * reduces to a single map lookup plus an array increment per call.
 */
public class MethodEntryAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin("#t:#m:#d") String originKey) {
        ProbeDispatch.INSTANCE.hit(originKey);
    }
}
