package com.example.target;

import java.util.function.Supplier;

/** Fixtures for the condition writer's Java idioms and jump families. Each method holds one site. See ADR 0037. */
public class ConditionJavaTarget {
    private int count;
    private static int total;

    public int getCount() {
        return count;
    }

    public int icmpEq(int a, int b) {
        if (a == b) return 1;
        return 0;
    }

    public int icmpNe(int a, int b) {
        if (a != b) return 1;
        return 0;
    }

    public int icmpLt(int a, int b) {
        if (a < b) return 1;
        return 0;
    }

    public int icmpGe(int a, int b) {
        if (a >= b) return 1;
        return 0;
    }

    public int icmpGt(int a, int b) {
        if (a > b) return 1;
        return 0;
    }

    public int icmpLe(int a, int b) {
        if (a <= b) return 1;
        return 0;
    }

    public int zeroLt(int a) {
        if (a < 0) return 1;
        return 0;
    }

    public int zeroEq(int a) {
        if (a == 0) return 1;
        return 0;
    }

    public int longCompare(long a, long b) {
        if (a > b) return 1;
        return 0;
    }

    public int floatLess(float a, float b) {
        if (a < b) return 1;
        return 0;
    }

    public int doubleGreater(double a, double b) {
        if (a > b) return 1;
        return 0;
    }

    public int doubleNotGreater(double a, double b) {
        if (!(a > b)) return 1;
        return 0;
    }

    public int nullCheck(String s) {
        if (s != null) return 1;
        return 0;
    }

    public int nonNullCheck(String s) {
        if (s == null) return 1;
        return 0;
    }

    public int referenceEq(Object a, Object b) {
        if (a == b) return 1;
        return 0;
    }

    public int referenceNe(Object a, Object b) {
        if (a != b) return 1;
        return 0;
    }

    public int equalsCall(String a, String b) {
        if (a.equals(b)) return 1;
        return 0;
    }

    public int instanceOfCheck(Object o) {
        if (o instanceof String) return 1;
        return 0;
    }

    public int getterCall(ConditionJavaTarget other) {
        if (other.getCount() > 2) return 1;
        return 0;
    }

    public int fieldRead(ConditionJavaTarget other) {
        if (other.count + total > 10) return 1;
        return 0;
    }

    public int arrayRead(int[] values, int i) {
        if (values[i] > values.length) return 1;
        return 0;
    }

    public int newCheck(String seed) {
        if (new StringBuilder(seed).length() > 3) return 1;
        return 0;
    }

    public int concatCheck(String name, int n) {
        if ((name + "-" + n).equals("x-1")) return 1;
        return 0;
    }

    public int numericConcatCheck(int a, int b) {
        if (("" + a + b).isEmpty()) return 1;
        return 0;
    }

    public int negationCheck(int a) {
        if (-a > 5) return 1;
        return 0;
    }

    public int charCheck(char c) {
        if (c == 'x') return 1;
        return 0;
    }

    public int boxedCheck(Integer boxed) {
        if (boxed > 4) return 1;
        return 0;
    }

    public int castCheck(Object o) {
        if (((String) o).length() > 1) return 1;
        return 0;
    }

    public int classLiteralCheck(Object o) {
        if (o.getClass() == String.class) return 1;
        return 0;
    }

    public int lambdaCheck(int a) {
        if (call(() -> a) > 0) return 1;
        return 0;
    }

    public int dupCheck(int a) {
        int b;
        if ((b = a) > 0) return b;
        return 0;
    }

    public int unknownSwitch(long value) {
        switch ((int) value) {
            case 1:
                return 10;
            case 2:
                return 20;
            default:
                return 0;
        }
    }

    public int subjectSwitch(int value) {
        switch (value + 1) {
            case 1:
                return 10;
            case 2:
                return 20;
            default:
                return 0;
        }
    }

    public int literalCheck(String text) {
        if (text.equals("say \"hi\"\nbye")) return 1;
        return 0;
    }

    public int nullLiteralCheck(Object o) {
        if (o != null && o.hashCode() > 7L) return 1;
        return 0;
    }

    public int narrowingCheck(long total) {
        if ((int) total > 3) return 1;
        return 0;
    }

    public int doubleToLongCheck(double value, int offset) {
        if ((long) (value + offset) > 3L) return 1;
        return 0;
    }

    public int charNarrowingCheck(int code) {
        if ((char) code == 'x') return 1;
        return 0;
    }

    public int intToDoubleCheck(int a) {
        if (a > 0.5) return 1;
        return 0;
    }

    public int floatToDoubleCheck(float f) {
        if (f > 0.5) return 1;
        return 0;
    }

    public int longToFloatCheck(long a, float b) {
        if (a < b) return 1;
        return 0;
    }

    public int patternSwitch(Object o) {
        switch (o) {
            case String s:
                return 1;
            case Integer i:
                return 2;
            default:
                return 0;
        }
    }

    private static int call(Supplier<Integer> supplier) {
        return supplier.get();
    }
}
