import org.gradle.api.tasks.bundling.Jar
import org.gradle.internal.jvm.Jvm
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

dependencies {
    // The stub collector decodes the root project's generated protobuf
    // classes directly (io.github.lukedevops.yukon.proto.*); the demo server
    // and client don't reference Yukon at all, matching how a real
    // consumer's app never depends on the agent at compile time.
    implementation(project(":"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val demoServerMainClass = "io.github.lukedevops.demo.server.DemoServerMainKt"
val demoClientMainClass = "io.github.lukedevops.demo.client.DemoClientMainKt"
val stubCollectorMainClass = "io.github.lukedevops.demo.collector.StubCollectorMainKt"
val flushIntervalSeconds = 3L
val portWaitTimeoutSeconds = 15L

tasks.register("runDemo") {
    group = "application"
    description = "Runs the stub collector, the -javaagent-instrumented demo server, and the demo client end to end."
    dependsOn(rootProject.tasks.named("shadowJar"), tasks.named("classes"))

    doLast {
        val javaBin = Jvm.current().javaExecutable.absolutePath
        val demoClasspath = sourceSets["main"].runtimeClasspath.asPath
        val agentJar =
            rootProject.tasks
                .named("shadowJar", Jar::class.java)
                .get()
                .archiveFile
                .get()
                .asFile

        println("yukon demo: starting stub collector")
        val collector = startProcess("collector", javaBin, listOf("-cp", demoClasspath, stubCollectorMainClass))
        try {
            waitForPort(DemoPorts.COLLECTOR_PORT, portWaitTimeoutSeconds)

            println("yukon demo: starting instrumented demo server")
            val agentArg =
                "-javaagent:${agentJar.absolutePath}=" +
                    "serviceName=yukon-demo," +
                    "flushIntervalSeconds=$flushIntervalSeconds," +
                    "endpoint=http://localhost:${DemoPorts.COLLECTOR_PORT}," +
                    "includePackages=io.github.lukedevops.demo.server," +
                    "staticBaselineEnabled=true"
            val server = startProcess("server", javaBin, listOf(agentArg, "-cp", demoClasspath, demoServerMainClass))
            try {
                waitForPort(DemoPorts.SERVER_PORT, portWaitTimeoutSeconds)

                println("yukon demo: running demo client")
                val client = startProcess("client", javaBin, listOf("-cp", demoClasspath, demoClientMainClass))
                client.process.waitFor()
                client.outputThread.join()

                println("yukon demo: waiting for one more flush and the static baseline scan before shutdown")
                Thread.sleep((flushIntervalSeconds + 2) * 1000)
            } finally {
                gracefulShutdown("server", server, DemoPorts.SERVER_PORT)
            }
        } finally {
            gracefulShutdown("collector", collector, DemoPorts.COLLECTOR_PORT)
        }
        println("yukon demo: done")
    }
}

val springDemoClientMainClass = "io.github.lukedevops.demo.client.SpringDemoClientMainKt"

// Boot's context refresh is slower to reach a listening port than the plain demo server's bare
// HttpServer.create/start, so this task gets its own, longer port-wait timeout rather than
// sharing portWaitTimeoutSeconds.
val springPortWaitTimeoutSeconds = 60L

tasks.register("runSpringDemo") {
    group = "application"
    description =
        "Runs the stub collector, the -javaagent-instrumented Spring Boot fat-jar demo, and its client end to end."
    dependsOn(
        rootProject.tasks.named("shadowJar"),
        project(":demo-spring").tasks.named("bootJar"),
        tasks.named("classes"),
    )

    doLast {
        val javaBin = Jvm.current().javaExecutable.absolutePath
        val demoClasspath = sourceSets["main"].runtimeClasspath.asPath
        val agentJar =
            rootProject.tasks
                .named("shadowJar", Jar::class.java)
                .get()
                .archiveFile
                .get()
                .asFile
        val springBootJar =
            project(":demo-spring")
                .tasks
                .named("bootJar", Jar::class.java)
                .get()
                .archiveFile
                .get()
                .asFile

        println("yukon spring demo: starting stub collector")
        val collector = startProcess("spring-collector", javaBin, listOf("-cp", demoClasspath, stubCollectorMainClass))
        try {
            waitForPort(DemoPorts.COLLECTOR_PORT, portWaitTimeoutSeconds)

            println("yukon spring demo: starting instrumented Spring Boot fat jar")
            val agentArg =
                "-javaagent:${agentJar.absolutePath}=" +
                    "serviceName=yukon-spring-demo," +
                    "serviceVersion=spring-demo," +
                    "flushIntervalSeconds=$flushIntervalSeconds," +
                    "endpoint=http://localhost:${DemoPorts.COLLECTOR_PORT}," +
                    "includePackages=io.github.lukedevops.demo.spring," +
                    "staticBaselineEnabled=true"
            val server =
                startProcess(
                    "spring-server",
                    javaBin,
                    listOf(agentArg, "-jar", springBootJar.absolutePath, "--server.port=${DemoPorts.SPRING_SERVER_PORT}"),
                )
            try {
                waitForPort(DemoPorts.SPRING_SERVER_PORT, springPortWaitTimeoutSeconds)

                println("yukon spring demo: running spring demo client")
                val client = startProcess("spring-client", javaBin, listOf("-cp", demoClasspath, springDemoClientMainClass))
                client.process.waitFor()
                client.outputThread.join()

                println("yukon spring demo: waiting for one more flush and the static baseline scan before shutdown")
                Thread.sleep((flushIntervalSeconds + 2) * 1000)
            } finally {
                gracefulShutdown("spring-server", server, DemoPorts.SPRING_SERVER_PORT)
            }
        } finally {
            gracefulShutdown("spring-collector", collector, DemoPorts.COLLECTOR_PORT)
        }
        println("yukon spring demo: done")
    }
}

val stackEndpoint = providers.gradleProperty("yukonEndpoint").getOrElse("http://localhost:4319")
val stackAgentToken = providers.gradleProperty("yukonAgentToken").getOrElse("local-stack-agent-token")
val stackServerUrl = providers.gradleProperty("yukonServerUrl").getOrElse("http://localhost:4320")
val stackServerApiKey = providers.gradleProperty("yukonServerApiKey").getOrElse("yk_local-stack-api-key")
val stackServiceVersion = providers.gradleProperty("yukonServiceVersion").getOrElse("stack-demo")
val stackServiceName = "yukon-demo"

tasks.register("runDemoStack") {
    group = "application"
    description =
        "Runs the -javaagent-instrumented demo server and client against a real collector, " +
        "then prints what the yukon-server read API reports for it."
    dependsOn(rootProject.tasks.named("shadowJar"), tasks.named("classes"))

    doLast {
        val javaBin = Jvm.current().javaExecutable.absolutePath
        val demoClasspath = sourceSets["main"].runtimeClasspath.asPath
        val agentJar =
            rootProject.tasks
                .named("shadowJar", Jar::class.java)
                .get()
                .archiveFile
                .get()
                .asFile

        if (httpGet("$stackEndpoint/healthz", token = null).status != 200) {
            throw GradleException(
                "yukon demo: no collector answering at $stackEndpoint/healthz. Start the stack with " +
                    "`docker compose --profile stack up --build` in yukon-server, or pass -PyukonEndpoint=<url>.",
            )
        }

        val runInstanceId = UUID.randomUUID().toString()
        println("yukon demo: starting instrumented demo server against $stackEndpoint as instance $runInstanceId")
        val agentArg =
            "-javaagent:${agentJar.absolutePath}=" +
                "serviceName=$stackServiceName," +
                "serviceVersion=$stackServiceVersion," +
                "serviceInstanceId=$runInstanceId," +
                "flushIntervalSeconds=$flushIntervalSeconds," +
                "endpoint=$stackEndpoint," +
                "includePackages=io.github.lukedevops.demo.server," +
                "staticBaselineEnabled=true"
        val server =
            startProcess(
                "server",
                javaBin,
                listOf(agentArg, "-cp", demoClasspath, demoServerMainClass),
                env = mapOf("YUKON_AUTH_TOKEN" to stackAgentToken),
            )
        try {
            waitForPort(DemoPorts.SERVER_PORT, portWaitTimeoutSeconds)

            println("yukon demo: running demo client")
            val client = startProcess("client", javaBin, listOf("-cp", demoClasspath, demoClientMainClass))
            client.process.waitFor()
            client.outputThread.join()

            println("yukon demo: waiting for one more flush and the static baseline scan before shutdown")
            Thread.sleep((flushIntervalSeconds + 2) * 1000)
        } finally {
            val seenBeforeShutdown = instanceLastSeen(runInstanceId)
            gracefulShutdown("server", server, DemoPorts.SERVER_PORT)
            awaitShutdownFlush(runInstanceId, seenBeforeShutdown)
        }

        printStackReport()
        println("yukon demo: done")
    }
}

class HttpResult(
    val status: Int,
    val body: String,
)

fun httpGet(
    url: String,
    token: String?,
): HttpResult {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 2000
    connection.readTimeout = 10000
    if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
    return try {
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        HttpResult(status, stream?.bufferedReader()?.readText() ?: "")
    } catch (e: IOException) {
        HttpResult(-1, e.toString())
    } finally {
        connection.disconnect()
    }
}

fun readApi(path: String): Map<*, *> {
    val result = httpGet("$stackServerUrl/api/v1/services/$stackServiceName$path", stackServerApiKey)
    if (result.status != 200) {
        throw GradleException("yukon demo: GET $path returned ${result.status}: ${result.body}")
    }
    return groovy.json.JsonSlurper().parseText(result.body) as Map<*, *>
}

// last_seen_at of this run's instance as the server reports it, or null
// if the server has not heard from the instance yet.
fun instanceLastSeen(instanceId: String): Instant? {
    val instances = readApi("/instances?version=$stackServiceVersion")["instances"] as List<*>
    val match = instances.map { it as Map<*, *> }.firstOrNull { it["instance_id"] == instanceId } ?: return null
    return Instant.parse(match["last_seen_at"] as String)
}

// The agent's shutdown hook sends one last delta batch, and the collector
// forwards it asynchronously, so it can still be in flight after the demo
// server has exited. Every delta batch moves the instance's last_seen_at,
// so wait until this run's instance has been seen again since just before
// the shutdown request; that is the final flush landing. The report would
// otherwise be read before the last hits arrived, and because the store
// keeps data across runs, "any probes known" cannot tell one run from the
// last.
fun awaitShutdownFlush(
    instanceId: String,
    seenBefore: Instant?,
) {
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        val seenNow = instanceLastSeen(instanceId)
        if (seenNow != null && (seenBefore == null || seenNow.isAfter(seenBefore))) return
        Thread.sleep(250)
    }
    println(
        "yukon demo: warning: the shutdown flush for instance $instanceId did not reach the server within 15s; the report may be missing its final hits",
    )
}

// The report covers every instance of this service and version the server
// has ever seen, so repeated runs against the same stack accumulate.
fun printStackReport() {
    val version = "?version=$stackServiceVersion"
    val report = readApi("/report$version")
    val probes = report["probes"] as Map<*, *>
    val classes = report["classes"] as Map<*, *>
    val instances = report["instances"] as Map<*, *>
    println("yukon demo: report for $stackServiceName@$stackServiceVersion from $stackServerUrl (${instances["total"]} instance(s) so far)")
    println("  probes: known=${probes["known"]} hit=${probes["hit"]} never_hit=${probes["never_hit"]}")
    println("  classes: declared=${classes["declared"]} loaded=${classes["loaded"]} never_loaded=${classes["never_loaded"]}")

    println("  NEVER HIT:")
    for (probe in readApi("/never-hit$version")["probes"] as List<*>) {
        val p = probe as Map<*, *>
        val detail = p["branch_index"]?.let { "branch $it" } ?: p["kind"]
        println("    ${p["class_name"]}#${p["method_name"]}:${p["line"]} ($detail)")
    }
    println("  NEVER LOADED:")
    for (cls in readApi("/never-loaded$version")["classes"] as List<*>) {
        val c = cls as Map<*, *>
        println("    ${c["class_name"]} (${(c["methods"] as List<*>).size} methods)")
    }
}

class DemoProcess(
    val process: Process,
    val outputThread: Thread,
)

// Redirect.INHERIT would inherit the Gradle daemon's own stdio, not this
// build invocation's terminal, so child output would silently disappear.
// Forwarding each line through println instead routes it through Gradle's
// own logging, which does reach the console regardless of the daemon. The
// output thread is joined after the process exits so the collector's
// shutdown-hook report (printed as it's torn down) isn't cut off.
fun startProcess(
    tag: String,
    javaBin: String,
    args: List<String>,
    env: Map<String, String> = emptyMap(),
): DemoProcess {
    val builder =
        ProcessBuilder(listOf(javaBin) + args)
            .redirectErrorStream(true)
    builder.environment().putAll(env)
    val process = builder.start()
    val outputThread =
        Thread({
            process.inputStream.bufferedReader().forEachLine { println("[$tag] $it") }
        }, "yukon-demo-$tag-output").apply {
            isDaemon = true
            start()
        }
    return DemoProcess(process, outputThread)
}

// POSTing to /__shutdown lets each process exit itself and flush any final output first;
// destroy() (SIGTERM) is only a fallback if that doesn't work. The wait outlasts the
// agent's own ten-second shutdown flush budget, so a slow collector is never the
// reason the final flush is cut off.
fun gracefulShutdown(
    tag: String,
    demoProcess: DemoProcess,
    port: Int,
) {
    try {
        val connection = URI("http://localhost:$port/__shutdown").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        connection.responseCode
        connection.disconnect()
    } catch (e: IOException) {
        println("yukon demo: graceful shutdown request to $tag failed ($e), falling back to destroy")
    }
    if (!demoProcess.process.waitFor(15, TimeUnit.SECONDS)) {
        demoProcess.process.destroy()
        demoProcess.process.waitFor()
    }
    demoProcess.outputThread.join()
}

fun waitForPort(
    port: Int,
    timeoutSeconds: Long,
) {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket("localhost", port).close()
            return
        } catch (_: IOException) {
            Thread.sleep(200)
        }
    }
    throw GradleException("yukon demo: timed out waiting for port $port")
}

object DemoPorts {
    const val COLLECTOR_PORT = 4319
    const val SERVER_PORT = 8085
    const val SPRING_SERVER_PORT = 8090
}
