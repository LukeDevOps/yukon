---
status: accepted
---

# Methods carry their parameter names, generic signature and extension receiver

Decided on 2026-09-25 with `yukon-server`, whose ADR 0037 holds how a signature reads.

A Kotlin finding read `formatTotal(double, String, int)`: Java's view of `formatTotal(total: Double, currency: String, decimals: Int)`. The descriptor has no names and erases generics, and an extension function's receiver reads as a plain first parameter named `$this$shout`. The class file holds all three facts: the LocalVariableTable names each parameter, the `Signature` attribute keeps the generic types, and kotlinc names an extension receiver `$this$<function>` in the LocalVariableTable.

## The design

- **`parameter_names`.** A METHOD `ProbeLocation` and a `DeclaredMethod` carry the names of the method's declared parameters, in order, from `MethodParameters` when the class file has it and otherwise from the LocalVariableTable slots the parameters occupy. The list is empty when neither is present, as in a class built without debug info. Names are sent as the class file writes them, compiler names such as `$this$shout` or `$completion` included.
- **`generic_signature`.** The method's `Signature` attribute exactly as written, empty when the method has none.
- **`extension_receiver`.** True when the method's first parameter is a Kotlin extension receiver: its LocalVariableTable name starts with `$this$`, or is `$receiver`, the name older kotlinc gave it. It is read once here, from bytecode, so no consumer reads a compiler name.
- **No nullability.** kotlinc writes `@NotNull` and `@Nullable` only on public and protected members, so a private function's nullability lives only in `kotlin.Metadata`'s encoded payload, which ADR 0026 does not decode. The agent sends none.
- **Parity.** `yukon-testkit` and the stub collector keep the fields.

## Considered options

- **Decode `kotlin.Metadata` for names, receivers and nullability.** Rejected for ADR 0026's reasons. The class file's own attributes give everything but nullability.
- **Let the server read `$this$` from parameter names.** Rejected: the flag is a fact about the bytecode, so the agent reads it once, as it reads the lambda-body flag.

## Consequences

- A wire change: three fields on two messages, published to the Buf registry, and a binding bump in `yukon-server`.
- A class built without a LocalVariableTable shows types only.
