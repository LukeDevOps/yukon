package com.example.target.keypairs;

import java.util.List;

/** The condition passes a lambda, which javac names {@code lambda$check$0}. */
public class LambdaAddedEarlierJavaV1 {
    int check(List<Integer> xs) {
        return xs.stream().anyMatch(x -> x > 1) ? 1 : 0;
    }
}
