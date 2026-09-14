package io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture;

import net.bytebuddy.asm.Advice;

/** Counts entries into whatever method it is woven onto, to prove {@code AdviceBinder} resolved and applied it. */
public class PingAdvice {
    public static volatile int entries;

    @Advice.OnMethodEnter
    public static void onEnter() {
        entries++;
    }
}
