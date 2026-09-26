---
status: accepted
---

# Creation edges, lambda bodies and source files are reported, so a collector can name hidden code

Decided on 2026-09-23. This amends ADR 0024, whose lambda bodies are labelled "from their incoming edge", and renames `ClassSupertypes`. Amended the same day so a body class says what kind of body it is, and again so a body class the agent does not probe is a pass-through.

A lambda body reaches the collector under the compiler's name: `main$lambda$0` from kotlinc, `lambda$main$0` from javac, `$anonfun$main$1` from scalac. Nothing on the wire says what that method is or where to find it. ADR 0024 already gives it an edge from the method that creates it, but that edge looks like any call. A person reading `main$lambda$0(HttpExchange)` at line 48 of `DemoServerMainKt` cannot tell that it is the block they passed to `createContext`, or which file holds it. Every fact needed to say so is in the bytecode the agent already reads. So the agent reports those facts, and the collector names the code from them.

## The design

- `CallEdge` gains `kind`, an enum. `CALL`, the zero value, is every edge ADR 0024 records today. `CREATES` is an edge from a method to a body it hands to someone else to run:
  - an `invokedynamic` whose bootstrap is `LambdaMetafactory` gives a `CREATES` edge to the implementation method, whether that is a lambda body or a named method passed as a reference (`this::handle`);
  - a `new` or a `getstatic INSTANCE` of a body class gives a `CREATES` edge to each probed method the body class declares, the constructor aside. The constructor edge stays a `CALL`, as the constructor is called there.

  A `CREATES` edge reaches its target for every rule a `CALL` does, since a created body can only run after its creator ran. `kind` changes how the edge is named and drawn, never what it reaches. `virtual` is unrelated and stays as it is.
- `CallEdge` gains `captured_count`: on a `CREATES` edge from an `invokedynamic`, the number of the target descriptor's leading parameters that are captured values rather than the functional interface's own parameters. It is read from the call site's `invokedType`. A receiver bound by a reference to an instance method is not counted, since it is not in the target's parameter list. On every other edge it is 0.
- `ProbeLocation` and `DeclaredMethod` gain `lambda_body`. It is true for a method when an `invokedynamic` in its own class names it as the implementation, directly or through a same-class pass-through such as the `$adapted` forwarder scalac puts in front of a body that takes or returns a primitive, and its name is one a compiler gives a body the source never named (a body Scala 3 lifted out of a nested class is marked by its name alone; see the consequences):
  - javac: `lambda$…`;
  - scalac: `$anonfun$…`, except the `$adapted` forwarders, and in Scala 3 `<owner>$$anonfun$N` (see the consequences);
  - kotlinc: `…$lambda$N`.

  kotlinc does not mark its bodies synthetic, so for Kotlin the name is part of the rule. It lives beside `isProbedLambdaBody`, and the compiler fixtures pin it for each supported compiler version. A named method passed by reference fails the name test and stays unflagged.
- `ClassSupertypes` is renamed `ClassLocation`, and `ProbeManifest.class_supertypes` becomes `class_locations`. It is sent once per class and carries more than supertypes:
  - `source_file`: the class file's `SourceFile` attribute exactly as it appears (`DemoServerMain.kt`), empty when the class has none;
  - `body_kind`, an enum that says what kind of body class it is, or `NONE`;
  - `source_name`: the name the source gave a local class (`Local` for `Foo$1Local`), empty for every other kind.

  `DeclaredClass` gains the same three fields, so the static baseline names never-loaded code the same way.
- A class is a body class when it has an `EnclosingMethod` attribute, the test ADR 0024 already uses. Its `body_kind` is read from facts the class file states, checked in this order:
  - `LAMBDA_CLASS`: the superclass is `kotlin.jvm.internal.Lambda`, `kotlin.coroutines.jvm.internal.SuspendLambda` or `kotlin.coroutines.jvm.internal.RestrictedSuspendLambda`, which kotlinc uses for a lambda compiled to a class and for every suspend lambda;
  - `LOCAL_CLASS`: the class's own entry in its `InnerClasses` attribute has a name, which becomes `source_name`;
  - `OBJECT_EXPRESSION`: that entry has no name and the class carries a `kotlin.Metadata` annotation;
  - `ANONYMOUS_CLASS`: that entry has no name and there is no `kotlin.Metadata`, or the class has no `InnerClasses` entry for itself at all.

  The agent reads the `InnerClasses` attribute for this. The superclass names are the Kotlin runtime's stable base classes, and the kotlinc and javac fixtures pin each kind. The agent matches them by their path below the top-level package, not by a literal: `shadowJar` relocates every string in the agent's own classes that starts with `kotlin`, the bare word included. A test runs the rule from the shaded jar.
- A body class the agent does not probe is a pass-through, since an edge into it would name a method with no probe. The analyser asks the type matcher's own question, `TypeMatchPolicy.isTurnedAwayByShape`, of the class's bytes, so the two cannot disagree. Three cases reach it:
  - every function and property reference class, which kotlinc marks synthetic;
  - the `$sam$` wrapper, also synthetic, that the fixtures show for a `fun interface` constructor reference;
  - a suspend function's own continuation, whose superclass is `ContinuationImpl` or `RestrictedContinuationImpl`.

  A candidate for any method of such a class adds no edge to the class. That method's own callees take its place. When the candidate is the constructor or `<clinit>`, the callees of every other method the class declares take their place too, as `CREATES` edges. So the creator's `CREATES` edge goes to the referenced function or accessor, and there is no `REFERENCE` kind. `val f: (Int) -> Int = ::twice` gives `CREATES twice`, the same edge a Java `this::twice` gives. A property reference gives a `CREATES` edge to its getter, and to its setter when it is mutable. A continuation's `invokeSuspend` calls back into the suspend function that created it, so the edge it gives is a self-edge and is dropped. When the class's bytes cannot be read, the creator keeps a `CALL` edge to its constructor, as for any class the agent cannot read.

## Considered options

- **Decoding compiler names in the collector or UI.** Rejected: it copies the naming schemes of three compilers and several versions into a place that cannot test against them, breaks silently on the next compiler, and misreads a method a person named with a `$`. The agent holds the bytecode, and its fixtures already pin each compiler's shapes.
- **A `defined_in` field on `ProbeLocation`.** Rejected: the creating method is already the source of the edge ADR 0024 records, so a field would state the same fact twice and could disagree with the edge.
- **A `creates` boolean on `CallEdge`.** Rejected in favour of an enum, which leaves room for another kind of edge without a second field that could contradict the first.
- **Instrumenting reference classes so they arrive as `REFERENCE`.** Rejected: it labels adapter code the person never wrote, and the referenced function still has no creation edge, since the reference class's `invoke` sits between the two.
- **A `body_class` boolean.** Rejected: every body class would read "anonymous class", which is wrong for a suspend lambda, an object expression and a named local class. The kinds are each stated by the class file, so the agent can report them exactly. An enum also takes a new shape as a new value, not as a combination of flags.
- **Keeping the name `ClassSupertypes`.** Rejected: neither repo is released, and a message named for supertypes that carries a source file is a name that misleads every future reader.
- **Sending a path instead of a file name.** Not possible: the class file records only the file name. A path guessed from the package is often wrong for Kotlin files and multi-module builds. The collector shows the package beside the file name instead.
- **Cleaning `source_file` in the agent** (dropping `<generated>` and similar). Rejected, per ADR 0015: the agent reports what the class file says, and judging whether a value is a real file name belongs to whoever displays it.

## Consequences

- A body that a new compiler names in a shape the rule does not know reaches the collector unflagged. It still has its `CREATES` edge and its source file, so it shows under its raw name, created in its method, at its file and line. The compiler fixtures are where the new shape is found and added.
- `lambda_body` is only ever true on a method that has a probe, so the collector never needs it on a pass-through.
- Amended on 2026-09-26, from the Scala fixtures run against the stack. Scala 3 names a body after the method that owns it: `LambdaLift.newName` in the 3.3.4 compiler prefixes a lambda whose owner is a method with `<owner>$`, giving `label$$anonfun$1`, and `nested$$anonfun$1$$anonfun$1` for a lambda inside it. The owner is `$init$` for a lambda in a class-body `val` or statement, since the `Constructors` phase moves those into the constructor before `LambdaLift` runs; only a lambda owned by a `val` local to a method keeps `$anonfun$N`. The first rule knew only the Scala 2 prefix, which had two effects. A lambda the source wrote in a method or a class body is synthetic (`Desugar` gives it `Artifact`, which the backend writes as `ACC_SYNTHETIC`), so it was neither probed nor flagged, and its branches went uncounted. A closure the compiler makes itself, such as a by-name argument's (`newAnonFun`, `Synthetic` without `Artifact`), is not synthetic, so it was probed but unflagged and showed under its raw name. The rule accepts `<owner>$$anonfun$N` as well. A by-name argument's closure counts as a lambda body, as Scala 2's already did: its code runs exactly when the callee evaluates the argument, and it is created at the call site like any lambda. Scala 3 also moves a lambda that does not capture `this` out of a nested class into the top-level class (`LambdaLift.liftLocals`), and `ensureNotPrivate` gives it an expanded name, the encoded name of the class it was written in, `$$`, one `_$` per term in its original owner chain, then its own name: `com$acme$Outer$Inner$$_$bump$$anonfun$1` on `Outer`. The `invokedynamic` that creates it stays in `Outer$Inner`, so the same-class test can never see it; `scala3-compiler_3-3.3.4.jar` holds 934 such bodies among about 7290. When the lambda boxes a primitive, only the boxing bridge the nested class calls is made public and expanded (`…$Inner$$_$show$$anonfun$adapted$1`); the body behind it stays private as `show$$anonfun$1`, and the compiler jar has 45 such bridges. In a Scala class, a method with the expanded name, body or bridge, is treated as though an `invokedynamic` in the class named it, so the existing walk flags the body directly or through the bridge. No `invokedynamic` is needed there: `$$_$` is the compiler's filler and not a name a person writes, where kotlinc's plain `main$lambda$0` is, which is why the test exists. Flagging it when the nested class loads was set aside: the flag rides on the body's class record, which is sent once and can go out long before the nested class loads. The boxing forwarder never gets the flag itself: it fails the body-name rule and is a bridge. JaCoCo 0.8.13's `SyntheticFilter`, which ADR 0015's allow-list extends, has the same gap for Scala 3.
- A Kotlin reference to a Kotlin function type (`::f`) compiles to a synthetic `FunctionReferenceImpl` class under kotlinc 2.2, not to an `invokedynamic`. The creator reaches `f` through that class as a pass-through. A SAM conversion such as `IntUnaryOperator(::twice)` gives an `invokedynamic` to the named method instead.
- A `fun interface` constructor reference (`::Op`) wraps the function value in a `$sam$` class. The wrapper calls only the function value it holds, so the walk through both classes reaches nothing in scope, and the creator gets no edge from them.
- A nested lambda's `CREATES` edge comes from the lambda it is written in, not from the outermost named method. That is the fact the bytecode states. Walking the chain up to a named method is the collector's job.
- The `CREATES` edges of a body class come from the method holding the `new` or the `getstatic`, as ADR 0024 already records. A body class created in two places has two creators.
- A body class whose shape the rules do not know, such as one extending a new Kotlin runtime base class, still has `EnclosingMethod`, so it falls through to its `InnerClasses` entry. From kotlinc that gives `OBJECT_EXPRESSION`, since the class has an unnamed entry and `kotlin.Metadata`. That label is loose but true, and the missing fixture shows the gap.
- Linking an endpoint to the lambda passed as its handler is not part of this decision. `STATUS.md` keeps it as an open question: whether the registration advice can map the handler instance's hidden class to its implementation method.
