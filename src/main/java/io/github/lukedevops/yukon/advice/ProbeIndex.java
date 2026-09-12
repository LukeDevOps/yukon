package io.github.lukedevops.yukon.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an {@link net.bytebuddy.asm.Advice} parameter to the constant slot index a woven probe
 * owns in its class's counts array. Each method supplies its own index at weave time, through
 * {@code Advice.withCustomMapping().bind(ProbeIndex.class, index)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ProbeIndex {
}
