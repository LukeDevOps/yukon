---
status: accepted
---

# A dependency is listed only after its counts are delivered

The agent sends a dependency's manifest entry only once a delta send carrying its first loaded-class count has been confirmed. Each manifest also carries `dependencies_listed`. It is true once every dependency the startup listing found, and every reference mapping recorded before the listing ended, has gone out on a confirmed manifest. A collector can then judge a dependency as soon as its entry arrives, and it can tell "no dependencies" or "no absent references" apart from "not listed yet". Without this, a collector that sees the entry before the counts reads a used jar as unloaded. A test collector that sees no listing at all reads "none" on missing data.

ADR 0030 sends the listing, the counts and the reference mappings in three streams. The testkit gated on them by counting delta batches: a dependency was judged once its instance had sent two batches after the manifest that listed it. That gate assumes one delta batch per flush. A flush with more than 20,000 deltas sends several, and its manifest and delta sends run side by side. So both batches the gate counts can come from the flush before the one that carries the counts. `absentReferences()` had no gate: the agent sends no reference mapping until its listing ends, so until then the query returned an empty list.

## The rule

- **Counting.** A sweep counts the classes loaded from each dependency once the startup listing is complete (ADR 0030). A sweep that counts marks every dependency registered by then as counted in that sweep, including a jar the same sweep discovered by load.
- **Delivery of counts.** When every delta send of a flush is confirmed, every count computed after that flush's sweep has been delivered. The registry records the newest sweep whose counts are delivered in this way. A failed send delivers nothing, and the next flush tries again.
- **Holding the entry.** `computeManifestEntries` offers a dependency only once the sweep that counted it has its counts delivered. An unloaded dependency has no delta, and that is correct: a counting sweep found zero classes, and the confirmed delta sends after it prove no count is in flight.
- **Holding a mapping.** An external class that maps to a dependency is held under the same condition as that dependency's entry. The two can go out on the same manifest send. The server stores a mapping's dependency id with no foreign key and joins at read time, so the order within one send does not matter. An absent reference names no dependency, so it is never held for this reason.
- **The same flush.** A flush sends its manifest and its delta batch side by side, as before. When the delta sends are all confirmed and that makes held entries or mappings sendable, the flush then runs one more manifest send for them. The entries go out in the same flush as their counts, and the shutdown flush still delivers a listing that ended just before it. The extra send runs only on the few flushes that release something, so a flush's worst case during an outage is unchanged.
- **The flag.** `ProbeManifest.dependencies_listed` is true on a manifest when three things hold as it is built. The listing is complete. Every dependency whose discovery source is the startup classpath has been delivered on a confirmed send. Every external class recorded before the listing completed has been delivered on a confirmed send, or resolved to nothing to send. From then on every manifest carries true, like `references_recorded`. When the flag first becomes due and no confirmed manifest has carried it, the flush ends with an empty manifest that carries it, so a flush that releases the listing also delivers the flag. A listing that failed never sets it.

## Considered options

- **A flush sequence number on every payload.** A collector would then know which batches belong to one flush and could wait for the flush after the one that listed a dependency. Rejected: a failed send moves the rest of a flush into the next one, so "a batch from flush N+2 has arrived" does not prove every batch of flush N+1 has. Only the agent knows which sends were confirmed, so the agent orders them.
- **Holding the entry but no wire flag.** This fixes `dependency()`. It leaves `absentReferences()` and the list queries unable to tell "not listed yet" from "none" on an instance with no dependency jars, since that instance never sends an entry.
- **Gating only in the testkit, on the first listing to arrive.** This is the smallest change. It keeps the split-flush hole, and it waits forever on an instance with no dependency jars.
- **A timeout in the testkit.** Rejected: the testkit does not know the agent's flush interval, and a timeout that passes on a slow run reads missing data as "none".

## Consequences

- A dependency's entry arrives in the same flush as its first counts, at the earliest the flush after the listing ends, where before it could go out on the flush the listing ended in. The flush that releases the listing sends up to two more manifests than other flushes. The listing itself runs as ADR 0030 describes.
- The testkit judges a dependency once its entry has arrived and drops its batch-counting gate. Its list queries and `absentReferences()` throw until every instance heard from has sent `dependencies_listed`. A test waits for that with `awaitDependenciesListed`.
- `yukon-collector` forwards the field unchanged and logs it. `yukon-server` ignores it for now. Its statuses no longer see an entry before its counts, but it still returns an empty list for an instance whose listing has not arrived. Gating its answers on the flag is a follow-up there.
- The testkit's general "settled" wait counts delta batches in the same way. It keeps the split-flush weakness for hit totals. This ADR does not change that.
