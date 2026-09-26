import java.nio.ByteBuffer
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.11"
    id("com.google.protobuf") version "0.9.4"
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.lukedevops"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// `-Pyukon.testJdk=25` runs every project's tests on that JDK. Compilation keeps the JDK 21
// toolchain; only the JVM that runs the tests changes. CI sets it so a JDK that renames an
// internal the agent reads, such as the lambda factory members in ADR 0035, fails a test.
val testJdk = providers.gradleProperty("yukon.testJdk")
allprojects {
    plugins.withType<JavaBasePlugin> {
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<Test>().configureEach {
            if (testJdk.isPresent) {
                javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJdk.get())) })
            }
        }
    }
}

evaluationDependsOn(":bootstrap")
evaluationDependsOn(":fixtures-scala3")
evaluationDependsOn(":fixtures-scala2")
evaluationDependsOn(":fixtures-kotlin-jvm-default-disable")
evaluationDependsOn(":fixtures-kotlin-class-sam")
evaluationDependsOn(":demo-spring")
evaluationDependsOn(":endpoints-ktor-3")

dependencies {
    // Compile-time only: at runtime the holder class comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar below. Shipping it as loose
    // classes in this jar too would put a second copy on the system loader.
    compileOnly(project(":bootstrap"))

    // testCompileOnly does not extend compileOnly by default, so tests that reference a
    // bootstrap-resident type directly (loaded for real via BootstrapHolder.install) need the
    // same compile-time-only dependency repeated here.
    testCompileOnly(project(":bootstrap"))

    // Main artifact ships ASM shaded under net.bytebuddy.jar.asm.*, which the
    // branch-tracking tier's AsmVisitorWrapper uses directly instead of pulling
    // in a second, independently-versioned ASM dependency.
    implementation("net.bytebuddy:byte-buddy:1.18.12")

    // EndpointModule, AdviceBinder, and the ByteBuddy-facing types a per-framework endpoint
    // module is written against. A per-framework subproject depends on this module and never on
    // this one, the root project, since the root project depends on the per-framework modules to
    // merge their advice into the shaded jar: depending the other way would be a cycle.
    implementation(project(":endpoints-api"))

    // The first real endpoint module: the JDK's own com.sun.net.httpserver.HttpServer.
    implementation(project(":endpoints-jdk-httpserver"))

    // Endpoint module for Spring MVC, covering Spring Framework 5.3, 6.x and 7.x with one module.
    implementation(project(":endpoints-spring-webmvc"))

    // Endpoint modules for Ktor. Route became the interface RoutingNode between 2.x and 3.x, so
    // the two major versions need their own module rather than one shared one.
    implementation(project(":endpoints-ktor-2"))
    implementation(project(":endpoints-ktor-3"))

    // Endpoint module for JAX-RS, covering both the javax.ws.rs and jakarta.ws.rs namespaces with
    // one module. JAX-RS has no registration hook to advise, so its declared list comes from
    // reading annotations at transform time instead; see the module's own KDoc.
    implementation(project(":endpoints-jaxrs"))

    // Route bridge endpoint module: counts the route OpenTelemetry's own HTTP server
    // instrumentation resolved, for a framework none of the modules above cover. Off by default
    // (AgentConfig.otelBridgeEnabled); see ADR 0019.
    implementation(project(":endpoints-otel-bridge"))

    // Wire schema for the delta batch and probe manifest payloads
    // (see src/main/proto/yukon.proto). Generated classes are shaded under
    // io.github.lukedevops.yukon.shaded.protobuf below, same rationale as
    // the ByteBuddy relocation.
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(kotlin("test"))
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")

    // Drives javassist's own proxy generator, so the name rule for its classes is tested against
    // a class it really defined.
    testImplementation("org.javassist:javassist:3.30.2-GA")

    // JaCoCo's offline instrumenter, used only to produce the bytecode shape a coverage agent
    // attached ahead of this one hands to the transformer chain, so the analyser is tested
    // against the real thing rather than a hand-written imitation of it.
    testImplementation("org.jacoco:org.jacoco.core:0.8.13")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The bootstrap module's jar rides inside this one as a plain resource. premain writes it to a
// temp file and hands it to Instrumentation.appendToBootstrapClassLoaderSearch, which takes a jar
// on disk and nothing else. Embedding the finished jar rather than its classes is what keeps the
// holder out of this jar's own class tree, so the shadow relocation below never touches it and
// the system loader never sees a second copy.
//
// The resource deliberately does not end in ".jar": shadowJar explodes every file with that
// extension it copies, dependency or not, which would scatter the holder's classes into this
// jar as loose files and drop the resource itself. The demo would not have caught that, since
// its classpath also carries the plain jar where the resource survives; verifyAgentJar below
// checks the shaded jar directly.
val bootstrapResourcePath = "META-INF/yukon/bootstrap-jar.bin"

val embedBootstrapJar by tasks.registering(Sync::class) {
    from(project(":bootstrap").tasks.named<Jar>("jar")) {
        into(bootstrapResourcePath.substringBeforeLast('/'))
        rename { bootstrapResourcePath.substringAfterLast('/') }
    }
    // This directory becomes a resource root, so the META-INF/yukon prefix above is what the
    // resource path inside the agent jar ends up being.
    into(layout.buildDirectory.dir("generated-resources/bootstrap"))
}

sourceSets.main {
    resources.srcDir(embedBootstrapJar)
}

// Licence texts for the dependencies relocated into the shaded jar, one directory per
// component. Byte Buddy is the only one of them that ships its own LICENSE and NOTICE entries;
// the rest carry nothing, so the texts are checked in here rather than extracted at build time.
val bundledLicensesDir = layout.projectDirectory.dir("licenses")
val bundledLicenseEntries =
    fileTree(bundledLicensesDir).files.map { it.relativeTo(bundledLicensesDir.asFile).invariantSeparatorsPath }.sorted()

// Fails the build if the shaded jar does not have the shape premain relies on: the embedded
// holder jar present as one resource, and none of the holder's classes present loose. Also
// checks that META-INF/NOTICE is this project's own and that every vendored third-party licence
// made it under META-INF/licenses/.
val verifyAgentJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    inputs.property("bundledLicenseEntries", bundledLicenseEntries)
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            check(bootstrapResourcePath in names) {
                "agent jar is missing the embedded bootstrap holder at $bootstrapResourcePath"
            }
            val loose = names.filter { it.startsWith("io/github/lukedevops/yukon/bootstrap/") && it.endsWith(".class") }
            check(loose.isEmpty()) {
                "agent jar must not carry the bootstrap holder as loose classes, found: $loose"
            }
            val notice = checkNotNull(zip.getEntry("META-INF/NOTICE")) { "agent jar is missing META-INF/NOTICE" }
            val noticeFirstLine = zip.getInputStream(notice).bufferedReader().use { it.readLine() }
            check(noticeFirstLine == "Yukon") {
                "agent jar's META-INF/NOTICE is not this project's own; first line is \"$noticeFirstLine\""
            }
            check("META-INF/LICENSE" in names) { "agent jar is missing META-INF/LICENSE" }
            val missingLicenses = bundledLicenseEntries.map { "META-INF/licenses/$it" }.filterNot { it in names }
            check(missingLicenses.isEmpty()) {
                "agent jar is missing vendored third-party licence entries: $missingLicenses"
            }

            // Endpoint advice classes are inlined into framework bytecode by ByteBuddy, never
            // loaded as ordinary agent classes. Only the annotated method's own bytecode is
            // copied; anything else the advice touches stays a reference the target's own
            // classloader has to resolve, and from a bootstrap-loaded target, or an isolating
            // container loader, that resolution fails with NoClassDefFoundError and the module
            // disables itself at the first request. Each class under this package must therefore
            // reference no agent class except itself and the bootstrap seam (with its nested
            // classes, which load from the same bootstrap jar), call no method on
            // itself (a private helper is a real call at the woven site), carry no synthetic
            // member (a lambda, a switch over an enum, or an assert compiles to one), and never
            // mention the relocated Kotlin stdlib. Passes trivially while the package is empty.
            val adviceClassNames = names.filter { it.startsWith("io/github/lukedevops/yukon/endpoints/") && it.endsWith(".class") }
            val adviceProblems =
                adviceClassNames.flatMap { entryName ->
                    val shape = parseClassShape(zip.getInputStream(zip.getEntry(entryName)).use { it.readBytes() })
                    val problems = mutableListOf<String>()
                    if (shape.utf8Constants.any { "io/github/lukedevops/yukon/shaded/kotlin" in it }) {
                        problems += "references the shaded Kotlin stdlib"
                    }
                    val foreignAgentClasses =
                        shape.referencedClasses.filter {
                            it.startsWith("io/github/lukedevops/yukon/") &&
                                it != shape.name &&
                                it != "io/github/lukedevops/yukon/bootstrap/YukonEndpoints" &&
                                !it.startsWith("io/github/lukedevops/yukon/bootstrap/YukonEndpoints$") &&
                                !it.startsWith("io/github/lukedevops/yukon/shaded/bytebuddy/")
                        }
                    if (foreignAgentClasses.isNotEmpty()) problems += "references agent classes $foreignAgentClasses"
                    if (shape.selfMethodCalls.isNotEmpty()) problems += "calls its own methods ${shape.selfMethodCalls}"
                    if (shape.syntheticMembers.isNotEmpty()) problems += "declares synthetic members ${shape.syntheticMembers}"
                    problems.map { "${shape.name}: $it" }
                }
            check(adviceProblems.isEmpty()) {
                "endpoint advice classes must be self-contained (see the comment in verifyAgentJar):\n  " +
                    adviceProblems.joinToString("\n  ")
            }
        }
    }
}

/** The parts of a class file the advice check reads; see [parseClassShape]. */
class ClassShape(
    val name: String,
    val utf8Constants: List<String>,
    val referencedClasses: Set<String>,
    val selfMethodCalls: Set<String>,
    val syntheticMembers: List<String>,
)

/**
 * Reads a class file's constant pool and member tables, enough to know which classes it
 * references, which of its own methods it calls, and whether it declares a synthetic member.
 * Written here rather than through ASM because the build script has no bytecode library on its
 * classpath, and the class-file format's constant pool is a short, fixed set of tagged entries.
 */
fun parseClassShape(bytes: ByteArray): ClassShape {
    val buf = ByteBuffer.wrap(bytes)

    fun u2(): Int = buf.short.toInt() and 0xFFFF
    check(buf.int == 0xCAFEBABE.toInt()) { "not a class file" }
    u2()
    u2()
    val poolCount = u2()
    val utf8 = arrayOfNulls<String>(poolCount)
    val classNameIndex = IntArray(poolCount)
    val refClassIndex = IntArray(poolCount)
    val refNameAndTypeIndex = IntArray(poolCount)
    val nameAndTypeNameIndex = IntArray(poolCount)
    val methodRefs = mutableListOf<Int>()
    var i = 1
    while (i < poolCount) {
        when (val tag = buf.get().toInt()) {
            1 -> {
                val length = u2()
                val raw = ByteArray(length)
                buf.get(raw)
                utf8[i] = String(raw, Charsets.UTF_8)
            }

            3, 4 -> {
                buf.int
            }

            5, 6 -> {
                buf.long
                i++
            }

            7 -> {
                classNameIndex[i] = u2()
            }

            8, 16, 19, 20 -> {
                u2()
            }

            9, 10, 11 -> {
                refClassIndex[i] = u2()
                refNameAndTypeIndex[i] = u2()
                if (tag == 10 || tag == 11) methodRefs += i
            }

            12 -> {
                nameAndTypeNameIndex[i] = u2()
                u2()
            }

            15 -> {
                buf.get()
                u2()
            }

            17, 18 -> {
                u2()
                u2()
            }

            else -> {
                error("unknown constant pool tag $tag")
            }
        }
        i++
    }
    u2()
    val thisClass = u2()
    u2()
    repeat(u2()) { u2() }
    val name = checkNotNull(utf8[classNameIndex[thisClass]])
    val synthetic = mutableListOf<String>()
    repeat(2) {
        repeat(u2()) {
            val access = u2()
            val memberName = checkNotNull(utf8[u2()])
            u2()
            repeat(u2()) {
                u2()
                val attributeLength = buf.int
                buf.position(buf.position() + attributeLength)
            }
            if (access and 0x1000 != 0 || memberName.startsWith("lambda$") || memberName.startsWith("$")) {
                synthetic += memberName
            }
        }
    }
    val referencedClasses =
        (1 until poolCount).filter { classNameIndex[it] != 0 }.mapTo(
            HashSet(),
        ) { checkNotNull(utf8[classNameIndex[it]]) }
    val selfMethodCalls =
        methodRefs
            .filter { utf8[classNameIndex[refClassIndex[it]]] == name }
            .mapTo(HashSet()) { checkNotNull(utf8[nameAndTypeNameIndex[refNameAndTypeIndex[it]]]) }
    return ClassShape(name, utf8.filterNotNull(), referencedClasses, selfMethodCalls, synthetic)
}

tasks.shadowJar {
    finalizedBy(verifyAgentJar)
}

// Scala default-getter resolution (ADR 0023) is proven against real scalac output, compiled by
// the two Scala fixture modules below, `$DefaultImpls` marking (ADR 0026) against kotlinc
// output under -jvm-default=disable, compiled by the third, and the forwarder table (ADR 0035)
// against kotlinc output under class-based SAM conversion, compiled by the fourth. None is a
// test dependency, only a task dependency: putting one on the test classpath would let JUnit
// discovery load these classes before install() wires up instrumentation, defeating the
// fixture's purpose (see FixtureClassLoader). The output directory and runtime classpath are
// read lazily through providers, resolved only when the test task actually runs, so configuring
// this project never forces a fixture project to evaluate its dependencies.
val scala3FixtureClassesDir = project(":fixtures-scala3").layout.buildDirectory.dir("classes/scala/main")
val scala3FixtureRuntimeClasspath = project(":fixtures-scala3").configurations.named("runtimeClasspath")
val scala2FixtureClassesDir = project(":fixtures-scala2").layout.buildDirectory.dir("classes/scala/main")
val scala2FixtureRuntimeClasspath = project(":fixtures-scala2").configurations.named("runtimeClasspath")
val jvmDefaultDisableFixtureClassesDir =
    project(":fixtures-kotlin-jvm-default-disable").layout.buildDirectory.dir("classes/kotlin/main")
val jvmDefaultDisableFixtureRuntimeClasspath =
    project(":fixtures-kotlin-jvm-default-disable").configurations.named("runtimeClasspath")
val classSamFixtureClassesDir = project(":fixtures-kotlin-class-sam").layout.buildDirectory.dir("classes/kotlin/main")

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    dependsOn(
        ":fixtures-scala3:classes",
        ":fixtures-scala2:classes",
        ":fixtures-kotlin-jvm-default-disable:classes",
        ":fixtures-kotlin-class-sam:classes",
    )
    // ShadedBodyKindRuleTest runs the analyser from the shaded jar, since relocation rewrites
    // string constants in the agent's own classes and only the shaded copy shows the effect.
    dependsOn(tasks.shadowJar)
    val shadedAgentJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shadedAgentJar)
    doFirst {
        systemProperty("yukon.agent.shadedJar", shadedAgentJar.get().asFile.absolutePath)
        systemProperty("yukon.fixtures.scala3.dir", scala3FixtureClassesDir.get().asFile.absolutePath)
        systemProperty("yukon.fixtures.scala3.classpath", scala3FixtureRuntimeClasspath.get().asPath)
        systemProperty("yukon.fixtures.scala2.dir", scala2FixtureClassesDir.get().asFile.absolutePath)
        systemProperty("yukon.fixtures.scala2.classpath", scala2FixtureRuntimeClasspath.get().asPath)
        systemProperty(
            "yukon.fixtures.jvmdefaultdisable.dir",
            jvmDefaultDisableFixtureClassesDir.get().asFile.absolutePath,
        )
        systemProperty(
            "yukon.fixtures.jvmdefaultdisable.classpath",
            jvmDefaultDisableFixtureRuntimeClasspath.get().asPath,
        )
        systemProperty("yukon.fixtures.classsam.dir", classSamFixtureClassesDir.get().asFile.absolutePath)
        systemProperty(
            "yukon.demo.serverMainSource",
            project(":demo").file("src/main/kotlin/io/github/lukedevops/demo/server/DemoServerMain.kt").absolutePath,
        )
    }
}

// The transform-time benchmark's corpora: class sets by corpus name, each one module's output or
// one jar. BenchmarkCorpus reads them from the system properties below, in the JMH fork and in the
// test that keeps them loadable. spring-webmvc is the jar demo-spring already resolves, a large
// body of real Java. ktor-server-core is the jar endpoints-ktor-3 tests against, a large body of
// real Kotlin with suspend functions and inline functions. The agent's own classes are not a
// corpus: TypeMatchPolicy never includes the agent's package, so the analyser would drop their
// inlined copies and call edges, which no adopter class sees.
val benchmarkCorpora: Map<String, Map<String, FileCollection>> =
    mapOf(
        "ktor-server-core" to
            mapOf(
                "jar" to
                    project(":endpoints-ktor-3")
                        .configurations
                        .getByName("testRuntimeClasspath")
                        .filter { it.name.startsWith("ktor-server-core-jvm-") },
            ),
        "demo" to mapOf("main" to files(project(":demo").layout.buildDirectory.dir("classes/kotlin/main")).builtBy(":demo:classes")),
        "demo-spring" to
            mapOf(
                "main" to files(project(":demo-spring").layout.buildDirectory.dir("classes/kotlin/main")).builtBy(":demo-spring:classes"),
            ),
        "scala" to
            mapOf(
                "fixtures-scala2" to files(scala2FixtureClassesDir).builtBy(":fixtures-scala2:classes"),
                "fixtures-scala3" to files(scala3FixtureClassesDir).builtBy(":fixtures-scala3:classes"),
            ),
        "spring-webmvc" to
            mapOf(
                "jar" to
                    project(":demo-spring")
                        .configurations
                        .getByName("runtimeClasspath")
                        .filter { it.name.startsWith("spring-webmvc-") },
            ),
    )
val benchmarkCorpusFiles = files(benchmarkCorpora.values.flatMap { it.values })

fun benchmarkCorpusProperties(): Map<String, String> =
    benchmarkCorpora
        .flatMap { (corpus, classSets) ->
            classSets.map { (classSet, files) ->
                val path = files.asPath
                // JMH joins the fork's JVM arguments with spaces, so a space in a path would split it.
                check(' ' !in path) { "benchmark corpus $corpus.$classSet has a space in its path: $path" }
                "yukon.benchmark.corpus.$corpus.$classSet" to path
            }
        }.toMap()

tasks.test {
    inputs.files(benchmarkCorpusFiles).withPropertyName("benchmarkCorpora")
    doFirst { systemProperties(benchmarkCorpusProperties()) }
}

// Fork, warmup and measurement settings live on the benchmark class. `-Pyukon.benchmark.corpus=demo`
// runs one corpus; a comma-separated list runs several.
jmh {
    jmhVersion.set("1.37")
    includeTests.set(false)
    jvmArgsAppend.addAll(provider { benchmarkCorpusProperties().map { (key, value) -> "-D$key=$value" } })
    providers.gradleProperty("yukon.benchmark.corpus").orNull?.let { selected ->
        benchmarkParameters.put("corpus", objects.listProperty<String>().value(selected.split(',').map { it.trim() }))
    }
}

tasks.named("jmh") {
    inputs.files(benchmarkCorpusFiles).withPropertyName("benchmarkCorpora")
}

// BenchmarkCorpusTest compiles against the benchmark's own corpus loader. So every build also
// compiles the benchmark, and an analyser change that breaks it fails the build.
sourceSets.test {
    compileClasspath += sourceSets["jmh"].output
    runtimeClasspath += sourceSets["jmh"].output
}

val agentMainClass = "io.github.lukedevops.yukon.Agent"

// The plain jar task would otherwise write to the same build/libs/yukon-*.jar
// path as shadowJar below (its classifier is cleared to make that the single
// distributable file), and whichever task happened to run last would win,
// silently overwriting the shaded agent jar with one missing the
// Premain-Class manifest attribute and the relocated dependencies. Giving the
// plain jar its own classifier keeps the two outputs apart without disabling
// the task outright: disabling it previously broke `project(":")` consumers
// (the demo module's compile classpath), which resolve a local project
// dependency's default `apiElements`/`runtimeElements` variant back to this
// task's output. Only shadowJar's output is ever meant to be distributed or
// used as the -javaagent jar; the plain jar exists solely so in-repo project
// dependencies keep working.
tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")

    // Each per-framework endpoint module subproject ships its own
    // META-INF/services/io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
    // entry. Without this, shadow keeps only one such file (whichever dependency it copies
    // last), so every module but one would silently vanish from ServiceLoader discovery.
    mergeServiceFiles()

    // Relocate ByteBuddy so it can't collide with a possibly
    // differently-versioned copy already on the target application's classpath.
    relocate("net.bytebuddy", "io.github.lukedevops.yukon.shaded.bytebuddy")

    // Same rationale for protobuf-java: the target app may already carry its
    // own, differently-versioned copy on the classpath.
    relocate("com.google.protobuf", "io.github.lukedevops.yukon.shaded.protobuf")

    // Most of the agent itself is Kotlin, so kotlin-stdlib is unavoidably on this jar's own
    // classpath too. Left unrelocated, it collides exactly the same way ByteBuddy and protobuf
    // would: a Kotlin target app almost certainly carries its own, possibly differently-versioned
    // copy of the same classes on the system classloader the agent shares with it.
    relocate("kotlin", "io.github.lukedevops.yukon.shaded.kotlin")

    // Transitive dependency of kotlin-stdlib (org.jetbrains:annotations). Same collision
    // rationale, lower stakes since these are stable marker annotations, but no reason to leave
    // them unshaded either.
    relocate("org.jetbrains.annotations", "io.github.lukedevops.yukon.shaded.annotations")
    relocate("org.intellij.lang.annotations", "io.github.lukedevops.yukon.shaded.intellij.annotations")

    // META-INF/LICENSE and META-INF/NOTICE must describe this jar, not whichever dependency's
    // entries shadow happened to copy first, so every dependency's own copies are dropped and
    // the vendored texts under licenses/ go in under META-INF/licenses/ instead. The exclude
    // patterns match a dependency entry's path inside its jar; the from() blocks below are
    // matched against their own source paths (LICENSE, byte-buddy/NOTICE), which the patterns
    // do not cover, so they land where into() sends them.
    exclude("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/licenses/**")
    from(layout.projectDirectory.files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
    from(bundledLicensesDir) {
        into("META-INF/licenses")
    }

    manifest {
        attributes(
            "Premain-Class" to agentMainClass,
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
