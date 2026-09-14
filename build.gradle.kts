import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.11"
    id("com.google.protobuf") version "0.9.4"
}

group = "io.github.lukedevops"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

evaluationDependsOn(":bootstrap")

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

    // Wire schema for the delta batch and probe manifest payloads
    // (see src/main/proto/yukon.proto). Generated classes are shaded under
    // io.github.lukedevops.yukon.shaded.protobuf below, same rationale as
    // the ByteBuddy relocation.
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(kotlin("test"))
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
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

            // Endpoint advice classes are inlined into framework bytecode by ByteBuddy, not
            // loaded as ordinary agent classes, so any Kotlin-typed reference in them would be
            // rewritten by the relocation above to a shaded class the framework does not have,
            // producing a NoClassDefFoundError inside the target application. Passes trivially
            // while this package holds no classes yet.
            val relocatedKotlinMarker = "io/github/lukedevops/yukon/shaded/kotlin".toByteArray(Charsets.US_ASCII)
            val endpointClassesWithShadedKotlin =
                names
                    .filter { it.startsWith("io/github/lukedevops/yukon/endpoints/") && it.endsWith(".class") }
                    .filter { name ->
                        val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                        indexOf(bytes, relocatedKotlinMarker) >= 0
                    }
            check(endpointClassesWithShadedKotlin.isEmpty()) {
                "endpoint advice classes must never reference the shaded Kotlin stdlib, found in: $endpointClassesWithShadedKotlin"
            }
        }
    }
}

/** Naive substring search over raw bytes, used to check a class file for a relocated package name. */
fun indexOf(
    haystack: ByteArray,
    needle: ByteArray,
): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

tasks.shadowJar {
    finalizedBy(verifyAgentJar)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
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
