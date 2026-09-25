package com.example.target;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The javac side of ADR 0042. {@link #lambda} converts a lambda to {@link Runnable}, so its
 * creation edge names that interface. {@link #anonymous} creates an anonymous class with
 * {@code new}, so its edge names none. {@link #twoInterfaces} hands one method to two interfaces,
 * which gives two creation edges.
 */
public class ImplementedInterfaceJavaTarget {
    public Runnable lambda() {
        return () -> touch();
    }

    public Runnable anonymous() {
        return new Runnable() {
            @Override
            public void run() {
                touch();
            }
        };
    }

    public Object[] twoInterfaces() {
        IntSupplier asInt = this::touch;
        Supplier<Integer> boxed = this::touch;
        return new Object[] {asInt, boxed};
    }

    public int touch() {
        return 1;
    }
}
