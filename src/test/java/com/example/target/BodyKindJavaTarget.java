package com.example.target;

/**
 * The javac body-class shapes ADR 0034's {@code body_kind} names that {@link AnonymousClassTarget}
 * does not already cover: a local class named {@code Local}, and an anonymous class in a field
 * initializer, whose {@code EnclosingMethod} attribute names the class but no method. The static
 * nested class carries no {@code EnclosingMethod} attribute at all, so it is not a body class.
 */
public class BodyKindJavaTarget {
    private final Runnable fromField = new Runnable() {
        public void run() {
        }
    };

    public Runnable localClass() {
        class Local implements Runnable {
            public void run() {
            }
        }
        return new Local();
    }

    /** A named nested class, not a body class. */
    public static class Nested {
    }
}
