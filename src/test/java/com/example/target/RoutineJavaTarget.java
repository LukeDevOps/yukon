package com.example.target;

import java.io.StringReader;

/** Java branch outcomes of each routine shape, and of the shapes that stay findings. */
public class RoutineJavaTarget {

    public int earlyReturn(String value) {
        if (value == null) return 0;
        return value.length();
    }

    public int guardThrow(int amount) {
        if (amount < 0) throw new IllegalArgumentException("negative amount: " + amount);
        return amount;
    }

    public int tryFinally(boolean flag) {
        int result = 0;
        try {
            result = load().length();
        } finally {
            if (flag) result += 1;
        }
        return result;
    }

    /** The throw is caught right here, so its side leads to the catch block's fallback. */
    public int caughtGuard(String value) {
        try {
            if (value == null) throw new IllegalArgumentException("missing");
            return value.length();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** The catch rethrows only when it cannot recover, so the guard's throw can still lead to a return. */
    public int caughtGuardThatMayRecover(String value, boolean retryable) {
        try {
            if (value == null) throw new IllegalArgumentException("missing");
            return value.length();
        } catch (IllegalArgumentException e) {
            if (retryable) return -1;
            throw e;
        }
    }

    /** A finally body with a loop of its own, whose counter sits in a different slot in each copy. */
    public int finallyWithLoop(int[] xs, Runnable body) {
        int count = xs.length;
        try {
            body.run();
        } finally {
            for (int i = 0; i < count; i++) {
                if (xs[i] > 3) count--;
            }
        }
        return count;
    }

    /** The catch recovers only through a handler nested inside it. */
    public int caughtGuardNestedRecovery(String value) {
        try {
            if (value == null) throw new IllegalArgumentException("missing");
            return value.length();
        } catch (IllegalArgumentException e) {
            try {
                mightThrow();
            } catch (RuntimeException x) {
                return -1;
            }
            throw e;
        }
    }

    private static void mightThrow() {
        Integer.parseInt("1");
    }

    /** The catch recovers by falling out of the try statement. */
    public int caughtGuardFallsThrough(String value, boolean retryable) {
        int n = 0;
        try {
            if (value == null) throw new IllegalArgumentException("missing");
            n = value.length();
        } catch (IllegalArgumentException e) {
            if (!retryable) throw e;
            n = -1;
        }
        return n;
    }

    /** The catch recovers by going round the loop again. */
    public int caughtGuardContinues(java.util.List<String> values, boolean retryable) {
        int total = 0;
        for (String value : values) {
            try {
                if (value == null) throw new IllegalArgumentException("missing");
                total += value.length();
            } catch (IllegalArgumentException e) {
                if (retryable) continue;
                throw e;
            }
        }
        return total;
    }

    /** The catch only wraps what it caught and throws again, so the guard's throw leaves the method. */
    public int caughtGuardWrapped(String value) {
        try {
            if (value == null) throw new IllegalArgumentException("missing");
            return value.length();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The try block never completes normally, so javac emits the finally body only on the exception path. */
    public void loopForeverFinally(java.util.concurrent.BlockingQueue<String> queue, boolean running) throws InterruptedException {
        try {
            while (true) {
                queue.take();
            }
        } finally {
            if (running) System.out.println("stopped");
        }
    }

    /** Two conditions on one line: {@code a} on the normal path, {@code b} only in the finally body. */
    // @formatter:off
    public void oneLineFinally(java.util.concurrent.BlockingQueue<String> queue, boolean a, boolean b) throws InterruptedException {
        if (a) queue.put("x"); try { while (true) queue.take(); } finally { if (b) System.out.println("stopped"); }
    }
    // @formatter:on

    private boolean fieldA;
    private boolean fieldB;

    /** The one-line case with fields, whose reads differ only in the field they name. */
    // @formatter:off
    public void oneLineFinallyFields(java.util.concurrent.BlockingQueue<String> queue) throws InterruptedException {
        if (fieldA) queue.put("x"); try { while (true) queue.take(); } finally { if (fieldB) System.out.println("stopped"); }
    }
    // @formatter:on

    /** The one-line case with locals declared before the try, read with no local variable table. */
    // @formatter:off
    public void oneLineFinallyLocals(java.util.concurrent.BlockingQueue<String> queue, int n, int m) throws InterruptedException {
        int x = n * 2; int y = m * 3; if (x > 0) queue.put("x"); try { while (true) queue.take(); } finally { if (y > 0) System.out.println("stopped"); }
    }
    // @formatter:on

    public int tryWithResources(StringReader reader, boolean flag) throws Exception {
        try (StringReader resource = reader) {
            return flag ? resource.read() : 2;
        }
    }

    public int tryWithResourcesNew(boolean flag) throws Exception {
        try (StringReader resource = new StringReader("abc")) {
            return flag ? resource.read() : 2;
        }
    }

    public int typedCatch(boolean flag) {
        try {
            return load().length();
        } catch (IllegalStateException e) {
            if (flag) return 1;
            return 2;
        }
    }

    private String load() {
        return "loaded";
    }
}
