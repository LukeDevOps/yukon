package com.example.target;

import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * A lambda body compiles to a private static synthetic {@code lambda$...} method, and a method
 * reference compiles to an {@code invokedynamic} whose handle points straight at the referenced
 * method, leaving that method itself untouched. Used to prove that the method and branch tiers
 * probe a lambda body's own synthetic method, including any conditional inside it, while a method
 * reference's target keeps behaving as an ordinary probed method.
 */
public class LambdaTarget {

    public int classifyViaLambda(int value) {
        IntUnaryOperator classify =
                v -> {
                    if (v > 0) {
                        return 1;
                    }
                    return -1;
                };
        return classify.applyAsInt(value);
    }

    public String shipViaMethodReference() {
        Supplier<String> supplier = this::ship;
        return supplier.get();
    }

    public String ship() {
        return "shipped";
    }
}
