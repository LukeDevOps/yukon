---
status: accepted
---

# Creation edges, lambda bodies and source files are reported, so a collector can name hidden code

Decided on 2026-09-23. This amends ADR 0024, whose lambda bodies are labelled "from their incoming edge", and renames `ClassSupertypes`.

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
  - `body_class`: true when the class has an `EnclosingMethod` attribute, the same test ADR 0024 already uses to recognise a body class.

  `DeclaredClass` gains the same two fields, so the static baseline names never-loaded code the same way.

## Considered options

- **Decoding compiler names in the collector or UI.** Rejected: it copies the naming schemes of three compilers and several versions into a place that cannot test against them, breaks silently on the next compiler, and misreads a method a person named with a `$`. The agent holds the bytecode, and its fixtures already pin each compiler's shapes.
- **A `defined_in` field on `ProbeLocation`.** Rejected: the creating method is already the source of the edge ADR 0024 records, so a field would state the same fact twice and could disagree with the edge.
- **A `creates` boolean on `CallEdge`.** Rejected in favour of an enum, which leaves room for another kind of edge without a second field that could contradict the first.
- **Keeping the name `ClassSupertypes`.** Rejected: neither repo is released, and a message named for supertypes that carries a source file is a name that misleads every future reader.
- **Sending a path instead of a file name.** Not possible: the class file records only the file name. A path guessed from the package is often wrong for Kotlin files and multi-module builds. The collector shows the package beside the file name instead.
- **Cleaning `source_file` in the agent** (dropping `<generated>` and similar). Rejected, per ADR 0015: the agent reports what the class file says, and judging whether a value is a real file name belongs to whoever displays it.

## Consequences

- A body that a new compiler names in a shape the rule does not know reaches the collector unflagged. It still has its `CREATES` edge and its source file, so it shows under its raw name, created in its method, at its file and line. The compiler fixtures are where the new shape is found and added.
- `lambda_body` is only ever true on a method that has a probe, so the collector never needs it on a pass-through.
- A nested lambda's `CREATES` edge comes from the lambda it is written in, not from the outermost named method. That is the fact the bytecode states. Walking the chain up to a named method is the collector's job.
- The `CREATES` edges of a body class come from the method holding the `new` or the `getstatic`, as ADR 0024 already records. A body class created in two places has two creators.
- A local class with its own name also carries `EnclosingMethod`, so it reports `body_class` like an anonymous one. Telling the two apart needs the `InnerClasses` attribute, which the agent does not read. That is left until a collector needs the distinction.
- Linking an endpoint to the lambda passed as its handler is not part of this decision. `STATUS.md` keeps it as an open question: whether the registration advice can map the handler instance's hidden class to its implementation method.
