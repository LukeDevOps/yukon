---
status: accepted
---

# Initialise probe arrays through a bootstrap-resident holder

The `:bootstrap` module contains one Java class, `YukonProbeArrays`. The agent embeds it in its jar as `META-INF/yukon/bootstrap-jar.bin` and appends it to the bootstrap classloader at `premain`. Each instrumented class gets a `<clinit>` prelude that fills `$yukonProbeCounts` with one call to `YukonProbeArrays.resolve(className, layoutHash, probeCount, classLoader)`, every argument a constant. The agent plugs its registry in through `YukonProbeArrays.install(Resolver)`. Because the holder sits on the bootstrap loader, a class defined by any loader can reach it from its own initializer.

## Considered options

Setting a private non-final field reflectively after load, through ByteBuddy's `LoadedTypeInitializer.ForStaticField`. This cannot work for interfaces: the JVM allows only public static final fields on an interface, and the JDK refuses reflective writes to static finals, so every interface with a default or static method failed to transform and was skipped. The holder covers classes and interfaces with one mechanism. ByteBuddy's `InitializationStrategy` is `NoOp`, so nothing runs after load and ByteBuddy does not inject its `Nexus` class through `Unsafe`.

## Consequences

- A miss in the holder (no resolver installed, nothing registered under that key) returns a fresh array of the right size and logs once. The worst case is an uncounted class, never an exception inside an application method.
- If the holder cannot be installed at all (a read-only filesystem, a full `java.io.tmpdir`), the agent logs at ERROR and disables itself entirely, exporter included. A missing instance is visible at the collector; a zero-hit instance is not.
- HotSpot prints a one-line CDS warning at startup about the appended bootstrap classpath. OpenTelemetry's agent causes the same one.
- The shadow plugin explodes any embedded `.jar` it copies, which is why the resource has a `.bin` suffix. The `verifyAgentJar` task fails the build if the resource is missing or a holder class is present loose.
