package com.example.target;

/** A plain-Java fixture with a single conditional, used to test branch-level probe tracking. */
public class BranchTarget {

    public String classify(int value) {
        if (value > 0) {
            return "positive";
        }
        return "non-positive";
    }

    /** Contiguous case values compile to a `TABLESWITCH`. */
    public int classifyDense(int value) {
        switch (value) {
            case 0:
                return 100;
            case 1:
                return 101;
            case 2:
                return 102;
            default:
                return -1;
        }
    }

    /** Widely-spaced case values compile to a `LOOKUPSWITCH`. */
    public int classifySparse(int value) {
        switch (value) {
            case 1:
                return 200;
            case 1000:
                return 201;
            default:
                return -1;
        }
    }
}
