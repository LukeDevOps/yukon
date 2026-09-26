---
status: accepted
---

# Hibernate's enhancement methods are not probed

Decided on 2026-09-26.

Hibernate's bytecode enhancement, at build time through its Gradle or Maven plugin or at runtime,
rewrites an entity class the adopter wrote. It adds a fixed set of methods (`$$_hibernate_getInterceptor`,
`$$_hibernate_trackChange` and about twenty-five more), plus a reader and a writer per persistent
field (`$$_hibernate_read_<field>`, `$$_hibernate_write_<field>`). It also turns every read and write
of such a field in the entity's own methods into a call to them. None of these methods is synthetic.
hibernate-core 5.6.15, 6.6.58 and 7.4.10 all define them public through ByteBuddy's `defineMethod`
(`EnhancerImpl`, `PersistentAttributeTransformer`), and every name they use starts with
`$$_hibernate_` (`EnhancerConstants`, `ExtendedSelfDirtinessTracker`). So the method tier probed
them, and an adopter who turned enhancement on got never-hit rows for code they never wrote and cannot
delete.

An **enhancement method**, any method whose name starts with `$$_hibernate_`, gets no METHOD or
BRANCH probe and is not declared by the static baseline, the way a synthetic method is left out. A
call to one is a pass-through: its callees are substituted into the caller, as for an `access$`
accessor or a bridge. The agent reports nothing about them.

## Considered options

- **Keep them and mark them with a new `GeneratedBy` value**, the way ADR 0026 keeps a data class's
  `copy`. Rejected. ADR 0026 keeps a generated method because it stands for something in the
  adopter's source, so whether it ran is a fact about their API. An enhancement method stands for
  nothing there and exists only when a build flag is on. The one signal it could carry is per-field:
  Hibernate never calls a field's reader itself, so a zero on `$$_hibernate_read_code` would mean no
  application code read that field. But a marked method is hidden from findings, so the adopter would
  never see that signal without a new finding designed for it. Without extended enhancement only the
  entity's own methods are rewritten, which in practice means the field's getter, already probed.
  Paying for a wire change in three repos and a probe on every field access, to show the adopter
  nothing, buys nothing. "Persistent field never read" is recorded in `STATUS.md` as a possible
  finding of its own, to be built from the adopter's field reads rather than from Hibernate's methods.
- **Recognising them by the enhancement interfaces the class gains as well as by name**
  (`ManagedEntity`, `PersistentAttributeInterceptable`, `SelfDirtinessTracker`, `CompositeTracker`).
  Rejected. The prefix is not a name a person gives a method. Kotlin needs backticks for it, and Java
  allows it but nobody writes it. The interface check costs a supertype walk per class and depends
  on settings for an `@Embeddable`.
- **Keeping a call to one as a verbatim edge.** Rejected. The edge names a method nothing probes,
  so it could never resolve, while a pass-through keeps whatever in-scope code the enhancement
  method does reach when the adopter's own method runs.

## Consequences

- The rule sits beside the synthetic-method rule in both places that answer "would the method tier
  probe this": `TypeMatchPolicy.methodMatcher`, which the static baseline shares, and
  `BranchSiteAnalyzer`'s own reading of the class, which decides pass-throughs within the class and
  across classes. The two have to agree.
- An entity gets the same probes whether it was enhanced or not, since enhancement adds no branch
  site to the adopter's own methods: it only turns their field reads and writes into calls.
- Under runtime enhancement the class file a loader serves for the entity is the one on disk, which
  predates the enhancer. A call from another class into the entity's enhancement method then finds
  no declaration to pass through, and the edge is dropped rather than kept under a name nothing
  probes. A bidirectional association's writer reaches the other entity this way, through its
  `$$_hibernate_read_<field>`.
- References the enhancement methods make, such as Hibernate's interceptor types, are attributed to
  the adopter's method that reaches them, as for any pass-through (ADR 0030). That is true: the
  application does use hibernate-core through them.
- Only Hibernate is covered. EclipseLink's weaving (`_persistence_*`), OpenJPA's (`pc*`) and Ebean's
  (`_ebean_*`) add methods to entities in the same way, and are recorded in `STATUS.md` until each
  has been read from its own source. OpenJPA's `pc` prefix could not be matched this way at all,
  since `pcRate()` is a name a person writes.
- The proof follows ADR 0029's Hibernate work: an in-repo Java fixture declaring the exact shapes,
  plus a one-off run of hibernate-core 7.4.10's `Enhancer` over two entities joined by a
  bidirectional `@ManyToOne`/`@OneToMany`, loaded under the agent. No probe landed on an
  enhancement method, the entities' own methods gained no branch site, the association was really
  managed (`setOrder` added the item to the order's list), and the one edge the run first left
  verbatim, `Item.setOrder` to `Order.$$_hibernate_read_items`, is the runtime-enhancement case
  above.
