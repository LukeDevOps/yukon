package com.example.target;

/** A plain-Java fixture with no runtime dependencies. This lets it load via a bare bootstrap-parented classloader. */
public class SampleTarget {

    public String ping() {
        return "pong";
    }

    public String neverCalled() {
        return "dead code";
    }
}
