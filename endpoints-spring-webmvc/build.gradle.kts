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

    // Compiled against the oldest supported Spring Framework version, per this project's
    // no-muzzle version-drift policy (see CLAUDE.md's "Endpoint instrumentation" status entry): a
    // module builds against its floor version and disables itself on a LinkageError against a
    // newer or older one it does not actually match. Never `implementation`: Spring must never be
    // shaded into the agent jar, since it belongs to the target application, not to this agent.
    // javax.servlet-api is needed only so javac can resolve method signatures Spring's own API
    // mentions (HttpServletRequest on `handleMatch`); no advice class in this module names a
    // servlet type itself.
    compileOnly("org.springframework:spring-webmvc:5.3.39")
    compileOnly("javax.servlet:javax.servlet-api:4.0.1")

    // Gives EndpointRegistry, EndpointInstrumentation, and BootstrapHolder to the integration
    // test below, the same way :endpoints-jdk-httpserver's own tests depend on the root project.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Lets the test self-attach with ByteBuddyAgent.install() and weave real advice into Spring's
    // own classes, the same way :endpoints-jdk-httpserver's test does for the JDK's HttpServer.
    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The default test suite runs against Spring Framework 6.x, driven through MockMvc, which
    // runs a real DispatcherServlet so both the registration and dispatch hooks fire without a
    // servlet container.
    testImplementation("org.springframework:spring-webmvc:6.2.19")
    testImplementation("org.springframework:spring-test:6.2.19")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
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

// A second, and optionally a third, JVM Test Suite exercise the exact same test sources
// (src/test/kotlin) against Spring Framework 7.x and 5.3.x, each in its own forked test JVM with
// its own classpath, so the one SpringWebMvcModule this module ships is proven against every
// supported major version without a per-version module or a per-version copy of the test file.
testing {
    suites {
        register<JvmTestSuite>("spring7Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                // Unlike the built-in `test` suite, a suite registered here has no implicit
                // dependency on this same project's own production code, so SpringWebMvcModule
                // itself has to be added explicitly.
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("org.springframework:spring-webmvc:7.0.9")
                implementation("org.springframework:spring-test:7.0.9")
                implementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
            }
            targets {
                all {
                    testTask.configure {
                        jvmArgs("-Djdk.attach.allowAttachSelf=true")
                    }
                }
            }
        }

        register<JvmTestSuite>("spring53Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("org.springframework:spring-webmvc:5.3.39")
                implementation("org.springframework:spring-test:5.3.39")
                implementation("javax.servlet:javax.servlet-api:4.0.1")
            }
            targets {
                all {
                    testTask.configure {
                        jvmArgs("-Djdk.attach.allowAttachSelf=true")
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("spring7Test"))
    dependsOn(testing.suites.named("spring53Test"))
}
