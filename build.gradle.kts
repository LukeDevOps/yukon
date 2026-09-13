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

    // Main artifact ships ASM shaded under net.bytebuddy.jar.asm.*, which the
    // branch-tracking tier's AsmVisitorWrapper uses directly instead of pulling
    // in a second, independently-versioned ASM dependency.
    implementation("net.bytebuddy:byte-buddy:1.18.12")

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

// Fails the build if the shaded jar does not have the shape premain relies on: the embedded
// holder jar present as one resource, and none of the holder's classes present loose.
val verifyAgentJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
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
        }
    }
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
