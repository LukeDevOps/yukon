package com.example.target.keypairs;

/** The same {@code switch}, with a fourth case added between the existing ones and {@code default}. */
public class SwitchCaseAddedJavaV2 {
    public String classify(int x) {
        switch (x) {
            case 1:
                return "one";
            case 2:
                return "two";
            case 3:
                return "three";
            case 4:
                return "four";
            default:
                return "other";
        }
    }
}
