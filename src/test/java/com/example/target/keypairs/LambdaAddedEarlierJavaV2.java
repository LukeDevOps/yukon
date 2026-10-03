package com.example.target.keypairs;

import java.util.List;

/** An earlier method gains a lambda, so the condition's own lambda is numbered one higher. */
public class LambdaAddedEarlierJavaV2 {
    Runnable earlier() {
        return () -> System.out.println("earlier");
    }

    int check(List<Integer> xs) {
        return xs.stream().anyMatch(x -> x > 1) ? 1 : 0;
    }
}
