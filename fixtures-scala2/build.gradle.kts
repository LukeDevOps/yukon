// Scala 2.13 fixture classes for the analyser's default-getter resolution
// (BranchSiteAnalyzer, ADR 0023). Never on the test classpath directly: see the
// comment in fixtures-scala3/build.gradle.kts for why.
plugins {
    scala
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.scala-lang:scala-library:2.13.15")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
