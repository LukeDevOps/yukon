package com.example.target.keypairs;

import java.util.List;

/** The condition passes a reference to {@code isB} instead: a different condition. */
public class MethodRefTargetEditJavaV2 {
    static boolean isA(Integer x) {
        return x > 1;
    }

    static boolean isB(Integer x) {
        return x > 2;
    }

    int check(List<Integer> xs) {
        return xs.stream().anyMatch(MethodRefTargetEditJavaV2::isB) ? 1 : 0;
    }
}
