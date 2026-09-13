package com.example.target;

/** An interface with bodies to probe: one default method and one static method, plus an abstract one that has none. */
public interface DefaultMethodTarget {

    default String defaultThing() {
        return "default";
    }

    static String staticThing() {
        return "static";
    }

    String abstractThing();
}
