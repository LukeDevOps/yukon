package com.example.target;

/**
 * Calls kotlinc's generated forwarders the way Java code does. Kotlin callers never call them: a
 * Kotlin call that omits a default goes to the {@code $default} twin, and one to an interface's
 * default method goes to the interface. See ADR 0041.
 */
public class GeneratedForwarderCaller {

    public static Price callsOverloadConstructor() {
        return new Price(1);
    }

    public static String callsOverloadMethod(Price price) {
        return price.format(1);
    }

    public static String callsTopLevelOverload() {
        return GeneratedTargetKt.formatPrice(1L);
    }

    @SuppressWarnings("deprecation")
    public static int callsDefaultImpls(GeneratedInterface target) {
        return GeneratedInterface.DefaultImpls.withBody(target);
    }

    public static String callsMultifileFacade() {
        return MultifileText.multifileGreeting("x");
    }
}
