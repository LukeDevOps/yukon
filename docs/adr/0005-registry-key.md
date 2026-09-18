---
status: accepted
---

# Key the probe registry by classloader, class name and layout hash

`ProbeRegistry` stores one counts array per entry, keyed by the defining classloader's identity hash, the class name, and a hash of the class's probe layout. The classloader is part of the key because JVM class identity is (loader, name), not name alone: two loaders defining a same-named class (multi-tenant app servers, OSGi, plugin hosts) must not share an array. `System.identityHashCode` is used rather than a held reference so a retired loader can be collected. The layout hash is computed over method and branch signatures in the same order slots are assigned, so a class redefined with an identical layout keeps its array and history, while a changed layout gets a fresh array rather than a merge against bytecode that wires slots differently.

## Consequences

- The hash is order sensitive on purpose. A sorted hash would treat a reordered class as unchanged and hand back an array whose slots do not match the woven bytecode, silently attributing hits to the wrong methods.
- A class is registered only once its rewrite has succeeded (ADR 0007), so one loader's failed transform never reaches the registry to be undone, and another loader's entry for the same name is untouched by it.
- Cross-restart and cross-instance identity is the collector's job, built from the manifest's class name, method name, descriptor, line and branch index. `class_id` is per instance (0011).
