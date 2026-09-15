package com.example.agenttarget

/**
 * Instrumented by the real, `-javaagent`-attached shaded agent jar in this module's `agentTest`
 * suite (see `testkit/build.gradle.kts`). [exercised] is called from
 * `AgentTargetExercisedTest`; [neverCalled] never is, and its probe should report exactly that.
 */
class AgentTarget {
    fun exercised() {}

    fun neverCalled() {}
}
