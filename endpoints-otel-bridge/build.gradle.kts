plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

// The oldest 2.x release of the unshaded artifact: instrumentation-api 2.0.0 already carries
// HttpServerAttributesExtractor.onEnd and internal.HttpRouteState at the names this module's
// advice is written against (see OnEndAdvice's own KDoc for what was verified and how). Compiling
// against the floor version, never a newer one, is this project's no-muzzle policy: a module
// disables itself once on a LinkageError against a version its advice does not match, rather than
// pinning to whatever is newest today.
val openTelemetryInstrumentationApiVersion = "2.0.0"

// A newer release, matched to the OpenTelemetry SDK versions below, for the module's own tests:
// they drive a real Instrumenter end to end, which needs more of the API surface than the advice
// class itself touches.
val openTelemetryInstrumentationApiTestVersion = "2.31.1"
val openTelemetrySdkVersion = "1.65.0"

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime YukonEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice class) and for the EndpointModule types
    // (TypeDescription, DynamicType.Builder, ElementMatcher) this module's own Kotlin code is
    // written against. compileOnly: at runtime, ByteBuddy comes from the root project's own
    // dependency once this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:1.18.12")

    // Never shaded into the agent jar: OpenTelemetry, when present at all, belongs to the target
    // application or to the separate OpenTelemetry javaagent, never to this one. OnEndAdvice's own
    // bytecode is inlined directly into whichever copy is present at the woven call site; this
    // dependency only lets javac resolve its signature.
    compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:$openTelemetryInstrumentationApiVersion")

    // Gives EndpointRegistry, EndpointInstrumentation, and BootstrapHolder to the integration
    // test below, the same way :endpoints-jdk-httpserver's own tests depend on the root project.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Lets the test self-attach with ByteBuddyAgent.install() and weave real advice into a real
    // OpenTelemetry Instrumenter's own classes, the same way :endpoints-jdk-httpserver's test does
    // for the JDK's HttpServer.
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    // A newer instrumentation-api release than the advice class compiles against, plus a plain
    // OpenTelemetry SDK (no OpenTelemetryExtension convenience) so the test builds and drives a
    // real Instrumenter, ending real spans an InMemorySpanExporter captures.
    testImplementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:$openTelemetryInstrumentationApiTestVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk:$openTelemetrySdkVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:$openTelemetrySdkVersion")
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
