package io.github.lukedevops.demo.fixtures;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Runs once under the agent and exits, calling the named methods of a Scala fixture module's
 * {@code Driver} object through the static forwarders scalac puts on its class. The first argument
 * is how many seconds to wait before exiting, so the static baseline scan and a regular flush land
 * first; the rest name the methods to call. Written in Java so the run's classpath carries no Kotlin
 * standard library, which the report would otherwise list as a dependency of the Scala service.
 */
public final class ScalaDriverMain {
    private ScalaDriverMain() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> driver = Class.forName("com.example.scalatarget.Driver");
        for (String name : Arrays.copyOfRange(args, 1, args.length)) {
            Method method = driver.getMethod(name);
            System.out.println(name + " -> " + method.invoke(null));
        }
        Thread.sleep(Long.parseLong(args[0]) * 1000);
    }
}
