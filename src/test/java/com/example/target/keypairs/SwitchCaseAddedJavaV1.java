package com.example.target.keypairs;

/**
 * A dense {@code switch} over an {@code int} with three cases and a {@code default}. Three dense,
 * consecutive case values compile to a real {@code TABLESWITCH}, confirmed with {@code javap -c}
 * against this fixture's own compiled class; javac packs even a two-case dense switch into a
 * {@code LOOKUPSWITCH} instead, so this fixture needs the third case to get a real jump table.
 */
public class SwitchCaseAddedJavaV1 {
    public String classify(int x) {
        switch (x) {
            case 1:
                return "one";
            case 2:
                return "two";
            case 3:
                return "three";
            default:
                return "other";
        }
    }
}
