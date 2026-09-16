package com.example.target;

/**
 * javac attaches an {@code EnclosingMethod} attribute to both an anonymous class and a named local
 * class declared inside a method, so both are body classes under ADR 0024. Used to prove the
 * body-class rule fires for javac output the same way it does for kotlinc's.
 */
public class AnonymousClassTarget {

    private void helper() {
    }

    public Runnable makeAnonymousRunnable() {
        return new Runnable() {
            public void run() {
                helper();
            }
        };
    }

    public Runnable makeLocalClassRunnable() {
        class LocalRunnable implements Runnable {
            public void run() {
                helper();
            }
        }
        return new LocalRunnable();
    }
}
