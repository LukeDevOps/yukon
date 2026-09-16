package io.github.lukedevops.yukon.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an {@link net.bytebuddy.asm.Advice} parameter to the constant slot a woven
 * {@code $default} method's first omission probe owns in its class's counts array. Each
 * {@code $default} method supplies its own base slot at weave time, through
 * {@code Advice.withCustomMapping().bind(OmissionBase.class, ...)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OmissionBase {
}
