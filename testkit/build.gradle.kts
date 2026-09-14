plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    // Reuses the root project's wire models and codec (Payloads.kt, ProtoPayloadCodec) directly,
    // rather than duplicating the wire-mapping code in a separately published module.
    implementation(project(":"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(kotlin("test"))

    // byte-buddy-agent gives the end-to-end test ByteBuddyAgent.install() to self-attach.
    // Plain byte-buddy is needed too: YukonInstrumentation.install()/uninstall() are typed in
    // terms of net.bytebuddy.agent.builder.ResettableClassFileTransformer, which is only an
    // implementation (not api) dependency of the root project and so does not arrive on this
    // module's compile classpath transitively.
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    // Gives the endpoint end-to-end test JdkHttpServerModule, the real HttpServer endpoint module.
    testImplementation(project(":endpoints-jdk-httpserver"))

    // EndpointModule is only an implementation dependency of :endpoints-jdk-httpserver, so it does
    // not arrive transitively; needed directly for EndpointInstrumentation's List<EndpointModule>.
    testImplementation(project(":endpoints-api"))
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    archiveBaseName.set("yukon-testkit")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}
