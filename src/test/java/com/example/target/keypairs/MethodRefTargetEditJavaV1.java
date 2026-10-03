package com.example.target.keypairs;

import java.util.List;

/** The condition passes a reference to {@code isA}. */
public class MethodRefTargetEditJavaV1 {
    static boolean isA(Integer x) {
        return x > 1;
    }

    static boolean isB(Integer x) {
        return x > 2;
    }

    int check(List<Integer> xs) {
        return xs.stream().anyMatch(MethodRefTargetEditJavaV1::isA) ? 1 : 0;
    }
}
