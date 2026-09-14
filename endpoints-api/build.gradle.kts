plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    // Only ByteBuddy's types (TypeDescription, DynamicType.Builder, ElementMatcher, Advice,
    // ClassFileLocator, TypePool) are part of this module's surface. A per-framework module
    // subproject implements EndpointModule against these types without depending on the root
    // project, which is what lets the root project depend on both without a cycle.
    implementation("net.bytebuddy:byte-buddy:1.18.12")

    testImplementation(kotlin("test"))

    // Lets AdviceBinderTest self-attach with ByteBuddyAgent.install() and prove AdviceBinder's
    // resolved Advice weaves correctly through a real AgentBuilder transformer, the same way the
    // root project's own instrumentation tests do.
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
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
