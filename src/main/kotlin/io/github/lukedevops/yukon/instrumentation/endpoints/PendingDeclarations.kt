package io.github.lukedevops.yukon.instrumentation.endpoints

/**
 * Holds endpoint registrations a module made while ByteBuddy was rewriting a class, until that
 * rewrite is known to have succeeded.
 *
 * A module that reads its routes off a class's own annotations declares them from inside the
 * transform callback, which ByteBuddy runs before it rewrites and validates the bytes. Declaring
 * straight into the registry there publishes endpoints for a class that may still fail to weave:
 * the class then serves requests with no advice on it, the endpoint's hit count stays at zero,
 * and a collector reads "never called" for a route the framework is serving. This is the endpoint
 * form of what ADR 0007 rules out for probes, so it gets the same answer: hold the declarations,
 * and commit them only once the rewrite has produced bytes.
 *
 * One slot per thread, with no key. ByteBuddy runs the transform callback, the rewrite and the
 * result callbacks back to back on the loading thread, and its `CircularityLock` holds a
 * per-thread entry throughout, so one thread has at most one class in flight.
 *
 * A module that declares its routes at runtime instead, by walking a framework object once the
 * application has built it, is not staging: no transform is in flight on that thread, [stage]
 * declines the work, and the caller registers as it always did.
 */
class PendingDeclarations internal constructor() {
    private val staged = ThreadLocal<MutableList<() -> Unit>?>()

    /**
     * Holds [declaration] if this thread is inside a transform and returns true. Returns false
     * when nothing is in flight, leaving the caller to run it.
     */
    fun stage(declaration: () -> Unit): Boolean {
        val pending = staged.get() ?: return false
        pending += declaration
        return true
    }

    /** Starts holding declarations on this thread, discarding anything an earlier transform left. */
    fun begin() {
        staged.set(mutableListOf())
    }

    /** Runs what this thread staged, in the order it was declared, and stops holding. */
    fun commit() {
        val pending = staged.get() ?: return
        staged.remove()
        for (declaration in pending) declaration()
    }

    /** Drops what this thread staged without running any of it, and stops holding. */
    fun discard() {
        staged.remove()
    }

    /** How many declarations this thread is holding; for tests. */
    fun pendingCount(): Int = staged.get()?.size ?: 0
}
