package com.example.target;

/** A plain-Java fixture exercising field and method-call tokens inside a condition's window. */
public class FingerprintJavaTarget {

    private int threshold;

    public boolean overThreshold(int value) {
        if (value > threshold) {
            return true;
        }
        return false;
    }

    public boolean bothSidesPositive(
            int a,
            int b) {
        if (a > 0 && b > 0) {
            return true;
        }
        return false;
    }
}
