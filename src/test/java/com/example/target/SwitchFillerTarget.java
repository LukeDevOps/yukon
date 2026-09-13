package com.example.target;

/**
 * Case values 1, 2, 3 and 5 are dense enough for javac to emit a {@code TABLESWITCH} over 1..5,
 * with a filler entry for 4 that jumps straight to the default label.
 */
public class SwitchFillerTarget {

    public int classifyGappy(int value) {
        switch (value) {
            case 1:
                return 1;
            case 2:
                return 2;
            case 3:
                return 3;
            case 5:
                return 5;
            default:
                return -1;
        }
    }
}
