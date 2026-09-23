---
status: accepted
---

# A hidden handler is named through the lambda factory

Decided on 2026-09-23. This amends ADR 0017, whose JDK `HttpServer`, Ktor and Spring functional-route modules report null for a hidden handler class.

A handler written as a lambda or a method reference reaches its framework as a hidden class that the JDK spins at run time. The demo registers two: `server.createContext("/checkout", ::handleCheckout)` and `server.createContext("/__shutdown") { exchange -> ... }`. The class has no stable name and no class file, so the registration advice reports no handler, and `yukon-server` shows "Handler not named". Yet the JDK knows exactly which method each lambda class calls, at the moment it spins the class. The agent reads it there.

## The design

- **Watch the lambda factory.** Advice on the exit of `java.lang.invoke.InnerClassLambdaMetafactory.spinInnerClass()` reads the class it returns and two fields the factory inherits from `AbstractValidatingLambdaMetafactory`: `interfaceClass` (the functional interface) and `implInfo` (a `MethodHandleInfo` for the method the lambda calls). It hands all three to the bootstrap seam. `spinInnerClass()` covers a class the JDK loads from its CDS archive as well as one it generates. The members were checked with `javap` on JDK 21.0.5, 22.0.1, 25.0.4.1, 26.0.2.1 and 27, and they are the same on all of them.
- **Install by retransformation, with a check first.** The factory is loaded before `premain`, so the hook retransforms it. It uses its own `AgentBuilder`, separate from the endpoint modules' one. It adds advice to one method and makes no other change to the class. Before it retransforms, it checks by reflection that `spinInnerClass()` takes no arguments and returns `Class`, that `interfaceClass` is a `Class`, and that `implInfo` is a `MethodHandleInfo`. If a check fails, or the retransform fails, the agent logs one line and installs nothing. `java.base` gets a read edge to the seam's module the same way `jdk.httpserver` does.
- **Handler interfaces only.** `EndpointModule.handlerInterfaces` names the functional interfaces a framework takes a handler as. The JDK `HttpServer` module names `com.sun.net.httpserver.HttpHandler`. The hook installs only when some module names one. The seam keeps an entry only when the spun class's interface is in that set, so every other lambda in the process costs one set lookup. The interface is the one the lambda was written for, so a lambda for a subinterface of `HttpHandler` is not kept unless a module names that subinterface too.
- **A weak map in the seam.** The seam keeps a synchronized `WeakHashMap` from the hidden class to the method's owner (`Class.getName()`), name and descriptor. An unloaded lambda class drops out. A method whose owner is itself hidden is not kept, since its name joins to nothing. An abstract method is not kept either, since the method that runs depends on the receiver. The recording path in the seam and the advice hold no lambda or method reference, since one would ask the factory for a class while it is building one.
- **The endpoint advice asks the seam.** For a hidden handler class, the JDK `HttpServer` registration, `setHandler` and dispatch advice call `YukonEndpoints.lambdaImplementation` and report what it returns, exactly as recorded. The descriptor is the method's own, so it starts with any captured values: that is the real method, and it matches the manifest. A miss stays null.

## Considered options

- **Matching statically through `getNestHost()`, the functional interface and the `CREATES` edges of ADR 0034.** Rejected: it is ambiguous. The demo's `main` has two `invokedynamic` sites for `HttpHandler` in one nest host. Picking one by the bytecode index of the `StackWalker` caller breaks when a lambda is built in one place and passed to the registration from another.
- **Correlating at dispatch**, naming the first probed method the thread enters after `findContext`. Rejected: a filter, a wrapper or a hand-off to another thread runs first and takes the name.
- **Reading the hidden class's own bytecode.** Not possible. A hidden class is never passed to a `ClassFileTransformer`, and it cannot be retransformed. Checked on JDK 21.0.5, 25.0.4.1 and 27 with a transformer registered before the lambda was created: the transformer saw no `$$Lambda` class, `Instrumentation.isModifiableClass` returned false, and `retransformClasses` threw `UnmodifiableClassException`. `Instrumentation.getAllLoadedClasses` documents that it includes hidden classes, and `getInitiatedClasses` that it leaves them out, as no loader can find them.
- **Guessing when there is no entry**, for example from the handler's nest host and interface. Rejected: a wrong name is worse than no name. A reader cannot tell a guess from a fact, and a wrong join marks the wrong method as a handler.

## Consequences

- An endpoint whose handler is a lambda or method reference names the method the lambda calls: `lambda$main$0` from javac, `main$lambda$0` from kotlinc, or the named method for a reference such as `::handleCheckout` or `this::handle`. A lambda built in one class and registered from another names the method in the class that built it.
- A lambda class spun before the hook installs, a lambda class another spinner made, and every hidden handler on a JVM where the hook is off all report null, as before this decision.
- A JDK that renames one of these members switches the hook off with one log line rather than failing. CI runs the whole test suite on JDK 21 and on JDK 25, the newest LTS, so a rename in a new LTS fails a test there.
- A method reference whose target is abstract, such as `other::handle` where `other` is typed `HttpHandler`, reports null. The JDK reports `HttpHandler.handle`, which has no probe, and the method that runs depends on the receiver. Recording it would give the endpoint a name that looks right and joins to nothing.
- `yukon-server` ADR 0028 ("hidden code is named from its creation edge") names the lambda body the handler join points at, so the endpoint and the method row show the same code.
- This is the first of two chunks. The second collapses pass-throughs the lambda can point at (scalac's `$adapted` forwarders, kotlinc's reference classes, `$sam$` wrappers) and adds Spring's `HandlerFunction` as a handler interface. Neither changes the wire or the collector.
