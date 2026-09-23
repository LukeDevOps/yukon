package io.github.lukedevops.yukon.endpoints.lambdafactory;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.lang.invoke.MethodHandleInfo;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code java.lang.invoke.InnerClassLambdaMetafactory.spinInnerClass()},
 * which returns the hidden class the JDK spins for one lambda or method reference. It hands that
 * class, the functional interface it implements, and the method it calls to {@link
 * YukonEndpoints#recordLambdaClass}. See ADR 0035.
 *
 * <p>{@code interfaceClass} and {@code implInfo} are fields the factory inherits from {@code
 * AbstractValidatingLambdaMetafactory} in the same package. {@code LambdaFactoryShape.JDK} names
 * the same members, and the hook installs only after it has checked them on the running JDK.
 *
 * <p>This advice is inlined into {@code java.base}, so it may only call the bootstrap seam. It
 * must not contain a lambda or a method reference: either would call the method it is woven into.
 */
public class SpinInnerClassAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Return Class<?> lambdaClass,
            @Advice.FieldValue("interfaceClass") Class<?> interfaceClass,
            @Advice.FieldValue("implInfo") MethodHandleInfo implInfo) {
        YukonEndpoints.recordLambdaClass(lambdaClass, interfaceClass, implInfo);
    }
}
