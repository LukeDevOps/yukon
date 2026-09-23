// Kotlin fixture classes compiled with class-based SAM conversion and lambdas
// (-Xsam-conversions=class -Xlambdas=class), for the forwarder table (ADR 0035). In this mode a
// reference passed as a Java functional interface compiles to a synthetic class whose method only
// calls the real function, a shape the root build's own indy-based mode never produces. The root
// build reads the compiled classes through a system property, as it does the other fixture
// modules; :endpoints-jdk-httpserver puts them on its test classpath for the end-to-end test.
plugins {
    kotlin("jvm")
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xsam-conversions=class", "-Xlambdas=class")
    }
}
