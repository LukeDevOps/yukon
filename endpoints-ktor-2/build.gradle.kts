import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

val byteBuddyVersion = "1.18.12"
val ktorVersion = "2.3.13"

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime YukonEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice classes) and for the EndpointModule types
    // (TypeDescription, DynamicType.Builder, ElementMatcher) this module's own Kotlin code is
    // written against. compileOnly: at runtime, ByteBuddy comes from the root project's own
    // dependency once this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported Ktor 2.x version, per this project's no-muzzle
    // version-drift policy (see CLAUDE.md's "Endpoint instrumentation" status entry): a module
    // builds against its floor version and disables itself on a LinkageError against a version it
    // does not actually match. Never `implementation`: Ktor must never be shaded into the agent
    // jar, since it belongs to the target application, not to this agent.
    compileOnly("io.ktor:ktor-server-core-jvm:$ktorVersion")

    // Gives EndpointRegistry, EndpointInstrumentation, and BootstrapHolder to Ktor2TestAgent and
    // the integration test below, the same way :endpoints-spring-webmvc's own tests depend on the
    // root project.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Needed for net.bytebuddy.agent.builder.ResettableClassFileTransformer, the handle
    // Ktor2TestAgent holds between its own premain install and Ktor2ModuleTest's teardown.
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The default test suite runs against Ktor 2.3.13, the version this module compiles against.
    // ktor-server-cio-jvm is the embedded engine the test starts a real server with.
    testImplementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Ktor2TestAgent is installed as a real -javaagent on the test JVM's own command line, not through
// ByteBuddyAgent's self-attach: see Ktor2TestAgent's Javadoc for why self-attach from inside the
// test method is too late for this module specifically. The jar packages the whole compiled test
// output, which is small and already includes Ktor2TestAgent; everything the agent's premain
// references (EndpointInstrumentation, ByteBuddy) resolves off the test JVM's ordinary classpath,
// which is already in place before any agent's premain runs.
val ktor2TestAgentJar =
    tasks.register<Jar>("ktor2TestAgentJar") {
        archiveBaseName.set("ktor2-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(sourceSets.test.get().output)
        manifest {
            attributes["Premain-Class"] = "io.github.lukedevops.yukon.instrumentation.endpoints.ktor2.Ktor2TestAgent"
        }
    }

tasks.test {
    dependsOn(ktor2TestAgentJar)
    useJUnitPlatform()
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-javaagent:${ktor2TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
        },
    )
}

// A second JVM Test Suite against Ktor 2.0.3, the floor of the 2.x line, was attempted here the
// same way :endpoints-spring-webmvc adds a suite per supported major version. It was dropped:
// Ktor2ModuleTest's HTTP requests hang against a real 2.0.3 CIO server on this project's JDK 21
// toolchain, confirmed with the Yukon agent removed entirely, so the hang is a property of
// Ktor 2.0.3's own CIO engine and the kotlinx-coroutines-core 1.6.2 it pulls transitively, not
// of this module's advice; registration was independently confirmed correct against 2.0.3 by
// reading EndpointRegistry.endpoints() before the request that never returns. 2.3.13 remains the
// only version this module's tests run against, matching the "dropped with a note" allowance for
// a version the shared test source cannot actually exercise.
