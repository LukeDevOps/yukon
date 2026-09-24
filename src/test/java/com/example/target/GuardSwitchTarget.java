package com.example.target;

/** A switch whose case bodies and default body each call their own method, for ADR 0037's guarded code. */
public class GuardSwitchTarget {

    public int select(int code) {
        int result;
        switch (code) {
            case 1:
                result = one(); // marker: select-one
                break;
            case 2:
                result = two(); // marker: select-two
                break;
            case 5:
                result = five(); // marker: select-five
                break;
            default:
                result = other(); // marker: select-default
                break;
        }
        return result + 1; // marker: select-after
    }

    private int one() {
        return 1;
    }

    private int two() {
        return 2;
    }

    private int five() {
        return 5;
    }

    private int other() {
        return 0;
    }
}
