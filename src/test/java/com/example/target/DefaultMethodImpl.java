package com.example.target;

/** Inherits {@link DefaultMethodTarget#defaultThing()} rather than overriding it, so a call lands on the interface's body. */
public class DefaultMethodImpl implements DefaultMethodTarget {

    @Override
    public String abstractThing() {
        return "impl";
    }
}
