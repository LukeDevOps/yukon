---
status: accepted
---

# Branch outcomes carry a branch key derived from their own condition, and none when it could be ambiguous

`branch_index` counts every outcome of every site in a class in bytecode order, so one new conditional in an early method renumbers every later branch in the class. That is fine for its job, naming a slot within one build, but a consumer that dates a branch across builds needs a name that survives edits elsewhere. `yukon-server` keeps one row of dates per location for the whole service and cannot key a branch on `branch_index`: the row would carry an old date onto a new branch and report it dead for years. So each kept branch outcome gets a **branch key**, sent as `optional string branch_key = 18` on `ProbeLocation`, beside `branch_index` and not replacing it.

The key is a 128-bit hex digest of the class name, method name, method descriptor, the site's condition fingerprint and the outcome. The fingerprint is the instruction sequence from the last point in the method where the operand stack was empty up to the jump or switch. It includes opcodes in order, constants, the owner, name and descriptor of field and method references, and the name of each local variable an instruction reads or writes, taken from the method's `LocalVariableTable`. It leaves out local-variable slot numbers, labels, line numbers and jump targets. A variable name survives a local added or removed elsewhere in the method, where a slot number would shift; renaming the variable gives a new key. A method without a `LocalVariableTable`, or a slot the table does not cover at that instruction, fingerprints the variable by its load or store opcode alone, and the collision rule below keeps that safe. A switch's fingerprint covers only the instructions that compute the switch value, never its case keys. An inlined copy adds its origin class and leaves out its SMAP origin line. The outcome is taken or fall-through for a conditional, and the case key value or default for a switch, so adding a case leaves every other case's key alone. Stack depth is computed from each instruction's stack effect inside the existing visitor, since the analyser reads with `SKIP_FRAMES` and has no frames to use.

When two tracked sites in one method share a fingerprint, none of them gets a key. Dropped sites count, so whether a kept site has a key depends on its bytecode alone and not on scope configuration. Any case the analyser cannot fingerprint with confidence also gets no key. A consumer treats a missing key as an outcome it has never seen: it keeps capped, per-build dates for it and never joins it to another outcome's history.

## Considered options

- Redefining `branch_index` as the stable name. Rejected on the merits, not on cost: `branch_index` must be present for every outcome, dense and stable against drop rules (ADR 0025), because slots and per-instance rows rely on it. A key must be allowed to be absent, and it is a digest, not an ordinal.
- A method-local ordinal. Rejected: it survives edits in other methods, but a conditional added earlier in the same method shifts it with no way to tell, and the shifted site inherits another site's history.
- Numbering sites that share a fingerprint by order of appearance. Rejected for the same reason: one more identical check added earlier moves the old sites onto each other's history.
- Leaving local variables out entirely, with no slot and no name. Rejected: `if (flag)` and `if (done)` on two boolean locals, and each half of `if (a && b)`, would all fingerprint as a load and a jump and lose their keys, which in ordinary Kotlin is a large share of branches.
- Slot numbers. Rejected: a local added earlier in the method shifts every later slot, so keys would churn on edits that do not touch the condition.
- A readable key. Rejected: consumers need only equality, and an opaque key lets a later ADR change the derivation without breaking a contract. The readable fields (method, line, `branch_index`) are already on the wire.
- Normalising known compiler variants so keys survive a Kotlin or javac upgrade. Rejected: it means following every compiler's output forever, and an upgrade that changes a condition's bytecode gives new keys, which is the safe direction.

## Consequences

- An edit to the condition itself, a method rename, a signature change or a compiler upgrade that changes the bytecode all give new keys. Consumers restart those outcomes' dates. That is the price of never inventing history.
- Two identical conditions in one method share a fingerprint, as do two copies of one inline call. They lose their keys and fall back to capped dates. Without a `LocalVariableTable`, `if (a > 0)` and `if (b > 0)` on two locals of one type also collide, so stripping debug info costs keys but never correctness.
- A condition that holds branches of its own, such as a ternary, an elvis or the earlier operand of `&&`, empties the operand stack partway through. Its window starts at the last empty-stack point before the site in bytecode order, so an edit to the part of the expression before that point can keep the key. The window is still a pure function of the method's bytecode, so equal bytecode always gives equal keys.
- The analyser computes stack depth without frames. At a label reached only by a jump it has not seen yet, such as a loop body placed after a `GOTO` to its test, the depth is unknown and the sites there get no key until the stack is next known to be empty.
- Fingerprints come from a second read of the class bytes beside the analyser's own pass, so the agent reads each probed class one more time at load.
- The key is not part of the layout hash. The layout hash names a slot layout within one build, and the key does not change it.
- ADR 0005's statement that cross-instance identity is built from class, method, descriptor, line and branch index holds for methods. For branch outcomes, consumers join on the branch key.
- The collector passes the field through without a bindings change, since Go protobuf keeps unknown fields when it re-marshals.
