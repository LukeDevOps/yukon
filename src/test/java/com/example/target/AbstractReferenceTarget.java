package com.example.target;

import com.example.library.Lib;

/**
 * An abstract method has no body and so no probe, so its signature's references belong to the
 * class; the concrete method beside it references nothing outside scope.
 */
public abstract class AbstractReferenceTarget {
    public abstract Lib.AbstractReturn abs(Lib.AbstractParam p);

    public int concrete() {
        return 1;
    }
}
