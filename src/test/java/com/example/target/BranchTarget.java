package com.example.target;

/** A plain-Java fixture with a single conditional, used to test branch-level probe tracking. */
public class BranchTarget {

    public String classify(int value) {
        if (value > 0) {
            return "positive";
        }
        return "non-positive";
    }
}
