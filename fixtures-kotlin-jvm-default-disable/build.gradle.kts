// Kotlin fixture classes compiled with -jvm-default=disable, the default up to language version
// 2.1, for generated-method marking (BranchSiteAnalyzer, ADR 0026). In this mode an interface
// default method is abstract and its real body lives in the interface's $DefaultImpls class, a
// shape the root build's own mode never produces. Never on the test classpath directly: see the
// comment in fixtures-scala3/build.gradle.kts for why.
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

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
        jvmDefault.set(JvmDefaultMode.DISABLE)
    }
}
