package com.example.fixture.used;

/**
 * The one class of the {@code dep-used} fixture jar, which the {@code agentTest} suite puts on its
 * runtime classpath. {@code com.example.agenttarget.DependencyUser} calls it, so the jar reads as
 * used.
 */
public final class Greeter {
    private Greeter() {}

    /** Returns a greeting for {@code name}. */
    public static String greet(String name) {
        return "hello " + name;
    }
}
