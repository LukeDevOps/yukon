// The wire schema and the code that turns payloads into its bytes and back: the payload models,
// the protobuf codec, and the route normaliser a collector applies to an endpoint's identity.
// The agent and the testkit both build on this module and nothing else of each other, so the
// testkit never puts the agent's own classes or its dependencies on an adopter's test classpath.
plugins {
    kotlin("jvm") version "2.2.21"
    id("com.google.protobuf") version "0.9.4"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
}

// The schema stays at the repository's own src/main/proto, where buf.yaml and the Buf workflow
// publish it from.
sourceSets.main {
    proto {
        srcDir(rootProject.file("src/main/proto"))
    }
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

tasks.jar {
    archiveBaseName.set("otherlode-wire")
}
