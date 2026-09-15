package io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture;

import com.example.remap.Before;
import net.bytebuddy.asm.Advice;

/**
 * A minimal advice class referencing {@link Before}, used only to prove that
 * {@link io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder}'s prefix remap
 * rewrites the reference in its bytecode. Never woven into a real target.
 */
public class RemapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) Before before) {
    }
}
