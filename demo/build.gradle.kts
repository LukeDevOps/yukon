import org.gradle.api.tasks.bundling.Jar
import org.gradle.internal.jvm.Jvm
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.lukedevops"

repositories {
    mavenCentral()
}

// The demo server and client run on this classpath, not the module's runtime classpath. That one
// carries the root project's unshaded classes for the stub collector, and on a server's `-cp` it
// would load the agent ahead of the shaded `-javaagent` jar and list the agent's own libraries as
// the demo's dependencies.
val demoAppRuntime by configurations.creating

dependencies {
    // The stub collector decodes the root project's generated protobuf
    // classes directly (io.github.lukedevops.yukon.proto.*); the demo server
    // and client don't reference Yukon at all, matching how a real
    // consumer's app never depends on the agent at compile time.
    implementation(project(":"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(kotlin("test"))

    demoAppRuntime(kotlin("stdlib"))
}

tasks.test {
    useJUnitPlatform()
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

fun demoAppClasspath(): String = (sourceSets["main"].output + demoAppRuntime).asPath

tasks.register("runDemo") {
    group = "application"
    description = "Runs the stub collector, the -javaagent-instrumented demo server, and the demo client end to end."
    dependsOn(rootProject.tasks.named("shadowJar"), tasks.named("classes"))

    doLast {
        val javaBin = Jvm.current().javaExecutable.absolutePath
        val demoClasspath = sourceSets["main"].runtimeClasspath.asPath
        val appClasspath = demoAppClasspath()
        val agentJar =
            rootProject.tasks
                .named("shadowJar", Jar::class.java)
                .get()
                .archiveFile
                .get()
                .asFile

        println("yukon demo: starting stub collector")
        val (collector, collectorPort) = startStubCollector("collector", javaBin, demoClasspath)
        try {
            println("yukon demo: starting instrumented demo server")
            val agentArg =
                "-javaagent:${agentJar.absolutePath}=" +
                    "serviceName=yukon-demo," +
                    "flushIntervalSeconds=$flushIntervalSeconds," +
                    "endpoint=http://localhost:$collectorPort," +
                    "includePackages=io.github.lukedevops.demo.server," +
                    "staticBaselineEnabled=true"
            val server = startProcess("server", javaBin, listOf(agentArg, "-cp", appClasspath, demoServerMainClass))
            try {
                waitForPort(DemoPorts.SERVER_PORT, portWaitTimeoutSeconds)

                println("yukon demo: running demo client")
                val client = startProcess("client", javaBin, listOf("-cp", appClasspath, demoClientMainClass))
                client.process.waitFor()
                client.outputThread.join()

                println("yukon demo: waiting for one more flush and the static baseline scan before shutdown")
                Thread.sleep((flushIntervalSeconds + 2) * 1000)
            } finally {
                gracefulShutdown("server", server, DemoPorts.SERVER_PORT)
            }
        } finally {
            gracefulShutdown("collector", collector, collectorPort)
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
        val appClasspath = demoAppClasspath()
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
        val (collector, collectorPort) = startStubCollector("spring-collector", javaBin, demoClasspath)
        try {
            println("yukon spring demo: starting instrumented Spring Boot fat jar")
            val agentArg =
                "-javaagent:${agentJar.absolutePath}=" +
                    "serviceName=yukon-spring-demo," +
                    "serviceVersion=spring-demo," +
                    "flushIntervalSeconds=$flushIntervalSeconds," +
                    "endpoint=http://localhost:$collectorPort," +
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
                val client = startProcess("spring-client", javaBin, listOf("-cp", appClasspath, springDemoClientMainClass))
                client.process.waitFor()
                client.outputThread.join()

                println("yukon spring demo: waiting for one more flush and the static baseline scan before shutdown")
                Thread.sleep((flushIntervalSeconds + 2) * 1000)
            } finally {
                gracefulShutdown("spring-server", server, DemoPorts.SPRING_SERVER_PORT)
            }
        } finally {
            gracefulShutdown("spring-collector", collector, collectorPort)
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
        val demoClasspath = demoAppClasspath()
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

// Server ADR 0030's condition parts as one line: code as sent, a string
// literal quoted, a placeholder as an ellipsis.
fun conditionText(parts: List<*>): String =
    parts.joinToString("") { part ->
        val p = part as Map<*, *>
        when (p["kind"]) {
            "string_literal" -> "\"${p["text"]}\""
            "placeholder" -> "…"
            else -> p["text"] as String
        }
    }

// The result a never-hit outcome did not reach. The agent writes a condition
// as its fall-through side reads it (ADR 0037).
fun neverHappened(outcome: Map<*, *>): String =
    when (outcome["role"]) {
        "fall_through" -> "was never true"
        "taken" -> "was never false"
        "case" -> "never took " + conditionText(outcome["case_label"] as List<*>).ifEmpty { "case ${outcome["case_key"]}" }
        "default" -> "never took the default"
        else -> "never ran"
    }

// The lines only this outcome reaches, or a note that it guards nothing.
fun guardedText(outcome: Map<*, *>): String {
    fun ranges(key: String) =
        (outcome[key] as List<*>).joinToString(", ") { range ->
            val r = range as Map<*, *>
            val lines = if (r["first_line"] == r["last_line"]) "${r["first_line"]}" else "${r["first_line"]}-${r["last_line"]}"
            "${r["source_file"]}:$lines"
        }
    val whole = ranges("guarded_lines")
    val part = ranges("partly_guarded_lines")
    return when {
        whole.isNotEmpty() && part.isNotEmpty() -> "only path to $whole, partly $part"
        whole.isNotEmpty() -> "only path to $whole"
        part.isNotEmpty() -> "partly the path to $part"
        else -> "guards no code of its own"
    }
}

// Each outcome that put a never-hit site row on the list, as its condition,
// the result that never happened and the lines only it reaches.
fun siteFindings(row: Map<*, *>): List<String> {
    val condition = conditionText(row["condition"] as List<*>).ifEmpty { "branch" }
    return (row["outcomes"] as List<*>)
        .map { it as Map<*, *> }
        .filter { it["in_finding"] == true }
        .map { "`$condition` ${neverHappened(it)}, ${guardedText(it)}" }
}

// A cluster's root as one line (server ADR 0032). An untaken outcome reads as
// its site row does, then the method that holds it. A root reached from hit
// names the methods with hits that call it.
fun clusterRootText(root: Map<*, *>): String {
    val method = "${root["class_name"]}#${root["method_name"]}"
    return when (val kind = root["root_kind"] as String) {
        "untaken_outcome" -> {
            val site = root["site"] as Map<*, *>?
            val finding = site?.let { siteFindings(it).firstOrNull() } ?: "an untaken branch"
            "$finding, in $method:${site?.get("line") ?: root["line"]} (untaken outcome)"
        }

        "reached_from_hit" -> {
            val callers = (root["reached_from"] as List<*>).joinToString(", ") { "${(it as Map<*, *>)["class_name"]}#${it["method_name"]}" }
            "$method (reached from hit, called from $callers)"
        }

        else -> {
            "$method (${kind.replace('_', ' ')})"
        }
    }
}

// The report covers every instance of this service and version the server
// has ever seen, so repeated runs against the same stack accumulate.
fun printStackReport() {
    val version = "?version=$stackServiceVersion"
    val report = readApi("/report$version")
    val methods = report["methods"] as Map<*, *>
    val branchSites = report["branch_sites"] as Map<*, *>
    val probes = report["probes"] as Map<*, *>
    val classes = report["classes"] as Map<*, *>
    val instances = report["instances"] as Map<*, *>
    println("yukon demo: report for $stackServiceName@$stackServiceVersion from $stackServerUrl (${instances["total"]} instance(s) so far)")
    println("  methods: known=${methods["known"]} hit=${methods["hit"]} never_hit=${methods["never_hit"]}")
    println(
        "  branch sites: known=${branchSites["known"]} all_outcomes_hit=${branchSites["all_outcomes_hit"]} " +
            "with_never_hit_outcome=${branchSites["with_never_hit_outcome"]}",
    )
    println(
        "  probes: inline (not judged)=${probes["inline"]} generated (not judged)=${probes["generated"]} " +
            "no_debug_info=${probes["no_debug_info"]}",
    )
    println(
        "  classes: declared=${classes["declared"]} loaded=${classes["loaded"]} never_loaded=${classes["never_loaded"]} " +
            "all inline or generated (not judged)=${classes["all_inline_or_generated"]}",
    )
    println(
        "  instances: total=${instances["total"]} ended_cleanly=${instances["ended_cleanly"]} " +
            "silent_without_final_flush=${instances["silent_without_final_flush"]}",
    )
    val optionalParameters = report["optional_parameters"] as Map<*, *>
    println(
        "  optional parameters: known=${optionalParameters["known"]} never_supplied=${optionalParameters["never_supplied"]} always_supplied=${optionalParameters["always_supplied"]}",
    )
    val endpoints = report["endpoints"] as Map<*, *>
    println("  endpoints: known=${endpoints["known"]} called=${endpoints["called"]} never_called=${endpoints["never_called"]}")
    val clusters = report["unreached_clusters"] as Map<*, *>
    println("  unreached clusters: count=${clusters["count"]} methods_attributed=${clusters["methods_attributed"]}")
    for (module in report["disabled_endpoint_modules"] as List<*>) {
        val m = module as Map<*, *>
        println("  DISABLED ENDPOINT MODULE: ${m["module"]} (${m["instances"]} instance(s)): ${m["reason"]}")
    }

    println("  NEVER HIT:")
    for (row in readApi("/never-hit$version")["rows"] as List<*>) {
        val r = row as Map<*, *>
        val routes = (r["routes"] as List<*>).takeIf { it.isNotEmpty() }?.let { " routes=$it" } ?: ""
        val inlinedFrom = r["inlined_from_class_name"]?.let { " (inlined from $it)" } ?: ""
        val where = "${r["class_name"]}#${r["method_name"]}:${r["line"]}"
        if (r["kind"] != "branch") {
            println("    $where (method)$inlinedFrom$routes")
            continue
        }
        for (finding in siteFindings(r)) {
            println("    $where $finding$inlinedFrom$routes")
        }
    }
    println("  NEVER LOADED:")
    for (cls in readApi("/never-loaded$version")["classes"] as List<*>) {
        val c = cls as Map<*, *>
        println("    ${c["class_name"]} (${(c["methods"] as List<*>).size} methods)")
    }
    for (status in listOf("never-supplied", "always-supplied")) {
        println("  ${status.replace('-', ' ').uppercase()}:")
        for (parameter in readApi("/optional-parameters$version&status=$status")["optional_parameters"] as List<*>) {
            val p = parameter as Map<*, *>
            val name = p["parameter_name"] ?: "#${p["parameter_index"]}"
            println("    ${p["class_name"]}#${p["method_name"]}($name) omitted=${p["omissions_total"]} of ${p["target_hits_total"]} calls")
        }
    }
    println("  UNREACHED CLUSTERS:")
    for (cluster in readApi("/unreached-clusters$version")["clusters"] as List<*>) {
        val c = cluster as Map<*, *>
        val root = c["root"] as Map<*, *>
        val routes = (root["routes"] as List<*>).takeIf { it.isNotEmpty() }?.let { " routes=$it" } ?: ""
        println(
            "    UNREACHED CLUSTER: root ${clusterRootText(root)}, " +
                "${c["members_total"]} methods, ${c["never_loaded_classes"]} never-loaded classes$routes",
        )
        for (member in c["members"] as List<*>) {
            val m = member as Map<*, *>
            val suffix = if (m["never_loaded"] == true) " (never loaded)" else ""
            println("      ${m["class_name"]}#${m["method_name"]}$suffix")
        }
    }
    println("  ENDPOINTS:")
    for (endpoint in readApi("/endpoints$version&status=all")["endpoints"] as List<*>) {
        val e = endpoint as Map<*, *>
        val status = if ((e["calls_total"] as Number).toLong() > 0) "CALLED" else "NEVER CALLED"
        val handler = e["handler_class"]?.let { cls -> " handler=$cls${e["handler_method"]?.let { "#$it" } ?: ""}" } ?: ""
        println(
            "    $status: ${e["verb"]} ${e["route_template"]} calls=${e["calls_total"]} [${e["framework"]}, ${e["discovery_source"]}]$handler",
        )
    }
}

class DemoProcess(
    val process: Process,
    val outputThread: Thread,
    val captured: CompletableFuture<String>,
)

// The stub collector binds whatever port it is given and prints the one it actually bound, so
// asking it for 0 and reading the number back out of its output is how a demo run stays clear of
// the compose stack's collector on 4319 and of the testkit's. Reading it rather than picking a
// free port here and passing it in leaves no window between choosing the port and binding it for
// something else to take it.
val collectorPortPattern = Regex("""yukon stub collector listening on (\d+)""")

fun startStubCollector(
    tag: String,
    javaBin: String,
    demoClasspath: String,
): Pair<DemoProcess, Int> {
    val collector =
        startProcess(
            tag,
            javaBin,
            listOf("-cp", demoClasspath, stubCollectorMainClass, "0"),
            capturePattern = collectorPortPattern,
        )
    val port =
        try {
            collector.captured.get(portWaitTimeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            collector.process.destroy()
            throw GradleException("yukon demo: the stub collector never reported a port ($e)")
        }
    return collector to port.toInt()
}

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
    capturePattern: Regex? = null,
): DemoProcess {
    val builder =
        ProcessBuilder(listOf(javaBin) + args)
            .redirectErrorStream(true)
    builder.environment().putAll(env)
    val process = builder.start()
    val captured = CompletableFuture<String>()
    val outputThread =
        Thread({
            process.inputStream.bufferedReader().forEachLine { line ->
                println("[$tag] $line")
                if (!captured.isDone) {
                    capturePattern?.find(line)?.let { captured.complete(it.groupValues[1]) }
                }
            }
            // The stream ends when the process does, so a process that died before printing what
            // was wanted fails its waiter here instead of leaving it to time out.
            captured.completeExceptionally(IOException("$tag exited without matching $capturePattern"))
        }, "yukon-demo-$tag-output").apply {
            isDaemon = true
            start()
        }
    return DemoProcess(process, outputThread, captured)
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

// The stub collector's port is not here: the demo tasks let it bind an ephemeral one and read
// back what it got. See startStubCollector.
object DemoPorts {
    const val SERVER_PORT = 8085
    const val SPRING_SERVER_PORT = 8090
}
