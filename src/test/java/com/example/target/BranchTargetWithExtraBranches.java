package com.example.target;

/**
 * Same methods as {@link BranchTarget}, but {@code classify} carries two conditionals instead of
 * one. A test swaps these bytes in for {@code BranchTarget}'s through a transformer registered
 * ahead of Yukon's, standing in for another agent that rewrote the class first.
 */
public class BranchTargetWithExtraBranches {

    public String classify(int value) {
        if (value > 100) {
            return "large";
        }
        if (value > 0) {
            return "positive";
        }
        return "non-positive";
    }

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
