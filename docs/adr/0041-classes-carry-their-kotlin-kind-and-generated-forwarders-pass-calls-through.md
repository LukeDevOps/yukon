---
status: accepted
---

# Classes carry their Kotlin kind, and generated forwarders pass calls through

Decided on 2026-09-25 with `yukon-server`, whose ADR 0035 holds how a file facade reads.

`DemoServerMainKt` headed tables and graph groups, but the source has top-level functions in `DemoServerMain.kt` and no class of that name. Only a guess from the `Kt` suffix could tell, and `@file:JvmName` breaks the guess. Kotlin writes the kind of every class it compiles into the `k` element of `@kotlin.Metadata`: 1 for a class, 2 for a file facade, 3 for a synthetic class, 4 for a multi-file facade and 5 for a multi-file part. A multi-file facade, from `@file:JvmMultifileClass`, holds only forwarders to the parts where the code lives, so every function showed twice.

## The design

- **The kind on the wire.** `ClassLocation` and the baseline's `DeclaredClass` carry `KotlinKind`: `NONE` for a class with no `@kotlin.Metadata`, `KOTLIN_CLASS`, `FILE_FACADE`, `MULTIFILE_FACADE`, `MULTIFILE_PART` and `SYNTHETIC`, read from the `k` element. The agent reads that one int as it reads other annotations. It decodes nothing else in the metadata, so ADR 0021's and ADR 0026's reasons against a metadata library do not apply.
- **`GeneratedBy.MULTIFILE_FACADE`.** A method of a `MULTIFILE_FACADE` class whose body only loads its arguments, calls the same-named static method of a part class, and returns is generated, recognised by bytecode shape as `DEFAULT_IMPLS` is. It keeps its probe and is never judged (ADR 0026).
- **A generated forwarder passes calls through.** A call into a `JVM_OVERLOADS`, `MULTIFILE_FACADE` or `DEFAULT_IMPLS` forwarder records edges to what the forwarder calls, as a call into `$default` or `access$` does (ADR 0024). Without this, a generated method is no graph node and a call into it resolves to nothing, so the real code behind it read as uncalled. `ENUM`, `DATA_CLASS` and `RECORD` methods are not forwarders and are unchanged.
- **Parity.** `yukon-testkit` and the stub collector read the kind and follow forwarders the same way.

## Considered options

- **Guess from the `Kt` suffix on the server.** Rejected: `@file:JvmName` and a Java class that ends in `Kt` both break it, and the fact is in the bytecode.
- **Decode `kotlin.Metadata` with a library.** Rejected, as in ADR 0026: one int element needs no library.
- **Make generated forwarders graph nodes.** Rejected: a forwarder is not code a person wrote, so it must not root or join a cluster. It only carries the edge.

## Consequences

- A wire change: one enum and two fields, published to the Buf registry, and a binding bump in `yukon-server`.
- The `@JvmOverloads` chain of ADR 0040 now reaches the full constructor or function through the forwarder.
- Protobuf scopes enum values to the package, and `GeneratedBy` already names `MULTIFILE_FACADE`. So the wire spells the kinds as Kotlin's own table does: `KOTLIN_KIND_NONE`, `KOTLIN_CLASS`, `FILE_FACADE`, `SYNTHETIC_CLASS`, `MULTIFILE_CLASS_FACADE` and `MULTIFILE_CLASS_PART`. A `kotlin.Metadata` with no `k` element reads as `KOTLIN_CLASS`, the element's default, and a `k` outside 1 to 5 as `KOTLIN_KIND_NONE`.
- kotlinc marks every multi-file part synthetic, and the type matcher turned away every synthetic class, so a part had no probes and the facade's forwarder was the only probe on the code. The type matcher takes a synthetic class whose kind is `MULTIFILE_CLASS_PART`, and the agent drops the synthetic clause from ByteBuddy's default ignore matcher so such a class reaches it. The loaded-class sweep cannot read the kind without loading an annotation type, so it still drops every synthetic class, and a part that reached no transformer is not reported.
