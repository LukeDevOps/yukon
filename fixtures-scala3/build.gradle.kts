// Scala 3 fixture classes for the analyser's default-getter resolution
// (BranchSiteAnalyzer, ADR 0023). Never on the test classpath directly: the root
// build wires its compiled output and runtime classpath in as system properties
// instead, loaded through a child-first classloader at test time, the same way
// the existing Kotlin fixtures under com.example.target are loaded. Putting the
// module on the test classpath would let JUnit discovery load these classes
// before instrumentation is installed, defeating the fixture's purpose.
plugins {
    scala
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.scala-lang:scala3-library_3:3.3.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
