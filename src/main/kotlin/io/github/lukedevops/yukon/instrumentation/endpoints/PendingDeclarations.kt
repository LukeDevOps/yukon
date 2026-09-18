package io.github.lukedevops.yukon.instrumentation.endpoints

import java.lang.System.Logger.Level

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
    private val log = System.getLogger(PendingDeclarations::class.java.name)
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

    /**
     * Starts holding declarations on this thread, and returns how many are held already.
     *
     * Every module whose type matcher matched a class gets its own transform callback, and
     * ByteBuddy runs them all for that one class before it rewrites anything, so this is called
     * once per matching module rather than once per class. Only the first call opens the list. A
     * later one leaves what earlier modules staged in place and reports the size, which its
     * caller keeps as the mark to [rollbackTo] if its own module throws.
     *
     * ByteBuddy can also run a whole transform twice for one class: its default fallback strategy
     * retries with a different type description after a `LinkageError`, with no listener call in
     * between to close the list. The retry stages the same declarations a second time, which is
     * harmless, since declaring one endpoint key twice is the same endpoint.
     */
    fun begin(): Int {
        val pending = staged.get()
        if (pending != null) return pending.size
        staged.set(mutableListOf())
        return 0
    }

    /**
     * Drops everything staged past [mark], for a module that threw partway through declaring.
     *
     * Its own routes are only half read by then, and half a route list is worse than none: a
     * route the walk never reached looks like one the framework does not serve, rather than one
     * nobody called. Modules that already finished keep what they staged.
     */
    fun rollbackTo(mark: Int) {
        val pending = staged.get() ?: return
        while (pending.size > mark) pending.removeAt(pending.size - 1)
    }

    /**
     * Runs what this thread staged, in the order it was declared, and stops holding.
     *
     * A declaration that throws is logged and the rest still run. Declaring an endpoint must
     * never disturb the application, which is why the seam swallows a failure of its own. Letting
     * one escape here would reach ByteBuddy's error path instead, and that discards the rewritten
     * class, so one bad route would cost the whole class its advice.
     */
    fun commit() {
        val pending = staged.get() ?: return
        staged.remove()
        for (declaration in pending) {
            try {
                declaration()
            } catch (t: Throwable) {
                log.log(Level.WARNING, "yukon: an endpoint declaration failed while committing, the rest still apply", t)
            }
        }
    }

    /** Drops what this thread staged without running any of it, and stops holding. */
    fun discard() {
        staged.remove()
    }

    /** How many declarations this thread is holding; for tests. */
    fun pendingCount(): Int = staged.get()?.size ?: 0
}
