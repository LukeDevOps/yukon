---
status: accepted
---

# Creation edges, lambda bodies and source files are reported, so a collector can name hidden code

Decided on 2026-09-23. This amends ADR 0024, whose lambda bodies are labelled "from their incoming edge", and renames `ClassSupertypes`. Amended the same day so a body class says what kind of body it is.

A lambda body reaches the collector under the compiler's name: `main$lambda$0` from kotlinc, `lambda$main$0` from javac, `$anonfun$main$1` from scalac. Nothing on the wire says what that method is or where to find it. ADR 0024 already gives it an edge from the method that creates it, but that edge looks like any call. A person reading `main$lambda$0(HttpExchange)` at line 48 of `DemoServerMainKt` cannot tell that it is the block they passed to `createContext`, or which file holds it. Every fact needed to say so is in the bytecode the agent already reads. So the agent reports those facts, and the collector names the code from them.

## The design

- `CallEdge` gains `kind`, an enum. `CALL`, the zero value, is every edge ADR 0024 records today. `CREATES` is an edge from a method to a body it hands to someone else to run:
  - an `invokedynamic` whose bootstrap is `LambdaMetafactory` gives a `CREATES` edge to the implementation method, whether that is a lambda body or a named method passed as a reference (`this::handle`);
  - a `new` or a `getstatic INSTANCE` of a body class gives a `CREATES` edge to each probed method the body class declares, the constructor aside. The constructor edge stays a `CALL`, as the constructor is called there.

  A `CREATES` edge reaches its target for every rule a `CALL` does, since a created body can only run after its creator ran. `kind` changes how the edge is named and drawn, never what it reaches. `virtual` is unrelated and stays as it is.
- `CallEdge` gains `captured_count`: on a `CREATES` edge from an `invokedynamic`, the number of the target descriptor's leading parameters that are captured values rather than the functional interface's own parameters. It is read from the call site's `invokedType`. A receiver bound by a reference to an instance method is not counted, since it is not in the target's parameter list. On every other edge it is 0.
- `ProbeLocation` and `DeclaredMethod` gain `lambda_body`. It is true for a method when an `invokedynamic` in its own class names it as the implementation and its name is one a compiler gives a body the source never named:
  - javac: `lambda$…`;
  - scalac: `$anonfun$…`, except the `$adapted` forwarders;
  - kotlinc: `…$lambda$N`.

  kotlinc does not mark its bodies synthetic, so for Kotlin the name is part of the rule. It lives beside `isProbedLambdaBody`, and the compiler fixtures pin it for each supported compiler version. A named method passed by reference fails the name test and stays unflagged.
- `ClassSupertypes` is renamed `ClassLocation`, and `ProbeManifest.class_supertypes` becomes `class_locations`. It is sent once per class and carries more than supertypes:
  - `source_file`: the class file's `SourceFile` attribute exactly as it appears (`DemoServerMain.kt`), empty when the class has none;
  - `body_kind`, an enum that says what kind of body class it is, or `NONE`;
  - `source_name`: the name the source gave a local class (`Local` for `Foo$1Local`), empty for every other kind.

  `DeclaredClass` gains the same three fields, so the static baseline names never-loaded code the same way.
- A class is a body class when it has an `EnclosingMethod` attribute, the test ADR 0024 already uses. Its `body_kind` is read from facts the class file states, checked in this order:
  - `LAMBDA_CLASS`: the superclass is `kotlin.jvm.internal.Lambda`, `kotlin.coroutines.jvm.internal.SuspendLambda` or `kotlin.coroutines.jvm.internal.RestrictedSuspendLambda`, which kotlinc uses for a lambda compiled to a class and for every suspend lambda;
  - `REFERENCE`: the superclass is one of the Kotlin runtime's function or property reference bases (`FunctionReference`, `FunctionReferenceImpl`, `AdaptedFunctionReference`, and the `PropertyReference` family in `kotlin.jvm.internal`);
  - `LOCAL_CLASS`: the class's own entry in its `InnerClasses` attribute has a name, which becomes `source_name`;
  - `OBJECT_EXPRESSION`: that entry has no name and the class carries a `kotlin.Metadata` annotation;
  - `ANONYMOUS_CLASS`: that entry has no name and there is no `kotlin.Metadata`, or the class has no `InnerClasses` entry for itself at all.

  The agent reads the `InnerClasses` attribute for this. The superclass names are the Kotlin runtime's stable base classes, and the kotlinc and javac fixtures pin each kind.

## Considered options

- **Decoding compiler names in the collector or UI.** Rejected: it copies the naming schemes of three compilers and several versions into a place that cannot test against them, breaks silently on the next compiler, and misreads a method a person named with a `$`. The agent holds the bytecode, and its fixtures already pin each compiler's shapes.
- **A `defined_in` field on `ProbeLocation`.** Rejected: the creating method is already the source of the edge ADR 0024 records, so a field would state the same fact twice and could disagree with the edge.
- **A `creates` boolean on `CallEdge`.** Rejected in favour of an enum, which leaves room for another kind of edge without a second field that could contradict the first.
- **A `body_class` boolean.** Rejected: every body class would read "anonymous class", which is wrong for a suspend lambda, a function reference and a named local class. The kinds are each stated by the class file, so the agent can report them exactly. An enum also takes a new shape as a new value, not as a combination of flags.
- **Keeping the name `ClassSupertypes`.** Rejected: neither repo is released, and a message named for supertypes that carries a source file is a name that misleads every future reader.
- **Sending a path instead of a file name.** Not possible: the class file records only the file name. A path guessed from the package is often wrong for Kotlin files and multi-module builds. The collector shows the package beside the file name instead.
- **Cleaning `source_file` in the agent** (dropping `<generated>` and similar). Rejected, per ADR 0015: the agent reports what the class file says, and judging whether a value is a real file name belongs to whoever displays it.

## Consequences

- A body that a new compiler names in a shape the rule does not know reaches the collector unflagged. It still has its `CREATES` edge and its source file, so it shows under its raw name, created in its method, at its file and line. The compiler fixtures are where the new shape is found and added.
- `lambda_body` is only ever true on a method that has a probe, so the collector never needs it on a pass-through.
- A nested lambda's `CREATES` edge comes from the lambda it is written in, not from the outermost named method. That is the fact the bytecode states. Walking the chain up to a named method is the collector's job.
- The `CREATES` edges of a body class come from the method holding the `new` or the `getstatic`, as ADR 0024 already records. A body class created in two places has two creators.
- A body class whose shape the rules do not know, such as one extending a new Kotlin runtime base class, still has `EnclosingMethod`, so it reports `ANONYMOUS_CLASS`. That label is loose but true, and the missing fixture shows the gap.
- Linking an endpoint to the lambda passed as its handler is not part of this decision. `STATUS.md` keeps it as an open question: whether the registration advice can map the handler instance's hidden class to its implementation method.
