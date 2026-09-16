---
status: accepted
---

# Call edges are read from bytecode at transform time, in scope only, and resolved into unreached clusters at the collector

A never-hit method is a weak finding on its own: it may be dead only because the one method that calls it is dead. The agent therefore records, for every method it probes, the in-scope methods that method's bytecode references, as *call edges* on the manifest and on the static baseline, and the collector walks them to attribute an *unreached cluster* to its root. Nothing is counted per call. An edge is a static fact read once per class, in the same pass the branch analyser already makes over every method body, so the hot path is untouched.

The callee is named as the bytecode names it: owner class, method name and descriptor, with a flag saying whether the call is virtual. Resolution is the collector's job, using each class's superclass and interfaces, which travel beside the edges. An edge resolves to the union of two lookups, either of which may find nothing: the first probed method up the owner's supertype chain, which is an inherited concrete declaration, and, when the call is virtual, every probed method with the same name and descriptor on a transitive subtype of the owner. Widening starts at the owner, not at the declaring type: an abstract interface method has no probe anywhere, so a rule that required the up-walk to succeed would drop every edge into a pure interface, the constructor-injected case the supertypes exist for; and a receiver typed as the owner can only be the owner or one of its subtypes, never a sibling under some ancestor. A type never seen stops either walk. Only callees inside the include rules are recorded. An `invokedynamic` yields an edge to the method its bootstrap handle names, so a lambda body or a method reference is reached from the method that creates it. A reference to a method with no probe of its own, a *pass-through*, contributes that method's callees to the referencing method, transitively, so a call to Kotlin's `f$default` reaches `f`. `new Foo()` is an edge to the constructor. Self-edges are dropped.

An unreached cluster is a root plus every never-hit method reachable from it whose every in-scope caller is itself in the cluster. A root is never hit and either has a caller with hits or has no in-scope caller at all; the two kinds are reported apart, because they call for different fixes. Methods of a never-loaded class join a cluster through the baseline's edges. Inline methods and the methods the collector already filters by name are not nodes. The graph is the union across in-scope instances and complete baselines, under the same scope as never-hit, and is computed at query time.

## Considered options

- Per-call dynamic edges with counts. Rejected: a thread-local caller stack on every entry and exit, exceptions included, plus a hashed counter per edge, which breaks the one-increment overhead contract and doubles the woven bytecode.
- JFR `jdk.ExecutionSample` as an opt-in overlay marking which static edges are taken. Deferred, not rejected: the wire shape leaves room for an observed marking on an edge, and it gets its own decision once real manifest sizes are known.
- Resolving virtual calls agent-side. Rejected: the transform of one class does not have the hierarchy, and guessing there would put a wrong target on the wire for good.
- Recording every callee. Rejected: in the Spring demo nine of ten call sites target the JDK, the Kotlin stdlib or Spring, none of which the collector can judge, so they add size and no attribution.
- Edges on the manifest only, or on the baseline only. Rejected: the manifest alone cannot explain a never-hit method whose only callers never load, and the baseline is opt-in.
- Making pass-throughs nodes by probing them, or letting the collector treat a probeless callee as transparent. Rejected: the first reverses ADR 0015 for forwarders; the second fails because the collector has no edges *from* a method it never received.
- A collector-side pseudo-node for `<clinit>` whose hit state is "the class has a manifest row". Rejected: a class can load without initialising, so the pseudo-node would be wrong exactly when the question is interesting. ADR 0015 gives `<clinit>` a real probe instead.

## Consequences

- A call that leaves scope and comes back in shows as no edge. Lambdas cover the common case, since the body is reached from its creator; what remains is a named class implementing a framework interface, whose never-hit method appears as an uncalled root. That root kind is the signal for whether to record out-of-scope callees later.
- Manifests grow by one entry per edge. Edges weigh one entry in the chunk caps, no per-method cap, and are not part of the layout hash. A class retransformed with different calls but the same slots shares its array, as it should.
- A pass-through is resolved within its own class, plus one cross-class case: a call to another class's Kotlin `$default` is resolved to that class's target by reading its bytes, since every cross-class call that omits an argument would otherwise be lost. Any other cross-class reference to a compiler-generated method, an `access$` accessor reached from a nested class or a callable-reference adapter class, is an edge to a method with no row, and the collector stops there.
- A lambda body's row carries the compiler's name; the collector labels it from its incoming edge rather than from a new field.
- `yukon-testkit` gains `callEdges` for the raw callees and `unreachedClusters` applying the collector's rule within one JVM, including hierarchy resolution, the same precedent as the omission findings.
