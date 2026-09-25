package com.example.target;

/**
 * A Java class compiled with -g, so the LocalVariableTable names each parameter. ADR 0043 reads
 * the names from it, and a test strips the table to show an empty list.
 */
public class SignatureJavaTarget {

    private final String name;

    public SignatureJavaTarget(String name, long seed) {
        this.name = name + seed;
    }

    public static String join(String left, long count, double ratio, int last) {
        return left + count + ratio + last;
    }

    public String greet(String greeting) {
        String shown = greeting + " " + name;
        return shown;
    }
}
