package com.example.target;

import java.io.StringReader;

/** Java branch outcomes of the shapes ADR 0046 names, and of the shapes it leaves as findings. */
public class RoutineJavaTarget {

    public int earlyReturn(String value) {
        if (value == null) return 0;
        return value.length();
    }

    public int guardThrow(int amount) {
        if (amount < 0) throw new IllegalArgumentException("negative amount: " + amount);
        return amount;
    }

    public int tryFinally(boolean flag) {
        int result = 0;
        try {
            result = load().length();
        } finally {
            if (flag) result += 1;
        }
        return result;
    }

    public int tryWithResources(StringReader reader, boolean flag) throws Exception {
        try (StringReader resource = reader) {
            return flag ? resource.read() : 2;
        }
    }

    public int tryWithResourcesNew(boolean flag) throws Exception {
        try (StringReader resource = new StringReader("abc")) {
            return flag ? resource.read() : 2;
        }
    }

    public int typedCatch(boolean flag) {
        try {
            return load().length();
        } catch (IllegalStateException e) {
            if (flag) return 1;
            return 2;
        }
    }

    private String load() {
        return "loaded";
    }
}
