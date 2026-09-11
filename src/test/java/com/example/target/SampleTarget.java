package com.example.target;

/** A plain-Java fixture so it can be loaded via a bare bootstrap-parented classloader with no runtime dependencies. */
public class SampleTarget {

    public String ping() {
        return "pong";
    }

    public String neverCalled() {
        return "dead code";
    }
}
