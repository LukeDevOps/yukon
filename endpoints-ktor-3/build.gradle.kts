import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val byteBuddyVersion = "1.18.12"
val ktorVersion = "3.5.2"

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime YukonEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice classes) and for the EndpointModule types
    // (TypeDescription, DynamicType.Builder, ElementMatcher) this module's own Kotlin code is
    // written against. compileOnly: at runtime, ByteBuddy comes from the root project's own
    // dependency once this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the latest Ktor 3.x version, per this project's no-muzzle version-drift
    // policy (see CLAUDE.md's "Endpoint instrumentation" status entry): a module builds against
    // its own version and disables itself on a LinkageError against a version it does not
    // actually match. Never `implementation`: Ktor must never be shaded into the agent jar, since
    // it belongs to the target application, not to this agent.
    compileOnly("io.ktor:ktor-server-core-jvm:$ktorVersion")

    // Gives EndpointRegistry, EndpointInstrumentation, and BootstrapHolder to Ktor3TestAgent and
    // the integration test below, the same way :endpoints-spring-webmvc's own tests depend on the
    // root project.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Needed for net.bytebuddy.agent.builder.ResettableClassFileTransformer, the handle
    // Ktor3TestAgent holds between its own premain install and Ktor3ModuleTest's teardown.
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The default test suite runs against Ktor 3.5.2, the version this module compiles against.
    // ktor-server-cio-jvm is the embedded engine the test starts a real server with.
    testImplementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Ktor3TestAgent is installed as a real -javaagent on the test JVM's own command line, not through
// ByteBuddyAgent's self-attach: see Ktor3TestAgent's Javadoc for why self-attach from inside the
// test method is too late for this module specifically. The jar packages the whole compiled test
// output, which is small and already includes Ktor3TestAgent; everything the agent's premain
// references (EndpointInstrumentation, ByteBuddy) resolves off the test JVM's ordinary classpath,
// which is already in place before any agent's premain runs.
val ktor3TestAgentJar =
    tasks.register<Jar>("ktor3TestAgentJar") {
        archiveBaseName.set("ktor3-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(sourceSets.test.get().output)
        manifest {
            attributes["Premain-Class"] = "io.github.lukedevops.yukon.instrumentation.endpoints.ktor3.Ktor3TestAgent"
        }
    }

tasks.test {
    dependsOn(ktor3TestAgentJar)
    useJUnitPlatform()
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-javaagent:${ktor3TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
        },
    )
}

// A second JVM Test Suite exercises the exact same test sources (src/test/kotlin) against Ktor
// 3.0.3, the floor of the 3.x line, in its own forked test JVM with its own classpath, so the one
// Ktor3Module this module ships is proven against both ends of Ktor 3.x without a per-version
// module or a per-version copy of the test file.
testing {
    suites {
        register<JvmTestSuite>("ktor3_0Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                // Unlike the built-in `test` suite, a suite registered here has no implicit
                // dependency on this same project's own production code, so Ktor3Module itself
                // has to be added explicitly.
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("io.ktor:ktor-server-core-jvm:3.0.3")
                implementation("io.ktor:ktor-server-cio-jvm:3.0.3")
            }
        }
    }
}

// Same -javaagent wiring as the default test task above, built from ktor3_0Test's own compiled
// output so its Ktor3TestAgent resolves Ktor 3.0.3's classes rather than 3.5.2's.
val ktor30TestAgentJar =
    tasks.register<Jar>("ktor30TestAgentJar") {
        archiveBaseName.set("ktor3-0-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(testing.suites.named<JvmTestSuite>("ktor3_0Test").map { it.sources.output })
        manifest {
            attributes["Premain-Class"] = "io.github.lukedevops.yukon.instrumentation.endpoints.ktor3.Ktor3TestAgent"
        }
    }

testing.suites.named<JvmTestSuite>("ktor3_0Test") {
    targets {
        all {
            testTask.configure {
                dependsOn(ktor30TestAgentJar)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-javaagent:${ktor30TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("ktor3_0Test"))
}
