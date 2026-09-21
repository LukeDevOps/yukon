package com.example.target;

import com.example.library.Lib;

/**
 * References library classes that the end-to-end reference test serves from a jar it builds, and
 * one ({@link Lib.Helper}) it leaves out of that jar, so the reference is absent at run time.
 */
public class WidgetUser {
    public Lib.FieldType field;

    public Object make() {
        return new Lib.Widget();
    }

    public Object missing() {
        return new Lib.Helper();
    }
}
