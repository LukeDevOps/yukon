plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime YukonEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // Lets HiddenHandlerNamingTest call the seam directly. Compile-only for the same reason as
    // above: at runtime the test must reach the one copy BootstrapHolder puts on the bootstrap loader.
    testCompileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice classes) and for the EndpointModule types
    // (TypeDescription, DynamicType.Builder, ElementMatcher) this module's own Kotlin code is
    // written against. compileOnly: at runtime, ByteBuddy comes from the root project's own
    // dependency once this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:1.18.12")

    // Gives EndpointRegistry, EndpointInstrumentation, and BootstrapHolder to the integration
    // test below. The root project depends on this module's main sources and this module's tests
    // depend on the root project's main sources; Gradle allows that since no task cycle results
    // (root's compileKotlin never depends on this module's compileTestKotlin).
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Lets JdkHttpServerModuleTest self-attach with ByteBuddyAgent.install() and weave real
    // advice into the JDK's own HttpServer classes, the same way EndpointInstrumentationTest does
    // for its fixture framework.
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")
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
    // Each integration test here must install its endpoint advice before the JDK's ServerImpl first
    // loads, and the lambda factory hook changes java.lang.invoke for the whole JVM. One JVM per
    // test class keeps each class's setup from leaking into the next.
    forkEvery = 1
}
