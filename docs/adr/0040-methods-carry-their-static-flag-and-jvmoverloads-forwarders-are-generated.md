---
status: accepted
---

# Methods carry their static flag, and `@JvmOverloads` forwarders are generated

Decided on 2026-09-25 with `yukon-server`, whose ADR 0034 holds the design these facts serve: class findings (never initialised, never instantiated) in place of `<clinit>` rows and lone constructor rows, and a constructor listed only as an unused overload.

The server could not tell a class meant to have instances from a static-only holder, since no probe said whether a method is static. A class like `Utils` with a private constructor would read as "never instantiated". And `@JvmOverloads` compiles a Kotlin constructor or function with default arguments into extra non-synthetic overloads that forward to the `$default` twin. Such an overload that never ran would read as an unused overload the source never declared.

## The design

- **`static` on the wire.** A METHOD `ProbeLocation` and a `DeclaredMethod` carry `static`, true when the method has `ACC_STATIC`. It is false for a constructor and for `<clinit>`'s probe, whose meaning the server takes from its name. It is a named fact, as `inline` and `lambda_body` are, never a raw access-flags value.
- **`GeneratedBy.JVM_OVERLOADS`.** A constructor or method whose body only loads its arguments, adds the default constants and mask, and calls its own class's `$default` twin (a `<init>` ending in `DefaultConstructorMarker`, or a static `name$default`) is marked `JVM_OVERLOADS`. The recognition reads the bytecode shape, as `DEFAULT_IMPLS` does, and never an annotation. A generated method is probed and never judged (ADR 0026).
- **No "declared in source" flag.** Server ADR 0034 lists a constructor only as an unused overload. javac's implicit constructor exists only when a class declares none, so it is never an overload, and Kotlin's synthetic default-argument constructor is not probed. With forwarders marked, every constructor that can become an overload row was written in the source.
- **Parity.** `yukon-testkit` and the stub collector apply server ADR 0034's class findings and folds within one JVM, as they apply ADR 0039's cluster rule.

## Considered options

- **Send raw access flags.** Rejected: the server would decode JVM bits, and the wire names facts.
- **Decode `kotlin.Metadata` to say which constructors the source declared.** Rejected: ADR 0026 keeps a metadata library off the transform path, and javac's constructors would still be a guess.
- **Read the `@JvmOverloads` annotation.** Rejected: it is on the source declaration, not on the generated overloads, which carry no mark of their own.

## Consequences

- A wire change: one field on two messages and one enum value, published to the Buf registry, and a binding bump in `yukon-server`.
- A `@JvmOverloads` function's generated overloads leave the never-hit list too, which is right, since nobody wrote them.
- A hand-written secondary constructor or overload that calls the full one with named arguments and leaves some out, such as `constructor(a: Int, r: Int) : this(amount = a, rounding = r)`, compiles to the same bytecode and is marked too, so it is never judged. The bytecode cannot tell the two apart. The miss hides code rather than inventing a finding.
- A kotlinc that compiled a forwarder differently, such as by pushing a real default value, would leave it unmarked, and it would read as never hit. This is the same trade-off `DEFAULT_IMPLS` makes.
