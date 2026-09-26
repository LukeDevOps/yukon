import org.gradle.api.tasks.bundling.Jar
import org.gradle.internal.jvm.Jvm
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
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
val springStackServiceVersion = providers.gradleProperty("yukonServiceVersion").getOrElse("spring-stack-demo")

// One service as the stack's read API knows it: the name and version a demo's agent reports under.
class StackService(
    val name: String,
    val version: String,
    namespace: String?,
) {
    // The server's path for the service: under its namespace when one is named (server ADR 0038).
    val path: String =
        namespace?.let {
            "/api/v1/namespaces/${URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")}/services/$name"
        } ?: "/api/v1/services/$name"
}

// The demo runs in the unspecified namespace unless YUKON_SERVICE_NAMESPACE names one. The agent
// reads it from its own environment, so the stack tasks hand it to the demo server as it is.
val stackServiceNamespace: String? =
    providers
        .environmentVariable("YUKON_SERVICE_NAMESPACE")
        .orNull
        ?.trim()
        ?.ifEmpty { null }

tasks.register("runDemoStack") {
    group = "application"
    description =
        "Runs the -javaagent-instrumented demo server and client against a real collector, " +
        "then prints what the yukon-server read API reports for it."
    dependsOn(rootProject.tasks.named("shadowJar"), tasks.named("classes"))

    doLast {
        val demoClasspath = demoAppClasspath()
        runStackDemo(
            service = StackService("yukon-demo", stackServiceVersion, stackServiceNamespace),
            includePackages = "io.github.lukedevops.demo.server",
            serverArgs = listOf("-cp", demoClasspath, demoServerMainClass),
            serverPort = DemoPorts.SERVER_PORT,
            serverPortWaitSeconds = portWaitTimeoutSeconds,
            clientArgs = listOf("-cp", demoClasspath, demoClientMainClass),
        )
    }
}

tasks.register("runSpringDemoStack") {
    group = "application"
    description =
        "Runs the -javaagent-instrumented Spring Boot fat-jar demo and its client against a real collector, " +
        "then prints what the yukon-server read API reports for it."
    dependsOn(
        rootProject.tasks.named("shadowJar"),
        project(":demo-spring").tasks.named("bootJar"),
        tasks.named("classes"),
    )

    doLast {
        val springBootJar =
            project(":demo-spring")
                .tasks
                .named("bootJar", Jar::class.java)
                .get()
                .archiveFile
                .get()
                .asFile
        runStackDemo(
            service = StackService("yukon-spring-demo", springStackServiceVersion, stackServiceNamespace),
            includePackages = "io.github.lukedevops.demo.spring",
            serverArgs = listOf("-jar", springBootJar.absolutePath, "--server.port=${DemoPorts.SPRING_SERVER_PORT}"),
            serverPort = DemoPorts.SPRING_SERVER_PORT,
            serverPortWaitSeconds = springPortWaitTimeoutSeconds,
            clientArgs = listOf("-cp", demoAppClasspath(), springDemoClientMainClass),
        )
    }
}

// Runs one demo server under the agent against the stack's collector, drives it with its client,
// waits for the shutdown flush to land, then prints the server's report for [service].
fun runStackDemo(
    service: StackService,
    includePackages: String,
    serverArgs: List<String>,
    serverPort: Int,
    serverPortWaitSeconds: Long,
    clientArgs: List<String>,
) {
    requireStackCollector()
    val javaBin = Jvm.current().javaExecutable.absolutePath
    val runInstanceId = UUID.randomUUID().toString()
    println(
        "yukon demo: starting instrumented ${service.name}${stackNamespaceSuffix()} against $stackEndpoint as instance $runInstanceId",
    )
    val server =
        startProcess(
            "server",
            javaBin,
            listOf(stackAgentArg(service, includePackages, runInstanceId)) + serverArgs,
            env = mapOf("YUKON_AUTH_TOKEN" to stackAgentToken) + stackServiceNamespaceEnv(),
        )
    try {
        waitForPort(serverPort, serverPortWaitSeconds)

        println("yukon demo: running demo client")
        val client = startProcess("client", javaBin, clientArgs)
        client.process.waitFor()
        client.outputThread.join()

        println("yukon demo: waiting for one more flush and the static baseline scan before shutdown")
        Thread.sleep((flushIntervalSeconds + 2) * 1000)
    } finally {
        gracefulShutdown("server", server, serverPort)
        awaitShutdownFlush(service, runInstanceId)
    }

    printStackReport(service)
    println("yukon demo: done")
}

// Runs one program under the agent against the stack's collector until it exits by itself, waits
// for its shutdown flush to land, then prints the server's report for [service]. The program's
// first argument is how long to wait before exiting, so its static baseline scan and a regular
// flush land first.
fun runStackOneShot(
    service: StackService,
    includePackages: String,
    programArgs: List<String>,
) {
    requireStackCollector()
    val javaBin = Jvm.current().javaExecutable.absolutePath
    val runInstanceId = UUID.randomUUID().toString()
    println(
        "yukon demo: running instrumented ${service.name}${stackNamespaceSuffix()} against $stackEndpoint as instance $runInstanceId",
    )
    val program =
        startProcess(
            service.name,
            javaBin,
            listOf(stackAgentArg(service, includePackages, runInstanceId)) + programArgs,
            env = mapOf("YUKON_AUTH_TOKEN" to stackAgentToken) + stackServiceNamespaceEnv(),
        )
    try {
        if (!program.process.waitFor(oneShotTimeoutSeconds, TimeUnit.SECONDS)) {
            throw GradleException("yukon demo: ${service.name} did not exit within ${oneShotTimeoutSeconds}s")
        }
    } finally {
        // A program that hung, or a build that was cancelled mid-wait, must not leave a JVM behind
        // still sending to the collector.
        if (program.process.isAlive) program.process.destroyForcibly().waitFor()
        program.outputThread.join()
    }
    val exitCode = program.process.exitValue()
    if (exitCode != 0) throw GradleException("yukon demo: ${service.name} exited with $exitCode")
    awaitShutdownFlush(service, runInstanceId)

    printStackReport(service)
}

// How long a one-shot program may run: its own wait before exiting, plus JVM start-up and the
// agent's shutdown flush, with room to spare.
val oneShotTimeoutSeconds = flushIntervalSeconds + 60

fun requireStackCollector() {
    if (httpGet("$stackEndpoint/healthz", token = null).status != 200) {
        throw GradleException(
            "yukon demo: no collector answering at $stackEndpoint/healthz. Start the stack with " +
                "`docker compose --profile stack up --build` in yukon-server, or pass -PyukonEndpoint=<url>.",
        )
    }
}

// The -javaagent argument for one stack run of [service] as instance [instanceId].
fun stackAgentArg(
    service: StackService,
    includePackages: String,
    instanceId: String,
): String {
    val agentJar =
        rootProject.tasks
            .named("shadowJar", Jar::class.java)
            .get()
            .archiveFile
            .get()
            .asFile
    return "-javaagent:${agentJar.absolutePath}=" +
        "serviceName=${service.name}," +
        "serviceVersion=${service.version}," +
        "serviceInstanceId=$instanceId," +
        "flushIntervalSeconds=$flushIntervalSeconds," +
        "endpoint=$stackEndpoint," +
        "includePackages=$includePackages," +
        "staticBaselineEnabled=true"
}

val shapesStackServiceVersion = providers.gradleProperty("yukonServiceVersion").getOrElse("shapes-stack-demo")

// The Scala fixture modules' `Driver` methods runShapesStack calls. The rest of each module's
// classes and methods are left for the report to show as never run.
val scalaDriverCalls =
    listOf("callSimpleAllOmitted", "callCurried", "callCaseClassApply", "callTraitDefault", "callThroughPlainOverridesOnly")

tasks.register("runShapesStack") {
    group = "application"
    description =
        "Runs the code shapes run and the Scala 2 and 3 fixture drivers under the agent against a real collector, " +
        "then prints what the yukon-server read API reports for each."
    dependsOn(
        rootProject.tasks.named("shadowJar"),
        tasks.named("classes"),
        project(":fixtures-scala2").tasks.named("classes"),
        project(":fixtures-scala3").tasks.named("classes"),
    )

    doLast {
        val waitSeconds = (flushIntervalSeconds + 2).toString()
        runStackOneShot(
            service = StackService("yukon-shapes", shapesStackServiceVersion, stackServiceNamespace),
            includePackages = "io.github.lukedevops.demo.shapes",
            programArgs = listOf("-cp", demoAppClasspath(), "io.github.lukedevops.demo.shapes.ShapesMainKt", waitSeconds),
        )
        for (module in listOf("fixtures-scala2", "fixtures-scala3")) {
            val fixtureClasspath =
                project(":$module")
                    .extensions
                    .getByType(SourceSetContainer::class.java)["main"]
                    .runtimeClasspath
                    .asPath
            // Only the demo's Java output, so no Kotlin standard library is on this run's classpath.
            val driverClasspath =
                sourceSets["main"]
                    .java.destinationDirectory
                    .get()
                    .asFile.absolutePath
            runStackOneShot(
                service = StackService("yukon-$module", shapesStackServiceVersion, stackServiceNamespace),
                includePackages = "com.example.scalatarget",
                programArgs =
                    listOf(
                        "-cp",
                        "$driverClasspath${File.pathSeparator}$fixtureClasspath",
                        "io.github.lukedevops.demo.fixtures.ScalaDriverMain",
                        waitSeconds,
                    ) + scalaDriverCalls,
            )
        }
        println("yukon demo: done")
    }
}

fun stackServiceNamespaceEnv(): Map<String, String> = stackServiceNamespace?.let { mapOf("YUKON_SERVICE_NAMESPACE" to it) } ?: emptyMap()

// A suffix that names the namespace for a printed line, or an empty string when there is none.
fun stackNamespaceSuffix(): String = stackServiceNamespace?.let { " in namespace $it" } ?: ""

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

fun readApi(
    service: StackService,
    path: String,
): Map<*, *> {
    val result = httpGet("$stackServerUrl${service.path}$path", stackServerApiKey)
    if (result.status != 200) {
        throw GradleException("yukon demo: GET $path returned ${result.status}: ${result.body}")
    }
    return groovy.json.JsonSlurper().parseText(result.body) as Map<*, *>
}

// Whether the server reports this run's instance as ended cleanly: its shutdown flush, the delta
// batch marked final_flush, has landed. False while the service or the instance is unknown to it.
fun instanceEndedCleanly(
    service: StackService,
    instanceId: String,
): Boolean =
    try {
        val instances = readApi(service, "/instances?version=${service.version}")["instances"] as List<*>
        instances.map { it as Map<*, *> }.any { it["instance_id"] == instanceId && it["ended_cleanly"] == true }
    } catch (_: GradleException) {
        false
    }

// The agent's shutdown hook sends one last delta batch, and the collector forwards it
// asynchronously, so it can still be in flight after the demo server has exited. Wait until the
// server has it, so the report is not read before the last hits arrive; because the store keeps
// data across runs, "any probes known" cannot tell one run from the last. The instance id is fresh
// each run, so its ended_cleanly flag can only come from this run's final flush.
fun awaitShutdownFlush(
    service: StackService,
    instanceId: String,
) {
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        if (instanceEndedCleanly(service, instanceId)) return
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

// A constructor's name as source reads it (server ADR 0034): `constructor(...)`
// with its parameters' simple type names, read from the JVM descriptor. The stub
// collector prints constructors the same way.
fun constructorText(descriptor: String): String {
    val primitives =
        mapOf('Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double")
    val types = mutableListOf<String>()
    var i = descriptor.indexOf('(') + 1
    while (i in 1 until descriptor.length && descriptor[i] != ')') {
        var dimensions = 0
        while (descriptor[i] == '[') {
            dimensions++
            i++
        }
        val type =
            if (descriptor[i] == 'L') {
                val end = descriptor.indexOf(';', i)
                descriptor.substring(i + 1, end).substringAfterLast('/').also { i = end }
            } else {
                primitives[descriptor[i]] ?: descriptor[i].toString()
            }
        types += type + "[]".repeat(dimensions)
        i++
    }
    return "constructor(${types.joinToString(", ")})"
}

// The source file a row's class reads as (server ADR 0035): set for a file facade or a
// multi-file part that has one, null for every other kind. A server that sends no kotlin_kind
// gives null, so the row reads by its JVM name.
fun sourceFileNaming(row: Map<*, *>): String? {
    val sourceFile = (row["source_file"] as? String).orEmpty()
    val isFileKind = row["kotlin_kind"] == "file_facade" || row["kotlin_kind"] == "multifile_part"
    return sourceFile.takeIf { isFileKind && it.isNotEmpty() }
}

// A class row's name: a file facade or a multi-file part as its source file, a multi-file facade
// by its JVM name with a tag, anything else by its JVM name. The stub collector prints classes
// the same way.
fun classText(row: Map<*, *>): String {
    val className = row["class_name"] as String
    val tag = if (row["kotlin_kind"] == "multifile_facade") " (multi-file facade)" else ""
    return sourceFileNaming(row) ?: "$className$tag"
}

// A method row or node as `Class#name`, or `Class#constructor(...)` for a constructor. A member of
// a file facade or a multi-file part reads as a top-level function with its file, such as
// `handleCheckout (DemoServerMain.kt)`, and a line joins the file: `handleCheckout
// (DemoServerMain.kt:121)`. A member of a multi-file facade carries the facade's tag.
fun methodText(
    node: Map<*, *>,
    line: Any? = null,
): String {
    val name = node["method_name"] as String
    val shown = if (name == "<init>") constructorText(node["method_descriptor"] as String) else name
    val lineSuffix = line?.let { ":$it" } ?: ""
    sourceFileNaming(node)?.let { return "$shown ($it$lineSuffix)" }
    val tag = if (node["kotlin_kind"] == "multifile_facade") " (multi-file facade)" else ""
    return "${node["class_name"]}#$shown$lineSuffix$tag"
}

// A class finding's name as a report line spells it: "never_initialised" reads
// "never initialised".
fun findingText(finding: Any?): String = (finding as String).replace('_', ' ')

// A cluster's root as one line (server ADRs 0032 and 0034). An untaken outcome
// reads as its site row does, then the method that holds it. A root reached
// from hit, and a class root that a method with hits calls, name those callers.
fun clusterRootText(root: Map<*, *>): String {
    val method = methodText(root)
    val callers = (root["reached_from"] as List<*>).joinToString(", ") { methodText(it as Map<*, *>) }
    return when (val kind = root["root_kind"] as String) {
        "untaken_outcome" -> {
            val site = root["site"] as Map<*, *>?
            val finding = site?.let { siteFindings(it).firstOrNull() } ?: "an untaken branch"
            "$finding, in ${methodText(root, site?.get("line") ?: root["line"])} (untaken outcome)"
        }

        "reached_from_hit" -> {
            "$method (reached from hit, called from $callers)"
        }

        "class_finding" -> {
            val calledFrom = if (callers.isEmpty()) "" else ", called from $callers"
            "${classText(root)} (class finding: ${findingText(root["finding"])}$calledFrom)"
        }

        else -> {
            "$method (${kind.replace('_', ' ')})"
        }
    }
}

// The report covers every instance of this service and version the server
// has ever seen, so repeated runs against the same stack accumulate.
fun printStackReport(service: StackService) {
    val version = "?version=${service.version}"
    val report = readApi(service, "/report$version")
    val methods = report["methods"] as Map<*, *>
    val branchSites = report["branch_sites"] as Map<*, *>
    val probes = report["probes"] as Map<*, *>
    val classes = report["classes"] as Map<*, *>
    val instances = report["instances"] as Map<*, *>
    println(
        "yukon demo: report for ${service.name}@${service.version}${stackNamespaceSuffix()} from $stackServerUrl " +
            "(${instances["total"]} instance(s) so far)",
    )
    println(
        "  methods: known=${methods["known"]} hit=${methods["hit"]} never_hit=${methods["never_hit"]} " +
            "in_class_findings=${methods["in_class_findings"]} in_never_hit_code=${methods["in_never_hit_code"]} " +
            "unjudged_constructors=${methods["unjudged_constructors"]}",
    )
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
            "never_initialised=${classes["never_initialised"]} never_instantiated=${classes["never_instantiated"]} " +
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
    for (row in readApi(service, "/never-hit$version")["rows"] as List<*>) {
        val r = row as Map<*, *>
        val routes = (r["routes"] as List<*>).takeIf { it.isNotEmpty() }?.let { " routes=$it" } ?: ""
        val inlinedFrom = r["inlined_from_class_name"]?.let { " (inlined from $it)" } ?: ""
        val where = methodText(r, r["line"])
        if (r["kind"] != "branch") {
            val kind = if (r["method_name"] == "<init>") "unused overload" else "method"
            println("    $where ($kind)$inlinedFrom$routes")
            continue
        }
        for (finding in siteFindings(r)) {
            println("    $where $finding$inlinedFrom$routes")
        }
    }
    println("  NEVER LOADED:")
    for (cls in readApi(service, "/never-loaded$version")["classes"] as List<*>) {
        val c = cls as Map<*, *>
        println("    ${classText(c)} (${(c["methods"] as List<*>).size} methods)")
    }
    for (finding in listOf("never-initialised", "never-instantiated")) {
        println("  ${finding.replace('-', ' ').uppercase()}:")
        for (cls in readApi(service, "/$finding$version")["classes"] as List<*>) {
            val c = cls as Map<*, *>
            val names = (c["methods"] as List<*>).joinToString(", ") { if (it == "<init>") "constructor" else "$it" }
            println("    ${classText(c)} (methods: $names) (instances loading: ${c["instances_loading"]})")
        }
    }
    for (status in listOf("never-supplied", "always-supplied")) {
        println("  ${status.replace('-', ' ').uppercase()}:")
        for (parameter in readApi(service, "/optional-parameters$version&status=$status")["optional_parameters"] as List<*>) {
            val p = parameter as Map<*, *>
            val name = p["parameter_name"] ?: "#${p["parameter_index"]}"
            println("    ${p["class_name"]}#${p["method_name"]}($name) omitted=${p["omissions_total"]} of ${p["target_hits_total"]} calls")
        }
    }
    println("  UNREACHED CLUSTERS:")
    for (cluster in readApi(service, "/unreached-clusters$version")["clusters"] as List<*>) {
        val c = cluster as Map<*, *>
        val root = c["root"] as Map<*, *>
        val routes = (root["routes"] as List<*>).takeIf { it.isNotEmpty() }?.let { " routes=$it" } ?: ""
        println(
            "    UNREACHED CLUSTER: root ${clusterRootText(root)}, " +
                "${c["members_total"]} methods, ${c["never_loaded_classes"]} never-loaded classes$routes",
        )
        for (whole in c["whole_classes"] as List<*>) {
            val w = whole as Map<*, *>
            val finding = w["finding"]?.let { ", ${findingText(it)}" } ?: ""
            println("      ${classText(w)} (whole class$finding, ${w["methods_total"]} methods)")
        }
        for (member in c["members"] as List<*>) {
            val m = member as Map<*, *>
            val suffix = if (m["never_loaded"] == true) " (never loaded)" else ""
            println("      ${methodText(m)}$suffix")
        }
    }
    println("  ENDPOINTS:")
    for (endpoint in readApi(service, "/endpoints$version&status=all")["endpoints"] as List<*>) {
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
