package io.github.lukedevops.demo.shapes;

import java.util.function.Supplier;

/**
 * Java shapes for the shapes run: two anonymous classes, one run and one only created, and a lambda
 * created but never called. The system properties that would run the others are never set.
 */
public final class JavaShapes {
    private JavaShapes() {
    }

    public static void run() {
        Runnable greeting = new Runnable() {
            @Override
            public void run() {
                System.out.println("hello from an anonymous class");
            }
        };
        greeting.run();

        Runnable farewell = new Runnable() {
            @Override
            public void run() {
                System.out.println("goodbye from an anonymous class");
            }
        };
        if (Boolean.getBoolean("yukon.demo.farewell")) {
            farewell.run();
        }

        Supplier<String> lazyGreeting = () -> "hello from a lambda";
        if (Boolean.getBoolean("yukon.demo.lazy")) {
            System.out.println(lazyGreeting.get());
        }
    }
}
