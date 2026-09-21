---
status: accepted
---

# Classes a framework generates at runtime are left alone, recognised by the generator's own naming

A CGLIB proxy is named after the class it proxies, so it lands in the adopter's own package and no
`includePackages` rule can tell it apart. One `@Bean`-bearing `@Configuration` class in
`demo-spring` made Spring generate three: the enhanced subclass and two fast-class helpers. Every
one matched the type matcher, was woven, registered, and reported. The demo's manifest went from 15
probes to 289, its never-hit list from 4 rows to 230, and its unreached clusters from 4 to 16. The
report read "dead: 81.3%", and the 220 new never-hit rows all named methods with names like
`CGLIB$BIND_CALLBACKS` at line -1, in a class with no source file, that nobody can delete.

That is the failure mode ADR 0007 exists to rule out, arriving from the other side: not a real class
missing from the report, but a report so full of generated code that the real findings are four rows
in two hundred and thirty. The three classes also tripped the static-scan blind-spot warning ADR
0014 added, which is meant to tell an adopter their deployment loads code the scan cannot see. A
proxy has no `.class` file anywhere, so it fires that warning every time, and a diagnostic that
cries wolf on every Spring application is worth less than no diagnostic.

A class generated at runtime is therefore **not instrumented**, recognised by the markers the
generator puts in the name. The class it proxies is instrumented as usual, which is where the signal
actually lives: a proxy reaches the method it overrides through `super`, so the real class's probes
fire. `demo-spring`'s `PricingConfiguration` is fully hit with the rule in place.

## Considered options

- **Leaving them in.** Rejected on the numbers above. There is also nothing to do with the rows: a
  generated class's name carries a counter or a hash from the order its generator happened to
  produce it in, so two instances of one service disagree about the name and a collector can never
  merge them into "never hit across the fleet", which is the claim this project exists to make.
- **Recognising them structurally, from the bytecode, rather than by name.** Rejected: every
  structural signal collides with something real. Spring's CGLIB classes are not `ACC_SYNTHETIC`,
  and they do carry a `SourceFile` attribute, reading `<generated>` (confirmed with `javap` over the
  classes Spring generated for the demo, dumped with `-Dcglib.debugLocation`). The one thing they
  genuinely lack is line numbers, and that is exactly what a class compiled without debug info lacks
  too, which ADR 0026 decided to keep and label rather than drop. A `SourceFile` value that does not
  look like a file name would work for CGLIB, but it is a name rule wearing a different hat, and a
  narrower one: ByteBuddy and JDK proxies write no `SourceFile` at all.
- **Turning away any class name containing `$$`.** Rejected: kotlinc puts `$$` in the name of the
  class it generates for a lambda passed to an inlined function
  (`OrdersKt$special$$inlined$sortedBy$1`), and that class holds the adopter's own body. A test pins
  it.
- **Asking the classloader whether a `.class` resource exists for the name.** This is the rule with
  the right meaning, since what makes a generated class unreportable is that no artifact carries its
  name. Rejected for v1: it fails the wrong way. A loader that serves bytes without exposing them as
  resources, which an app server or an OSGi container may do, would make the agent silently skip the
  adopter's real code, and it puts a resource lookup on the class-loading path of every class the
  agent instruments. A name rule that misses an unknown generator leaves visible noise; this one
  hides real findings.
- **Skipping them and reporting the skip**, the way ADR 0007 reports a class ByteBuddy cannot safely
  redefine. Rejected: those two are different claims. A skipped class is one the agent wanted to
  instrument and could not, which an adopter should know about; a generated class holds no code the
  adopter wrote, so there is nothing to tell them. This follows ADR 0025's coroutine-continuation
  classes, which are excluded and not reported, rather than ADR 0007's.

## Consequences

- The rule lives in `TypeMatchPolicy.isRuntimeGenerated`, applied in `typeNameMatcher`, so the live
  matcher and the static baseline scanner share it, as they must for every other shape rule. The
  scanner walks `.class` files and can never meet a generated class, so this costs it nothing.
- `LoadedClassSweep` mirrors it, as it already mirrors the synthetic flag and the
  coroutine-continuation check. Without that, the forward direction of ADR 0027's sweep would report
  every generated class as one no transformer saw, which is the blind-spot false alarm again in a
  different bucket.
- The markers are `$$SpringCGLIB$$` (Spring 6 and 7) and `BySpringCGLIB$$` (Spring 5.3, which tags
  the generating class's simple name in front of it: `$$EnhancerBySpringCGLIB$$`,
  `$$FastClassBySpringCGLIB$$`). Both were read out of `SpringNamingPolicy` and `DefaultNamingPolicy`
  in spring-core 5.3.39, 6.2.19 and 7.0.9, not recalled. `endpoints-spring-webmvc` supports 5.3, so
  both spellings have to be covered.
- Only Spring is covered, because only Spring has been confirmed against its own source and run
  end to end here. Hibernate's `$HibernateProxy$`, ByteBuddy's own `$ByteBuddy$`, javassist's
  `_$$_jvst` and JDK dynamic proxies produce the same shape and are not covered; `STATUS.md` carries
  them, to be added when a corpus or an adopter shows one. The cost of a missing marker is the noise
  above, which is visible and loud, not a silent wrong claim.
- An adopter who genuinely wants a generated class instrumented has no way to ask for it. Nobody has
  wanted one.
