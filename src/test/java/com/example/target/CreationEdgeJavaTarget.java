package com.example.target;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * Each shape of creation edge javac emits (ADR 0034): a lambda capturing a local, a lambda
 * capturing the receiver, a bound and an unbound reference to an instance method, a reference to
 * a static method, and two constructor references. javac hands a static nested class's
 * constructor straight to the metafactory, but turns a reference to an inner class's constructor
 * into a {@code lambda$} body of its own, since that constructor needs the enclosing instance.
 */
public class CreationEdgeJavaTarget {
    private final int base = 10;

    public int capturing(int offset) {
        IntUnaryOperator op = v -> v + offset;
        return op.applyAsInt(1);
    }

    public int capturingThis() {
        IntUnaryOperator op = v -> v + base;
        return op.applyAsInt(1);
    }

    public Supplier<String> boundReference() {
        return this::name;
    }

    public Function<CreationEdgeJavaTarget, String> unboundReference() {
        return CreationEdgeJavaTarget::name;
    }

    public IntSupplier staticReference() {
        return CreationEdgeJavaTarget::constant;
    }

    public Function<String, Box> nestedConstructorReference() {
        return Box::new;
    }

    public Supplier<Inner> innerConstructorReference() {
        return Inner::new;
    }

    public String name() {
        return "name";
    }

    public static int constant() {
        return 1;
    }

    /** A static nested class with a one-argument constructor. */
    public static class Box {
        public Box(String label) {
        }
    }

    /** An inner class, so its constructor takes the enclosing instance as its first parameter. */
    public class Inner {
    }
}
