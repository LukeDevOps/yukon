package com.example.target;

/**
 * A sparse switch, which javac compiles to {@code LOOKUPSWITCH}, followed by a conditional in the
 * same method. A dropped switch has to advance the per-method site ordinal without taking a slot,
 * or the conditional after it is mistaken for the dropped site.
 */
public class SwitchThenBranchTarget {

    public int classify(int value) {
        switch (value) {
            case 1:
                return 10;
            case 1000:
                return 20;
            default:
                break;
        }
        if (value > 5000) {
            return 30;
        }
        return 40;
    }
}
