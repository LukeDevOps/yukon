import org.gradle.api.tasks.bundling.Jar

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    // The wire models and codec, and nothing else of the agent's: the agent itself reaches a test
    // JVM only as its shaded -javaagent jar, never as classes on the test classpath.
    implementation(project(":wire"))

    // compileOnly: an adopter who does not use JUnit pays nothing for OtherlodeExtension, the same
    // shape OpenTelemetry uses for opentelemetry-sdk-testing. JUnit's own launcher supplies the
    // real jar at test time for anyone who does add it. Pinned to the version already resolved
    // on this module's test classpath (checked with `./gradlew :testkit:dependencies
    // --configuration testRuntimeClasspath`), so main and test code compile against the same API.
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.10.1")

    testImplementation(kotlin("test"))

    // The end-to-end tests drive the agent's instrumentation and exporter in-process.
    testImplementation(project(":"))

    // byte-buddy-agent gives the end-to-end test ByteBuddyAgent.install() to self-attach.
    // Plain byte-buddy is needed too: OtherlodeInstrumentation.install()/uninstall() are typed in
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
    archiveBaseName.set("otherlode-testkit")
}

// The Scala fixture modules, wired in the way the root build wires them: a task dependency and two
// system properties each, never a test dependency, so no fixture class loads before a test installs
// instrumentation. The never-hit test for Scala's generated methods loads them through a child-first
// loader.
evaluationDependsOn(":fixtures-scala3")
evaluationDependsOn(":fixtures-scala2")
val scalaFixtureModules = listOf("scala3", "scala2")

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    dependsOn(scalaFixtureModules.map { ":fixtures-$it:classes" })
    doFirst {
        for (module in scalaFixtureModules) {
            val fixture = project(":fixtures-$module")
            systemProperty(
                "otherlode.fixtures.$module.dir",
                fixture.layout.buildDirectory
                    .dir("classes/scala/main")
                    .get()
                    .asFile.absolutePath,
            )
            systemProperty(
                "otherlode.fixtures.$module.classpath",
                fixture.configurations
                    .named("runtimeClasspath")
                    .get()
                    .asPath,
            )
        }
    }
}

// Proves OtherlodeExtension against the real, shaded agent jar rather than hand-built payloads or a
// self-attach. A distinct port (4329) and a distinct source set keep this suite from colliding
// with anything an adopter runs on the agent's own default port (4319), and from putting
// -javaagent on the plain `test` suite above, whose tests self-attach instead and must stay
// that way.
val agentTestCollectorPort = 4329

// Two one-class jars for the agentTest suite's dependency test, built here rather than taken from
// the suite's own classpath so which one loads is fully under the test's control. dep-used carries
// a pom.properties and is called from com.example.agenttarget; dep-unused has none, so its
// identity comes from the filename with an empty group, and nothing ever loads it.
val depUsed = sourceSets.create("depUsed")
val depUnused = sourceSets.create("depUnused")
val fixtureDependencyDir = layout.buildDirectory.dir("fixture-dependencies")
val depUsedJar by tasks.registering(Jar::class) {
    archiveFileName.set("dep-used-1.0.jar")
    destinationDirectory.set(fixtureDependencyDir)
    from(depUsed.output)
}
val depUnusedJar by tasks.registering(Jar::class) {
    archiveFileName.set("dep-unused-1.0.jar")
    destinationDirectory.set(fixtureDependencyDir)
    from(depUnused.output)
}

testing {
    suites {
        register<JvmTestSuite>("agentTest") {
            useJUnitJupiter("5.10.1")
            sources {
                kotlin.srcDir("src/agentTest/kotlin")
            }
            dependencies {
                // A registered suite, unlike the built-in `test` suite, does not inherit this
                // project's own main output automatically: project() gives it OtherlodeTestCollector
                // and OtherlodeExtension. kotlin("test") is a build-script-scoped helper, not
                // available inside a suite's own dependencies block, so this names the same
                // artifact directly, as the endpoints-spring-webmvc and endpoints-jaxrs suites
                // already do.
                implementation(project())
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation(files(depUsedJar, depUnusedJar))
            }
            targets {
                all {
                    testTask.configure {
                        dependsOn(rootProject.tasks.named("shadowJar"))
                        doFirst {
                            val agentJar =
                                rootProject.tasks
                                    .named("shadowJar", Jar::class.java)
                                    .get()
                                    .archiveFile
                                    .get()
                                    .asFile
                            jvmArgs(
                                "-javaagent:${agentJar.absolutePath}=" +
                                    "includePackages=com.example.agenttarget," +
                                    "flushIntervalSeconds=1," +
                                    "staticBaselineEnabled=true," +
                                    "serviceName=testkit-agent-test," +
                                    "endpointsEnabled=false," +
                                    "endpoint=http://localhost:$agentTestCollectorPort",
                                "-Dotherlode.testkit.port=$agentTestCollectorPort",
                            )
                        }
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("agentTest"))
}
