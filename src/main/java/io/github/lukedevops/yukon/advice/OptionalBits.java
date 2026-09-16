package io.github.lukedevops.yukon.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an {@link net.bytebuddy.asm.Advice} parameter to the constant bitmask of a woven
 * {@code $default} method's optional parameters, one set bit per parameter at its zero-based
 * value index. Each {@code $default} method supplies its own mask at weave time, through
 * {@code Advice.withCustomMapping().bind(OptionalBits.class, ...)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OptionalBits {
}
