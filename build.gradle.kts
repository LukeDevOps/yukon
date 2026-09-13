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

dependencies {
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
