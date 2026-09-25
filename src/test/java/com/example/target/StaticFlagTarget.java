package com.example.target;

/**
 * A static method with a conditional, an instance method, a constructor and a static initializer.
 * ADR 0040 marks only the static method's METHOD probe static.
 */
public class StaticFlagTarget {

    static final long STARTED = System.nanoTime();

    public StaticFlagTarget() {
    }

    public static int twice(int value) {
        return value > 0 ? value * 2 : 0;
    }

    public int plusOne(int value) {
        return value + 1;
    }
}
