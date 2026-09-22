---
status: accepted
---

# Dependency usage is read from class loads and the adopter's references, never from probes in library code

An adopter wants to know which of their dependencies they could drop. Nothing on the wire could say:
no message carried a jar, a code source or Maven coordinates, a class was only ever named by its
class name, and package names map to artifacts too loosely for a collector to guess (one artifact
holds several packages, and `org.springframework.web` spans two). Call edges stop at the scope
boundary, so the collector never learned that adopter code touched a library at all.

The agent therefore reports three observations per *dependency*, a jar on the classpath that is not
the adopter's own, and the collector derives three statuses from them, checked in order:

1. **Unloaded.** The dependency is on the instance's startup classpath and no class from it has
   loaded. The agent lists the startup classpath once, on a background thread started from
   `premain` (`java.class.path` entries, and in a Spring Boot fat jar every nested jar under
   `BOOT-INF/lib`), reads each jar's identity from `pom.properties`, and counts
   distinct loaded classes per dependency on every flush from the loaded-class array the sweep
   already takes, looking up each class's `ProtectionDomain` in an identity cache.
2. **Unreferenced.** At least one class loaded, but nothing in the adopter's code refers to the
   dependency. The branch analyser records every out-of-scope class the adopter's bytecode names, per
   method and per class, and the agent resolves each name to its dependency through the referencing
   class's own loader with `getResource`, which reads and never loads.
3. **Unreached.** The adopter's code refers to the dependency, but only from methods never hit or
   classes never loaded. This joins the references to hit counts the collector already has.

No library code is instrumented at any level. Library methods stay out of scope, so this feature adds
nothing to the hot path.

Identity is `groupId:artifactId` from the jar's single `pom.properties`, falling back to the
filename with its version stripped, and an empty group. The version is
an attribute, so an upgrade does not make a dependency look new and never used. A jar with several
`pom.properties` is a shaded jar: one dependency carrying every bundled identity, since it cannot be
half-removed. One identity loaded by two classloaders is one dependency. A class is matched to its
dependency by the identity of the jar it came from, never by comparing code-source URL strings. Those
differ by Spring Boot version (`jar:nested:<outer>/!BOOT-INF/lib/x.jar!/` from 3.2, `jar:file:<outer>
!/BOOT-INF/lib/x.jar!/` before it), and differ again for a library Boot unpacks to `java.io.tmpdir`.

A *reference* is wider than a call edge: invokes, field accesses, `new`, casts, `instanceof`, catch
types, class literals, types in method and field descriptors and generic signatures, supertypes, and
runtime-visible annotations on classes, methods, fields and parameters. A dependency used only through
`@JsonProperty`, or only as a base class, is still referenced. A reference in a pass-through is attributed to whatever references the
pass-through, and a lambda body or body class keeps its own, by the same rules as call edges. A
referenced class the bootstrap or platform loader provides is not a dependency and is dropped. A
referenced class that no loader can find is an *absent reference* and is reported, not dropped.

## Considered options

- **Probing the library methods the adopter's code references.** Rejected twice over. The probe
  counts every caller, Spring and other libraries included, so a hit says nothing about whether the
  adopter's call site ran, and a zero duplicates what the adopter's own probes already say about the
  code holding the call. It also needs the reference list complete before the library class loads,
  which means a synchronous classpath scan in `premain`.
- **Leaving private library methods unprobed.** Rejected as a way to save cost. `javap -p` over
  spring-webmvc 6.2.19, jackson-databind 2.15.1 and ktor-server-core 3.0.3 found 3.5 to 12 percent of
  methods private, and most of the cost is per class (the rewrite, the `<clinit>` prelude, the
  manifest entry, the branch probes in public bodies), which a visibility filter leaves in place.
- **Instrumenting everything and filtering at the collector.** Rejected: the cost lands in the
  adopter's JVM on the hottest code it runs, before the collector sees a byte, and the collector
  cannot tell `com.acme` from `com.thirdparty` any better than the agent can.
- **Recording out-of-scope call edges in full.** Rejected: it answers "which library methods do I
  call", which nobody asked, at the size of every call site. A set of class names per method answers
  the dependency question and still names which classes of a dependency are used.
- **Recording dependency IDs per method, resolved agent-side.** Rejected: smallest on the wire, but
  it loses which class was referenced.
- **Letting the collector map class names to dependencies.** Rejected: a class that never loaded has
  no known jar, and shipping every jar's class list to fix that makes the listing as large as the
  classpath.
- **Call edges only, as the definition of a reference.** Rejected: an annotation-only or
  base-class-only dependency would read as unreferenced, a false claim.
- **Reporting only the startup classpath.** Rejected: a jar that appears only at load, such as a WAR's
  `WEB-INF/lib` loaded by Tomcat after `premain`, would vanish from the report though it ran. It is
  added lazily and marked as discovered by load, the endpoint precedent, and can never read as
  unloaded.
- **Reading a nested jar's identity from its filename only.** Rejected: filenames are not unique
  across group IDs. Boot's Gradle plugin stores every `BOOT-INF/lib` entry uncompressed
  (`BootJar.resolveZipCompression` in 4.1.1), so a nested jar can be streamed straight from the
  outer one with nothing extracted to disk.
- **Treating a jar as the adopter's own only when all its classes are in scope.** Rejected: a shaded
  single-jar application would read as a dependency of itself, and with `includePackages` unset every
  jar would be the adopter's.
- **The manifest's `Implementation-Title` as a fallback identity.** Rejected once the listing ran
  against `demo-spring`: 21 of its 35 nested jars carry no `pom.properties` (every Spring jar is
  built by Gradle), and the title is a display name, not an artifact ID: `Spring Boot Web MVC`,
  `io.micrometer#micrometer-commons;1.17.1`, and `Apache Tomcat` on three different Tomcat jars,
  which merged them into one dependency so that two vanished. The filename was the exact artifact ID
  in all 21 cases, since Boot and Gradle name a packaged jar `<artifactId>-<version>.jar`. The
  manifest's `Implementation-Version` still fills in the version when the filename carries none.
  `DependencyIdentitySource.JAR_MANIFEST` stays on the wire, never produced.
- **Counting every annotation as a reference.** Rejected once references ran against `demo-spring`:
  kotlinc puts `@NotNull` and `@Nullable` from `org.jetbrains:annotations` on nearly every Kotlin
  class, so that jar always read as referenced and live. Those annotations have class retention: the
  JVM never resolves their types, and the jar can leave the runtime classpath with nothing changing.
  Only runtime-visible annotations (ASM's `visible` flag) are references, together with the values
  inside them.
- **Recognising agent jars from `-javaagent` flags.** Rejected: reading the JVM's input arguments
  means `java.lang.management`, which loads classes in `premain`. The manifest, already read during
  the listing, says the same thing.
- **A config flag.** Rejected: the listing reads one small entry per jar and the references are
  bounded by the adopter's code size, the reasoning ADR 0021 and ADR 0024 gave for having none.

## Consequences

- A jar holding any in-scope class is the adopter's own and is not a dependency. With `includePackages`
  unset every class is in scope, so origin decides instead: classpath directories, `BOOT-INF/classes`
  and `WEB-INF/classes` are the adopter's and every jar is a dependency. A shaded single-jar
  application therefore reports no dependencies, and the agent logs one INFO line saying so.
- A jar whose manifest carries `Premain-Class` or `Launcher-Agent-Class` is never a dependency, so
  Yukon's own jar, an OpenTelemetry agent or a profiler never reads as unreferenced.
- Unreferenced and unreached are claimed only for instances whose include rules are set, since with
  every class in scope library-to-library references would count as the adopter's. They are split only
  with a complete static baseline from each such instance: a reference inside a class that never
  loaded is visible only to the baseline, and without it a collector would call that dependency
  unreferenced when it is unreached. Without the baseline the two merge into "no live reference",
  which is true either way. The baseline scanner records references by the same analyser rules.
- A *live reference* is one held by a method with hits in any in-scope instance, or held by a class
  (an annotation, a supertype, a field type) that loaded. An inline method's own references never
  count, since its Kotlin callers carry copies, the reason ADR 0024 leaves inline methods out of the
  graph.
- The loaded-class count per dependency is distinct names ever seen, merged with `max()`, so class
  unloading cannot lower it. The agent holds one set of names per dependency for the life of the
  process.
- A dependency reached with no bytecode reference at all (a JDBC driver through `ServiceLoader`, a
  logging backend SLF4J binds) reads as unreferenced. That is why the statuses are observations, not
  a verdict that a dependency can go, and why a collector should show unreferenced beside the
  loaded-class count.
- Direct and transitive dependencies look the same: the build file is not visible at runtime.
  Unreferenced is the nearest approximation of "transitive, or used only by a framework".
- The static baseline's blind spot carries over to the startup listing: a jar only another system's
  runtime logic knows about is not on it. Discovery by load covers it once a class from the jar loads;
  a jar that never loads a class stays invisible.
- The listing runs off `premain`, like the static baseline scan (ADR 0014). Judging whether a jar
  is the adopter's own reads every entry name, and in a fat jar that means streaming each nested
  jar: about 200 ms for `demo-spring`'s 36 nested jars (20 MB), so one to two seconds for a large
  application, which is too much to add to boot. The sweep counts nothing for dependencies until
  the listing has finished, so no startup jar is ever marked as discovered by load; the cost is one
  flush of delay on `first_loaded_at`, whose precision is a flush anyway. Reading each nested jar's
  central directory straight from its stored byte range would have been fast enough for `premain`,
  but it is hand-written zip parsing for a problem a thread solves.
- A fat jar's nested dependencies are every file under `BOOT-INF/lib/`, or `WEB-INF/lib/` and
  `WEB-INF/lib-provided/` for an executable war, at any depth and whatever the extension: exactly
  what `JarLauncher` and `WarLauncher` select in `isLibraryFileOrClassesDirectory`. The classpath
  index is not read. `Launcher.getClassPathIndex` returns null unless the archive is exploded, so a
  packaged launch ignores it, and it can leave out a jar that is on the classpath all the same
  (`spring-boot-jarmode-tools` in `demo-spring`). Confirmed from the loader bytecode Boot 4.1.1
  packages.
- The baseline maps its references without a defining loader, through a class-name index the
  listing builds only when the baseline is enabled and drops once the baseline has read it. It
  never records a name as absent: it cannot tell a missing class from one in a jar only a runtime
  loader opens, and an absent verdict would outrank the transform path's later answer. Every
  mapping travels on the manifest; `StaticBaseline.external_classes` stays empty.
- ADR 0014 left nested jars unopened for the scan. This decision opens them for the listing only, one
  entry each; the scan still does not walk their classes.
- Wire, all additive: `DependencyLocation` send-once on the manifest, `DependencyDelta` on the delta
  batch, `referenced_classes` on METHOD-kind `ProbeLocation` and on `DeclaredMethod`, a send-once
  `ClassReferences` list for class-level references with a matching field on `DeclaredClass`, and a
  send-once `ExternalClass` list mapping each referenced class to its dependency or marking it absent.
  Across instances a dependency is `group:artifact`, never its per-instance ID. References weigh one
  entry each in the manifest and baseline chunk caps.
- Instances merge by identity across versions, and a report shows the versions seen. A dependency
  follows its instance on prune and on a version-change wipe, like endpoints.
- `yukon-testkit` gains `dependency(group, artifact)` and `unloadedDependencies()`, applying the
  collector's rules within one JVM.
