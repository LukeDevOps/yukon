package com.example.target;

/** Its static initializer and one ordinary method each hold a conditional. */
public class StaticInitBranchTarget {

    public static final int CHOSEN = Boolean.getBoolean("yukon.test.flag") ? 1 : 2;

    public static int pick(int value) {
        if (value > 0) {
            return 1;
        }
        return 0;
    }
}
