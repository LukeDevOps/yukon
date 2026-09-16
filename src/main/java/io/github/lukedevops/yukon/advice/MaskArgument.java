package io.github.lukedevops.yukon.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an {@link net.bytebuddy.asm.Advice} parameter to a woven {@code $default} method's first
 * mask {@code int}, read as a local variable at its own offset rather than through
 * {@code @Advice.Argument}, since the mask parameter's index differs per method. Bound through
 * {@code Advice.withCustomMapping().bind(MaskArgument.class, ...)} at weave time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface MaskArgument {
}
