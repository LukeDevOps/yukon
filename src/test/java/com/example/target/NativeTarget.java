package com.example.target;

/** Declares a native method that has no body to probe and no library to back it; only its declaration matters here. */
public class NativeTarget {

    public native void nativeThing();

    public String normalThing() {
        return "normal";
    }
}
