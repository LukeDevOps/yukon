package com.example.target;

/** A Java record with one hand-written method beside its generated members. See ADR 0026. */
public record RecordTarget(int x, String y) {
    public int extra() {
        return x;
    }
}

/** A plain Java enum, alongside {@link RecordTarget} in the same file. */
enum JavaColour {
    RED,
    GREEN,
}
