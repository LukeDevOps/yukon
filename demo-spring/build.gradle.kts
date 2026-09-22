import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "4.1.1"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    // Gradle's own platform support manages Spring Boot's dependency versions without the
    // io.spring.dependency-management plugin, which the Boot 4 Gradle plugin no longer requires
    // (see "Managing Dependencies" in the Spring Boot Gradle plugin reference docs).
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // On the classpath and never used, so the stub collector's dependency report shows it as
    // unloaded. The Boot BOM manages its version, so none is given here: a pinned one would be
    // raised to the BOM's regardless.
    runtimeOnly("org.apache.commons:commons-lang3")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

springBoot {
    mainClass.set("io.github.lukedevops.demo.spring.SpringDemoApplicationKt")
}

tasks.bootJar {
    archiveFileName.set("demo-spring.jar")
}

// The plain jar is never run; only bootJar's fat jar is what runSpringDemo launches with
// -javaagent, the same reasoning the root project's own plain jar task carries a classifier for.
tasks.jar {
    enabled = false
}
