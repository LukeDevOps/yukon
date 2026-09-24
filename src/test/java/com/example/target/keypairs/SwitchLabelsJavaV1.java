package com.example.target.keypairs;

import com.example.target.SwitchColor;

/** A switch over an enum, a string and a type, before a case is added to each. See ADR 0038. */
public class SwitchLabelsJavaV1 {

    public int enumSwitch(SwitchColor color) {
        switch (color) {
            case RED:
                return 1;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }

    public int stringSwitch(String status) {
        switch (status) {
            case "open":
                return 1;
            case "closed":
                return 2;
            default:
                return 0;
        }
    }

    public int typeSwitch(Object value) {
        return switch (value) {
            case String s -> 1;
            case Long l -> 3;
            default -> 0;
        };
    }
}
