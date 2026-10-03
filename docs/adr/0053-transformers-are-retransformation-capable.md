---
status: accepted
---

# Both tiers register as retransformation-capable, so they run after every other agent's transformer

Decided on 2026-10-03 in a grilling session with Luke, alongside ADR 0052.

The method tier and the endpoint tier registered their transformers as not retransformation-capable, which puts them in the same group as JaCoCo's, AspectJ's and Spring's load-time weaver's, where the `-javaagent` order on the command line decides who runs first. With this agent first, JaCoCo receives woven bytes: it identifies a class by a CRC64 of the bytes it receives (`Instrumenter.java:75`, 0.8.13) and its report hashes the class file (`Analyzer.java:106`), so every woven class reads "Execution data for class X does not match" with zero coverage. The endpoint tier counts too, since the JAX-RS module weaves the adopter's resource classes. And this agent first is Gradle's default: `Test.jvmArgs` is copied ahead of the `jacoco` plugin's argument provider (`DefaultJavaForkOptions.copyTo`, 8.14), so an adopter following the testkit's setup loses their coverage report.

So both tiers register as retransformation-capable. The JVM calls every transformer that is not capable before every one that is, whatever the command-line order (`jvmtiExport.cpp:975`, jdk21u), so this agent always runs after JaCoCo and the weavers, sees their output as received bytes, and reads shape from the class file under ADR 0052. Nothing is retransformed at install. OpenTelemetry's agent sits in the same group.

## Considered options

- **Staying in the first group, with documentation and a warning.** The README would tell adopters to add the agent through an argument provider after the `jacoco` plugin, and `premain` would read the JVM's input arguments and warn when a known coverage agent comes later. Rejected: adopters get a broken coverage report and one log line, and Maven's ordering differs from Gradle's again.
- **A testkit Gradle plugin that orders the arguments.** Rejected: a new published artifact that only covers Gradle and only covers the testkit.

## Consequences

- Another agent's `retransformClasses` on a woven class now reaches this agent, with the bytes from before it ran. A null answer would leave the class without its probe field, the JVM would refuse the schema change, and the other agent's whole batch would fail. So a class this agent wove is re-woven to identical bytes, which follows from ADR 0052: the output depends only on the class file and the received bytes, and the JVM's cached bytes are the received bytes of the first load. Any class this agent did not weave gets null. The `<clinit>` prelude does not run again and needs not to, since the field keeps its array. OpenTelemetry handles the same case the same way (`FieldBackedImplementationInstaller.java:250`, v2.32.0).
- Type descriptions on a retransformation are built from the passed bytes, not the loaded class, which already carries `$otherlodeProbeCounts`; ByteBuddy's default description strategy reads the loaded class.
- A retransformation commits nothing to the registry and declares no JAX-RS endpoint a second time.
- Redefinition stays unsupported, as ADR 0005 says.
- The JVM keeps an off-heap copy of each woven class's received bytes, about one class-file length each, freed when the class unloads. Before this, the only class it kept was the JDK lambda factory ADR 0035 hooks. There is no flag: the cost is bounded by the adopter's own code, like the branch tier's, and `STATUS.md`'s overhead measurement covers it. The second JVMTI environment already existed, since the manifest sets `Can-Retransform-Classes`.
- A capable agent listed ahead of this one that rewrites adopter classes is an earlier transformer like any other, handled by ADR 0052.
