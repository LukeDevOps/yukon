---
status: accepted
---

# Key the probe registry by classloader, class name and layout hash

`ProbeRegistry` stores one counts array per entry, keyed by the defining classloader's identity hash, the class name, and a hash of the class's probe layout. The classloader is part of the key because JVM class identity is (loader, name), not name alone: two loaders defining a same-named class (multi-tenant app servers, OSGi, plugin hosts) must not share an array. `System.identityHashCode` is used rather than a held reference so a retired loader can be collected. The layout hash is computed over method and branch signatures in the same order slots are assigned, so a second registration under the same loader identity and name with an identical layout gets the same array and history, while a changed layout gets a fresh array rather than a merge against bytecode that wires slots differently.

## Consequences

- The hash is order sensitive on purpose. A sorted hash would treat a reordered class as unchanged and hand back an array whose slots do not match the woven bytecode, silently attributing hits to the wrong methods.
- A class is registered only once its rewrite has succeeded (ADR 0007), so one loader's failed transform never reaches the registry to be undone, and another loader's entry for the same name is untouched by it.
- Cross-restart and cross-instance identity is the collector's job, built from the manifest's class name, method name, descriptor, line and branch index. `class_id` is per instance (0011).
- Redefining an already-loaded class (debugger HotSwap, another agent's `redefineClasses`) is not supported, and the layout hash never comes into play for it. The method tier ignores a class being redefined: the woven class's probe field is missing from the new bytes, so the JVM refuses the redefinition, and a woven `<clinit>` never runs again to fetch an array for changed bytecode. Re-weaving would fail on the field the loaded class already has and report as skipped a class whose probes were already sent. Retransformation by another agent never reaches the method tier, whose transformer is not registered as retransform-capable, so the JVM keeps the woven bytes. Settled 2026-10-03 in a review.
- Amended on 2026-10-03 by ADR 0053: both tiers register as retransformation-capable, so another agent's retransformation of a woven class now reaches the method tier, which re-weaves it to identical bytes instead of ignoring it. Redefinition is unchanged and still unsupported.
