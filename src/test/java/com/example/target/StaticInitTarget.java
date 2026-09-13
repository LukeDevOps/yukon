package com.example.target;

/** Its own static initializer calls a probed static method, so the probe array must already be in place by then. */
public class StaticInitTarget {

    public static final int TOUCHED = poke();

    static int poke() {
        return 7;
    }
}
