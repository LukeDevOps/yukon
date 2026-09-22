---
status: accepted
---

# Branch outcomes carry a branch key derived from their own condition, and none when it could be ambiguous

`branch_index` counts every outcome of every site in a class in bytecode order, so one new conditional in an early method renumbers every later branch in the class. That is fine for its job, naming a slot within one build, but a consumer that dates a branch across builds needs a name that survives edits elsewhere. `yukon-server` keeps one row of dates per location for the whole service and cannot key a branch on `branch_index`: the row would carry an old date onto a new branch and report it dead for years. So each kept branch outcome gets a **branch key**, sent as `optional string branch_key = 18` on `ProbeLocation`, beside `branch_index` and not replacing it.

The key is a 128-bit hex digest of the class name, method name, method descriptor, the site's condition fingerprint and the outcome. The fingerprint is the instruction sequence from the last point in the method where the operand stack was empty up to the jump or switch. It includes opcodes in order, constants, and the owner, name and descriptor of field and method references. It leaves out local-variable slot numbers, labels, line numbers and jump targets. A switch's fingerprint covers only the instructions that compute the switch value, never its case keys. An inlined copy adds its origin class and leaves out its SMAP origin line. The outcome is taken or fall-through for a conditional, and the case key value or default for a switch, so adding a case leaves every other case's key alone. Stack depth is computed from each instruction's stack effect inside the existing visitor, since the analyser reads with `SKIP_FRAMES` and has no frames to use.

When two tracked sites in one method share a fingerprint, none of them gets a key. Dropped sites count, so whether a kept site has a key depends on its bytecode alone and not on scope configuration. Any case the analyser cannot fingerprint with confidence also gets no key. A consumer treats a missing key as an outcome it has never seen: it keeps capped, per-build dates for it and never joins it to another outcome's history.

## Considered options

- Redefining `branch_index` as the stable name. Rejected on the merits, not on cost: `branch_index` must be present for every outcome, dense and stable against drop rules (ADR 0025), because slots and per-instance rows rely on it. A key must be allowed to be absent, and it is a digest, not an ordinal.
- A method-local ordinal. Rejected: it survives edits in other methods, but a conditional added earlier in the same method shifts it with no way to tell, and the shifted site inherits another site's history.
- Numbering sites that share a fingerprint by order of appearance. Rejected for the same reason: one more identical check added earlier moves the old sites onto each other's history.
- A readable key. Rejected: consumers need only equality, and an opaque key lets a later ADR change the derivation without breaking a contract. The readable fields (method, line, `branch_index`) are already on the wire.
- Normalising known compiler variants so keys survive a Kotlin or javac upgrade. Rejected: it means following every compiler's output forever, and an upgrade that changes a condition's bytecode gives new keys, which is the safe direction.

## Consequences

- An edit to the condition itself, a method rename, a signature change or a compiler upgrade that changes the bytecode all give new keys. Consumers restart those outcomes' dates. That is the price of never inventing history.
- `if (a > 0)` and `if (b > 0)` on two locals of the same type share a fingerprint, as do two copies of one inline call in a method. They lose their keys and fall back to capped dates.
- The key is not part of the layout hash. The layout hash names a slot layout within one build, and the key does not change it.
- ADR 0005's statement that cross-instance identity is built from class, method, descriptor, line and branch index holds for methods. For branch outcomes, consumers join on the branch key.
- The collector passes the field through without a bindings change, since Go protobuf keeps unknown fields when it re-marshals.
