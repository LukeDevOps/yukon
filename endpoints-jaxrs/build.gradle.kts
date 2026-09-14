import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    // EndpointModule, AdviceBinder, and the ByteBuddy-facing types this module is written
    // against. No JAX-RS artifact is a dependency anywhere in main: JaxRsModule reads annotation
    // names off ByteBuddy descriptions instead of matching against loaded JAX-RS types, and
    // ResourceMethodAdvice references nothing framework-specific at all.
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime YukonEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice class) and for the EndpointModule types
    // (TypeDescription, DynamicType.Builder, ElementMatcher, AnnotationSource) JaxRsModule is
    // written against. compileOnly: at runtime, ByteBuddy comes from the root project's own
    // dependency once this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:1.18.12")

    // Gives EndpointRegistry, EndpointInstrumentation, ProbeRegistry, YukonInstrumentation, and
    // BootstrapHolder to JaxRsTestAgent and the integration test below, the same way
    // :endpoints-spring-webmvc's own tests depend on the root project.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Needed for net.bytebuddy.agent.builder.ResettableClassFileTransformer, the handle
    // JaxRsTestAgent holds between its own premain install and the test's teardown.
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    // The default test suite runs against Jersey's Jakarta line: jersey-container-jdk-http hosts
    // a real JAX-RS server on the JDK's own HttpServer with no servlet container, jersey-hk2
    // supplies the dependency-injection provider Jersey needs to instantiate resource classes,
    // and jakarta.ws.rs-api is pinned explicitly even though Jersey already pulls it in
    // transitively.
    testImplementation("org.glassfish.jersey.containers:jersey-container-jdk-http:3.1.12")
    testImplementation("org.glassfish.jersey.inject:jersey-hk2:3.1.12")
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// JaxRsTestAgent is installed as a real -javaagent on the test JVM's own command line, not
// through ByteBuddyAgent's self-attach: the fixture resource classes are referenced from the
// test body, which Gradle's JUnit Platform integration loads while discovering @Test methods,
// before any test method's own first statement runs. A -javaagent installs the transformer
// during premain, which the JVM runs before any application class loads at all, discovery
// included, the same reasoning Ktor3TestAgent's Javadoc gives in full. The jar packages the
// whole compiled test output; everything the agent's premain references (YukonInstrumentation,
// EndpointInstrumentation, ByteBuddy) resolves off the test JVM's ordinary classpath, which is
// already in place before any agent's premain runs.
val jaxRsTestAgentJar =
    tasks.register<Jar>("jaxRsTestAgentJar") {
        archiveBaseName.set("jaxrs-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(sourceSets.test.get().output)
        manifest {
            attributes["Premain-Class"] = "io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs.JaxRsTestAgent"
        }
    }

tasks.test {
    dependsOn(jaxRsTestAgentJar)
    useJUnitPlatform()
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-javaagent:${jaxRsTestAgentJar.get().archiveFile.get().asFile.absolutePath}")
        },
    )
}

// A second JVM Test Suite exercises the javax.ws.rs namespace against Jersey 2.48, the last
// Jersey 2.x line, in its own forked test JVM with its own classpath. The resource fixture's
// imports differ by namespace (javax.ws.rs vs jakarta.ws.rs), so this suite owns its own source
// directory (src/javaxTest/kotlin) rather than reusing src/test/kotlin the way Ktor3Module's and
// SpringWebMvcModule's version suites reuse one shared file: those differ only by dependency
// version, never by import statement.
testing {
    suites {
        register<JvmTestSuite>("javaxTest") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/javaxTest/kotlin")
            }
            dependencies {
                // Unlike the built-in `test` suite, a suite registered here has no implicit
                // dependency on this same project's own production code, so JaxRsModule itself
                // has to be added explicitly.
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation("net.bytebuddy:byte-buddy:1.18.12")
                implementation("org.glassfish.jersey.containers:jersey-container-jdk-http:2.48")
                implementation("org.glassfish.jersey.inject:jersey-hk2:2.48")
                implementation("javax.ws.rs:javax.ws.rs-api:2.1.1")
            }
        }
    }
}

// Same -javaagent wiring as the default test task above, built from javaxTest's own compiled
// output so its JaxRsTestAgent resolves the javax.ws.rs fixture rather than the jakarta one.
val javaxTestAgentJar =
    tasks.register<Jar>("javaxTestAgentJar") {
        archiveBaseName.set("jaxrs-javax-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(testing.suites.named<JvmTestSuite>("javaxTest").map { it.sources.output })
        manifest {
            attributes["Premain-Class"] = "io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs.JaxRsTestAgent"
        }
    }

testing.suites.named<JvmTestSuite>("javaxTest") {
    targets {
        all {
            testTask.configure {
                dependsOn(javaxTestAgentJar)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-javaagent:${javaxTestAgentJar.get().archiveFile.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("javaxTest"))
}
