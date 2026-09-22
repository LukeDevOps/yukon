package com.example.fixture.unused;

/**
 * The one class of the {@code dep-unused} fixture jar, which the {@code agentTest} suite puts on
 * its runtime classpath and never loads, so the jar reads as unloaded. The jar carries no
 * {@code pom.properties}, so its identity comes from its filename, with an empty group.
 */
public final class Unused {
    private Unused() {}
}
