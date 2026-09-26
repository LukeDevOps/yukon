package io.github.lukedevops.demo.collector

import com.google.protobuf.MessageLite
import io.github.lukedevops.yukon.proto.BranchOutcome
import io.github.lukedevops.yukon.proto.BranchRole
import io.github.lukedevops.yukon.proto.BranchSite
import io.github.lukedevops.yukon.proto.ClassLocation
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.KotlinKind
import io.github.lukedevops.yukon.proto.ProbeDelta
import io.github.lukedevops.yukon.proto.ProbeKind
import io.github.lukedevops.yukon.proto.ProbeLocation
import io.github.lukedevops.yukon.proto.ProbeManifest
import io.github.lukedevops.yukon.proto.ResourceAttributes
import io.github.lukedevops.yukon.proto.RoutineKind
import io.github.lukedevops.yukon.proto.StaticBaseline
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubCollectorTest {
    private val server = startStubCollector(0)
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun resource(runId: String): ResourceAttributes =
        ResourceAttributes
            .newBuilder()
            .setServiceName("svc")
            .setServiceInstanceId("pinned")
            .setRunId(runId)
            .build()

    private fun post(
        path: String,
        payload: MessageLite,
    ): Int =
        client
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:${server.address.port}/v1/yukon/$path"))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray()))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    @Test
    fun `a delta batch with an empty run id is answered 400, and one with a run id 200`() {
        assertEquals(400, post("deltas", DeltaBatch.newBuilder().setResource(resource("")).build()))
        assertEquals(200, post("deltas", DeltaBatch.newBuilder().setResource(resource("run-1")).build()))
    }

    @Test
    fun `a manifest with an empty run id is answered 400, and one with a run id 200`() {
        assertEquals(400, post("manifest", ProbeManifest.newBuilder().setResource(resource("")).build()))
        assertEquals(200, post("manifest", ProbeManifest.newBuilder().setResource(resource("run-1")).build()))
    }

    @Test
    fun `a static baseline with an empty run id is answered 400, and one with a run id 200`() {
        val baseline = StaticBaseline.newBuilder().setChunkCount(1)
        assertEquals(400, post("static-baseline", baseline.setResource(resource("")).build()))
        assertEquals(200, post("static-baseline", baseline.setResource(resource("run-1")).build()))
    }

    @Test
    fun `a file facade and a multi-file part print as their source file, and a multi-file facade carries a tag`() {
        fun probe(
            classId: Int,
            className: String,
        ) = ProbeLocation
            .newBuilder()
            .setClassId(classId)
            .setKind(ProbeKind.METHOD)
            .setClassName(className)
            .setMethodName("greet")
            .setMethodDescriptor("()V")

        fun location(
            classId: Int,
            kind: KotlinKind,
            sourceFile: String,
        ) = ClassLocation
            .newBuilder()
            .setClassId(classId)
            .setSuperClassName("java.lang.Object")
            .setSourceFile(sourceFile)
            .setKotlinKind(kind)
        val manifest =
            ProbeManifest
                .newBuilder()
                .setResource(resource("run-naming"))
                .addProbes(probe(0, "com.acme.naming.TextKt"))
                .addProbes(probe(1, "com.acme.naming.Words"))
                .addProbes(probe(2, "com.acme.naming.Words__HelloKt"))
                .addProbes(probe(3, "com.acme.naming.Plain"))
                .addClassLocations(location(0, KotlinKind.FILE_FACADE, "Text.kt"))
                .addClassLocations(location(1, KotlinKind.MULTIFILE_CLASS_FACADE, ""))
                .addClassLocations(location(2, KotlinKind.MULTIFILE_CLASS_PART, "Hello.kt"))
                .addClassLocations(location(3, KotlinKind.KOTLIN_CLASS, "Plain.kt"))
                .build()

        assertEquals(200, post("manifest", manifest))

        assertEquals("Text.kt", classText("com.acme.naming.TextKt"))
        assertEquals("greet (Text.kt)", methodText("com.acme.naming.TextKt", "greet", "()V"))
        assertEquals("greet (Text.kt:12)", methodText("com.acme.naming.TextKt", "greet", "()V", 12))
        assertEquals("Hello.kt", classText("com.acme.naming.Words__HelloKt"))
        assertEquals("com.acme.naming.Words (multi-file facade)", classText("com.acme.naming.Words"))
        assertEquals("com.acme.naming.Words#greet:1 (multi-file facade)", methodText("com.acme.naming.Words", "greet", "()V", 1))
        assertEquals("com.acme.naming.Plain", classText("com.acme.naming.Plain"))
        assertEquals("com.acme.naming.Plain#greet:3", methodText("com.acme.naming.Plain", "greet", "()V", 3))
        assertEquals("com.acme.naming.Unknown#greet", methodText("com.acme.naming.Unknown", "greet", "()V"))
    }

    /** What [printNeverHitReport] prints, captured from standard output. */
    private fun neverHitReport(): List<String> {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            printNeverHitReport()
        } finally {
            System.setOut(original)
        }
        return captured.toString().lines()
    }

    /** The number on the report's line that starts with [label]. */
    private fun countOn(
        report: List<String>,
        label: String,
    ): Int =
        report
            .single { it.startsWith(label) }
            .substringAfterLast(' ')
            .toInt()

    @Test
    fun `a site in a never-hit method or behind a never-hit guard leaves NEVER HIT and ROUTINE OUTCOMES and is counted apart`() {
        val className = "com.acme.fold.Folds"

        fun outcome(
            branchIndex: Int,
            role: BranchRole,
            routine: RoutineKind = RoutineKind.ROUTINE_KIND_NONE,
        ) = BranchOutcome
            .newBuilder()
            .setBranchIndex(branchIndex)
            .setRole(role)
            .setRoutine(routine)

        fun site(
            siteIndex: Int,
            line: Int,
            guard: Int?,
            vararg outcomes: BranchOutcome.Builder,
        ) = BranchSite
            .newBuilder()
            .setSiteIndex(siteIndex)
            .setLine(line)
            .apply { guard?.let { setGuard(it) } }
            .addAllOutcomes(outcomes.map { it.build() })

        fun method(
            probeIndex: Int,
            methodName: String,
            line: Int,
            vararg sites: BranchSite.Builder,
        ) = ProbeLocation
            .newBuilder()
            .setClassId(0)
            .setProbeIndex(probeIndex)
            .setKind(ProbeKind.METHOD)
            .setClassName(className)
            .setMethodName(methodName)
            .setMethodDescriptor("()V")
            .setLine(line)
            .addAllBranchSites(sites.map { it.build() })

        fun branch(
            probeIndex: Int,
            methodName: String,
            line: Int,
            siteIndex: Int,
            branchIndex: Int,
        ) = ProbeLocation
            .newBuilder()
            .setClassId(0)
            .setProbeIndex(probeIndex)
            .setKind(ProbeKind.BRANCH)
            .setClassName(className)
            .setMethodName(methodName)
            .setMethodDescriptor("()V")
            .setLine(line)
            .setSiteIndex(siteIndex)
            .setBranchIndex(branchIndex)
        val manifest =
            ProbeManifest
                .newBuilder()
                .setResource(resource("run-fold"))
                // ran() ran and took outcome 0. Outcome 1 never ran and guards site 1, whose null side
                // is routine. Site 1 folds into outcome 1's row.
                .addProbes(
                    method(
                        0,
                        "ran",
                        1,
                        site(0, 2, null, outcome(0, BranchRole.TAKEN), outcome(1, BranchRole.FALL_THROUGH)),
                        site(1, 3, 1, outcome(2, BranchRole.TAKEN), outcome(3, BranchRole.FALL_THROUGH, RoutineKind.NULL_DEFAULT)),
                    ),
                ).addProbes(branch(1, "ran", 2, 0, 0))
                .addProbes(branch(2, "ran", 2, 0, 1))
                .addProbes(branch(3, "ran", 3, 1, 2))
                .addProbes(branch(4, "ran", 3, 1, 3))
                // dead() never ran, so its one site, routine null side included, folds into its row.
                .addProbes(
                    method(
                        5,
                        "dead",
                        10,
                        site(2, 11, null, outcome(4, BranchRole.TAKEN, RoutineKind.NULL_DEFAULT), outcome(5, BranchRole.FALL_THROUGH)),
                    ),
                ).addProbes(branch(6, "dead", 11, 2, 4))
                .addProbes(branch(7, "dead", 11, 2, 5))
                .build()

        fun hit(probeIndex: Int) =
            ProbeDelta
                .newBuilder()
                .setClassId(0)
                .setProbeIndex(probeIndex)
                .setHitsTotal(1)
        val deltas =
            DeltaBatch
                .newBuilder()
                .setResource(resource("run-fold"))
                .addDeltas(hit(0).setKind(ProbeKind.METHOD))
                .addDeltas(hit(1).setKind(ProbeKind.BRANCH))
                .build()
        val label = "branches in never-hit code (reported with their method or guard):"
        val before = countOn(neverHitReport(), label)

        assertEquals(200, post("manifest", manifest))
        assertEquals(200, post("deltas", deltas))

        val report = neverHitReport()
        val rows = report.filter { it.contains("NEVER HIT:") && it.contains("Folds") }
        assertEquals(2, rows.size, "the one never-taken outcome of ran and the method row of dead: $rows")
        assertTrue(rows.any { it.contains("Folds#dead:10 [METHOD]") }, rows.toString())
        assertTrue(rows.any { it.contains("Folds#ran:2") && it.contains("branch#1]") }, rows.toString())
        val routine = report.filter { it.contains("ROUTINE [") && it.contains("Folds") }
        assertEquals(emptyList(), routine, "a folded site's routine outcome is not listed")
        assertEquals(4, countOn(report, label) - before, "sites 1 and 2 fold, two outcomes each")
    }

    /**
     * Server ADR 0031 folds a site into any never-hit method, not only a row: a lone constructor of
     * a class with no finding is not listed, and neither is its code.
     */
    @Test
    fun `a site in a never-run constructor that is not a row leaves NEVER HIT and is counted apart`() {
        val className = "com.acme.fold.Lone"

        fun location(
            probeIndex: Int,
            kind: ProbeKind,
        ) = ProbeLocation
            .newBuilder()
            .setClassId(0)
            .setProbeIndex(probeIndex)
            .setKind(kind)
            .setClassName(className)
            .setMethodName("<init>")
            .setMethodDescriptor("(Z)V")
            .setLine(5)

        val site =
            BranchSite
                .newBuilder()
                .setSiteIndex(0)
                .setLine(6)
                .addOutcomes(BranchOutcome.newBuilder().setBranchIndex(0).setRole(BranchRole.TAKEN))
                .addOutcomes(BranchOutcome.newBuilder().setBranchIndex(1).setRole(BranchRole.FALL_THROUGH))
        val manifest =
            ProbeManifest
                .newBuilder()
                .setResource(resource("run-lone"))
                .addProbes(location(0, ProbeKind.METHOD).addBranchSites(site))
                .addProbes(location(1, ProbeKind.BRANCH).setLine(6).setSiteIndex(0).setBranchIndex(0))
                .addProbes(location(2, ProbeKind.BRANCH).setLine(6).setSiteIndex(0).setBranchIndex(1))
                .build()
        val label = "branches in never-hit code (reported with their method or guard):"
        val before = countOn(neverHitReport(), label)

        assertEquals(200, post("manifest", manifest))

        val report = neverHitReport()
        assertEquals(emptyList(), report.filter { it.contains("NEVER HIT:") && it.contains("Lone") })
        assertEquals(2, countOn(report, label) - before, "both outcomes of the constructor's site fold")
    }
}
