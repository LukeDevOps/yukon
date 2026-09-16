package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.DisabledEndpointModule
import io.github.lukedevops.yukon.export.EndpointDelta
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.export.EndpointLocation
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ProbeDelta
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.SkippedClass
import io.github.lukedevops.yukon.export.StaticBaseline
import io.github.lukedevops.yukon.export.StaticallyUnsafeClass
import io.github.lukedevops.yukon.export.UnprobedClass
import io.github.lukedevops.yukon.export.UnreadableClass
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YukonTestCollectorTest {
    private var collector: YukonTestCollector? = null

    @AfterTest
    fun tearDown() {
        collector?.close()
    }

    private fun startCollector(): YukonTestCollector {
        val started = YukonTestCollector.start()
        collector = started
        return started
    }

    private fun exporterFor(target: YukonTestCollector) = HttpOtlpStyleExporter(target.endpoint)

    private fun methodProbe(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        methodDescriptor: String,
        line: Int,
    ) = ProbeLocation(classId, probeIndex, ProbeKind.METHOD, className, methodName, methodDescriptor, line, null)

    private fun omissionProbe(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        methodDescriptor: String,
        line: Int,
        parameterIndex: Int,
        parameterName: String,
        overridable: Boolean = false,
        targetClassName: String? = null,
    ) = ProbeLocation(
        classId = classId,
        probeIndex = probeIndex,
        kind = ProbeKind.OPTIONAL_ARGUMENT,
        className = className,
        methodName = methodName,
        methodDescriptor = methodDescriptor,
        line = line,
        branchIndex = null,
        parameterIndex = parameterIndex,
        parameterName = parameterName,
        overridable = overridable,
        targetClassName = targetClassName,
    )

    private fun endpoint(
        endpointId: Int,
        verb: String,
        routeTemplate: String,
        handlerClass: String? = null,
        handlerMethod: String? = null,
    ) = EndpointLocation(
        endpointId = endpointId,
        verb = verb,
        routeTemplate = routeTemplate,
        verbatimTemplate = routeTemplate,
        framework = "jdk-httpserver",
        discoverySource = EndpointDiscoverySource.REGISTRATION,
        handlerClass = handlerClass,
        handlerMethod = handlerMethod,
        handlerDescriptor = if (handlerMethod != null) "()V" else null,
    )

    @Test
    fun `wasHit is true once a manifest and a hit-bearing delta both arrive, false with the manifest alone`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        val manifest =
            ProbeManifest(
                serviceName = "svc",
                serviceVersion = "1.0",
                probes = listOf(methodProbe(1, 0, "com.acme.Foo", "bar", "()V", 10)),
                serviceInstanceId = "i-1",
            )

        exporter.exportManifest(manifest)
        assertFalse(target.wasHit("com.acme.Foo", "bar"))

        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", "1.0", "i-1", null),
                listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 1L)),
            ),
        )
        assertTrue(target.wasHit("com.acme.Foo", "bar"))
    }

    @Test
    fun `hitCount sums overloads by default, isolates one when a descriptor is given, and max-merges a repeated batch`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        val manifest =
            ProbeManifest(
                serviceName = "svc",
                serviceVersion = null,
                probes =
                    listOf(
                        methodProbe(1, 0, "com.acme.Foo", "bar", "()V", 1),
                        methodProbe(1, 1, "com.acme.Foo", "bar", "(I)V", 2),
                    ),
                serviceInstanceId = "i-1",
            )
        exporter.exportManifest(manifest)
        val batch =
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(
                    ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 3L),
                    ProbeDelta(1, 1, ProbeKind.METHOD, 1L, 4L),
                ),
            )

        exporter.exportDeltaBatch(batch)
        assertEquals(7L, target.hitCount("com.acme.Foo", "bar"))
        assertEquals(3L, target.hitCount("com.acme.Foo", "bar", "()V"))

        // Re-delivering the identical batch must not double count: hits_total is cumulative and
        // merged with max(), so applying the same value twice is a no-op.
        exporter.exportDeltaBatch(batch)
        assertEquals(7L, target.hitCount("com.acme.Foo", "bar"))
    }

    @Test
    fun `two instances reusing the same class_id for different classes are never confused`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest("svc", null, listOf(methodProbe(1, 0, "com.acme.A", "m", "()V", 1)), serviceInstanceId = "i-1"),
        )
        exporter.exportManifest(
            ProbeManifest("svc", null, listOf(methodProbe(1, 0, "com.acme.B", "m", "()V", 1)), serviceInstanceId = "i-2"),
        )

        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, "i-1", null), listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 5L))),
        )

        assertTrue(target.wasHit("com.acme.A", "m"))
        assertFalse(target.wasHit("com.acme.B", "m"))
    }

    @Test
    fun `awaitNextFlush returns once a delta batch is sent after the call, and times out if none arrives`() {
        val target = startCollector()
        val exporter = exporterFor(target)

        thread(isDaemon = true) {
            Thread.sleep(50)
            exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList()))
        }
        target.awaitNextFlush(Duration.ofSeconds(2))

        assertFailsWith<TimeoutException> {
            target.awaitNextFlush(Duration.ofMillis(150))
        }
    }

    @Test
    fun `awaitSettled returns once two delta batches are sent after the call, and times out with only one`() {
        val target = startCollector()
        val exporter = exporterFor(target)

        thread(isDaemon = true) {
            Thread.sleep(50)
            exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList()))
            Thread.sleep(50)
            exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList()))
        }
        target.awaitSettled(Duration.ofSeconds(2))

        thread(isDaemon = true) {
            Thread.sleep(50)
            exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList()))
        }
        assertFailsWith<TimeoutException> {
            target.awaitSettled(Duration.ofMillis(150))
        }
    }

    @Test
    fun `start runs the HTTP dispatcher as a daemon thread so it cannot pin the JVM`() {
        startCollector()

        val dispatcherThreads = Thread.getAllStackTraces().keys.filter { it.name == "HTTP-Dispatcher" }

        assertTrue(dispatcherThreads.isNotEmpty(), "expected an HTTP-Dispatcher thread to exist after start()")
        assertTrue(dispatcherThreads.all { it.isDaemon }, "every HTTP-Dispatcher thread must be a daemon thread")
    }

    @Test
    fun `awaitProbe returns once a manifest mentions the class and method, and times out if it never does`() {
        val target = startCollector()
        val exporter = exporterFor(target)

        thread(isDaemon = true) {
            Thread.sleep(50)
            exporter.exportManifest(
                ProbeManifest("svc", null, listOf(methodProbe(1, 0, "com.acme.Foo", "bar", "()V", 1)), serviceInstanceId = "i-1"),
            )
        }
        target.awaitProbe("com.acme.Foo", "bar", Duration.ofSeconds(2))

        assertFailsWith<TimeoutException> {
            target.awaitProbe("com.acme.Other", "baz", Duration.ofMillis(150))
        }
    }

    @Test
    fun `an unknown probe reports it was matched but could not be instrumented`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                skippedClasses = listOf(SkippedClass("com.acme.Skipped", "cannot add @JvmName on class", 1L)),
                serviceInstanceId = "i-1",
            ),
        )

        val failure = assertFailsWith<UnknownProbeException> { target.wasHit("com.acme.Skipped", "m") }
        assertTrue(failure.message!!.contains("could not be instrumented"), failure.message)
    }

    @Test
    fun `an unknown probe reports it was declared by the static baseline but never loaded`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource = ResourceAttributes("svc", null, "i-1", null),
                declaredClasses = listOf(DeclaredClass("com.acme.Dead", listOf(DeclaredMethod("m", "()V")))),
                scannedAt = 1000L,
                chunkIndex = 0,
                chunkCount = 1,
            ),
        )

        val failure = assertFailsWith<UnknownProbeException> { target.wasHit("com.acme.Dead", "m") }
        assertTrue(failure.message!!.contains("never loaded"), failure.message)
    }

    @Test
    fun `an unknown probe reports the class is instrumented but has no probe for that method`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest("svc", null, listOf(methodProbe(1, 0, "com.acme.Has", "real", "()V", 1)), serviceInstanceId = "i-1"),
        )

        val failure = assertFailsWith<UnknownProbeException> { target.wasHit("com.acme.Has", "missing") }
        assertTrue(failure.message!!.contains("no probe for method"), failure.message)
    }

    @Test
    fun `an unknown probe reports it was never mentioned anywhere as a last resort`() {
        val target = startCollector()

        val failure = assertFailsWith<UnknownProbeException> { target.wasHit("com.acme.Nowhere", "m") }
        assertTrue(failure.message!!.contains("never mentioned"), failure.message)
    }

    @Test
    fun `neverHit lists unhit method and branch probes, sorted, and omits hit ones`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.Foo", "a", "()V", 1),
                    methodProbe(1, 1, "com.acme.Foo", "b", "()V", 2),
                    ProbeLocation(1, 2, ProbeKind.BRANCH, "com.acme.Foo", "b", "()V", 3, 0),
                    ProbeLocation(1, 3, ProbeKind.BRANCH, "com.acme.Foo", "b", "()V", 3, 1),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(
                    ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 2L),
                    ProbeDelta(1, 2, ProbeKind.BRANCH, 1L, 1L),
                ),
            ),
        )

        val neverHit = target.neverHit()

        assertEquals(2, neverHit.size)
        assertEquals("b", neverHit[0].methodName)
        assertEquals(ProbeKind.METHOD, neverHit[0].kind)
        assertEquals("b", neverHit[1].methodName)
        assertEquals(ProbeKind.BRANCH, neverHit[1].kind)
        assertEquals(1, neverHit[1].branchIndex)
    }

    @Test
    fun `neverHit excludes an inline probe even though it was never hit`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.Foo", "a", "()V", 1),
                    ProbeLocation(1, 1, ProbeKind.METHOD, "com.acme.Foo", "inl", "()V", 2, null, inline = true),
                ),
                serviceInstanceId = "i-1",
            ),
        )

        val neverHit = target.neverHit()

        assertEquals(listOf("a"), neverHit.map { it.methodName })
        assertFalse(neverHit.single().inline)
    }

    @Test
    fun `neverHit excludes an optional argument probe even though it was never omitted`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.Foo", "f", "(I)V", 1),
                    omissionProbe(1, 1, "com.acme.Foo", "f", "(I)V", 1, parameterIndex = 0, parameterName = "count"),
                ),
                serviceInstanceId = "i-1",
            ),
        )

        val neverHit = target.neverHit()

        assertEquals(listOf("f"), neverHit.map { it.methodName })
        assertEquals(ProbeKind.METHOD, neverHit.single().kind)
    }

    @Test
    fun `omissionCount sums across instances and isolates by parameter index or name`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(omissionProbe(1, 0, "com.acme.Foo", "f", "(II)V", 1, parameterIndex = 0, parameterName = "count")),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(omissionProbe(1, 0, "com.acme.Foo", "f", "(II)V", 1, parameterIndex = 0, parameterName = "count")),
                serviceInstanceId = "i-2",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(ProbeDelta(1, 0, ProbeKind.OPTIONAL_ARGUMENT, 1L, 3L)),
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-2", null),
                listOf(ProbeDelta(1, 0, ProbeKind.OPTIONAL_ARGUMENT, 1L, 2L)),
            ),
        )

        assertEquals(5L, target.omissionCount("com.acme.Foo", "f", parameterIndex = 0))
        assertEquals(5L, target.omissionCount("com.acme.Foo", "f", parameterName = "count"))
        assertEquals(5L, target.omissionCount("com.acme.Foo", "f", parameterIndex = 0, methodDescriptor = "(II)V"))
    }

    @Test
    fun `omissionCount throws when the class is instrumented but has no omission probe for that parameter`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(omissionProbe(1, 0, "com.acme.Foo", "f", "(II)V", 1, parameterIndex = 0, parameterName = "count")),
                serviceInstanceId = "i-1",
            ),
        )

        val error = assertFailsWith<UnknownProbeException> { target.omissionCount("com.acme.Foo", "f", parameterIndex = 1) }
        assertTrue(error.message!!.contains("no omission probe"))
    }

    @Test
    fun `neverSupplied lists a non-overridable target whose omission total equals its hit total`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.Foo", "f", "(I)V", 10),
                    omissionProbe(1, 1, "com.acme.Foo", "f", "(I)V", 10, parameterIndex = 0, parameterName = "count"),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(
                    ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 4L),
                    ProbeDelta(1, 1, ProbeKind.OPTIONAL_ARGUMENT, 1L, 4L),
                ),
            ),
        )

        val neverSupplied = target.neverSupplied()

        assertEquals(1, neverSupplied.size)
        assertEquals("count", neverSupplied.single().parameterName)
        assertEquals(0, neverSupplied.single().parameterIndex)
        assertEquals(10, neverSupplied.single().line)
        assertTrue(target.alwaysSupplied().isEmpty())
    }

    @Test
    fun `neverSupplied abstains for an overridable target even when every call omitted the parameter`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.Base", "greet", "(Ljava/lang/String;)V", 10),
                    omissionProbe(
                        1,
                        1,
                        "com.acme.Base",
                        "greet",
                        "(Ljava/lang/String;)V",
                        10,
                        parameterIndex = 0,
                        parameterName = "name",
                        overridable = true,
                    ),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(
                    ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 3L),
                    ProbeDelta(1, 1, ProbeKind.OPTIONAL_ARGUMENT, 1L, 3L),
                ),
            ),
        )

        assertTrue(target.neverSupplied().isEmpty())
    }

    @Test
    fun `alwaysSupplied lists a parameter whose omission total stayed at zero while its target was called`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.Foo", "f", "(I)V", 10),
                    omissionProbe(1, 1, "com.acme.Foo", "f", "(I)V", 10, parameterIndex = 0, parameterName = "count"),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 4L)),
            ),
        )

        val alwaysSupplied = target.alwaysSupplied()

        assertEquals(1, alwaysSupplied.size)
        assertEquals("count", alwaysSupplied.single().parameterName)
        assertTrue(target.neverSupplied().isEmpty())
    }

    @Test
    fun `neverSupplied and alwaysSupplied skip a target with no method probe`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    omissionProbe(
                        1,
                        0,
                        "com.acme.Greeter",
                        "greet",
                        "(Ljava/lang/String;)V",
                        10,
                        parameterIndex = 0,
                        parameterName = "name",
                        overridable = true,
                    ),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(ProbeDelta(1, 0, ProbeKind.OPTIONAL_ARGUMENT, 1L, 0L)),
            ),
        )

        assertTrue(target.neverSupplied().isEmpty())
        assertTrue(target.alwaysSupplied().isEmpty())
    }

    @Test
    fun `neverSupplied and alwaysSupplied skip an inline target`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(1, 0, "com.acme.FooKt", "f", "(I)V", 10),
                    ProbeLocation(
                        classId = 1,
                        probeIndex = 1,
                        kind = ProbeKind.OPTIONAL_ARGUMENT,
                        className = "com.acme.FooKt",
                        methodName = "f",
                        methodDescriptor = "(I)V",
                        line = 10,
                        branchIndex = null,
                        inline = true,
                        parameterIndex = 0,
                        parameterName = "count",
                    ),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 4L)),
            ),
        )

        assertTrue(target.neverSupplied().isEmpty())
        assertTrue(target.alwaysSupplied().isEmpty())
    }

    /**
     * A Scala constructor default getter's own class is the companion module (`Cc$`), but its
     * target `<init>` lives on `Cc`. `omissionCount` and the finding rules both name the parameter
     * by the target's own class, per ADR 0023, not the class the omission probe's slot lives on.
     */
    @Test
    fun `omissionCount and the finding rules join an omission probe to its target across a class boundary`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(2, 0, "com.example.scalatarget.Cc", "<init>", "(II)V", 71),
                    omissionProbe(
                        1,
                        0,
                        "com.example.scalatarget.Cc\$",
                        "<init>",
                        "(II)V",
                        71,
                        parameterIndex = 1,
                        parameterName = "b",
                        targetClassName = "com.example.scalatarget.Cc",
                    ),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(
                    ProbeDelta(2, 0, ProbeKind.METHOD, 1L, 4L),
                    ProbeDelta(1, 0, ProbeKind.OPTIONAL_ARGUMENT, 1L, 4L),
                ),
            ),
        )

        assertEquals(4L, target.omissionCount("com.example.scalatarget.Cc", "<init>", parameterIndex = 1))
        assertEquals(4L, target.omissionCount("com.example.scalatarget.Cc", "<init>", parameterName = "b"))

        val neverSupplied = target.neverSupplied()
        assertEquals(1, neverSupplied.size)
        assertEquals("com.example.scalatarget.Cc", neverSupplied.single().className)
        assertEquals("com.example.scalatarget.Cc", neverSupplied.single().targetClassName)
        assertEquals("<init>", neverSupplied.single().methodName)
        assertTrue(target.alwaysSupplied().isEmpty())
    }

    /**
     * A Scala constructor default gets two omission probes for the same parameter: the module
     * getter on `Cc$`, resolved cross-class to `Cc`'s own `<init>`, and `Cc`'s own static
     * forwarder for the same getter name, resolved in class to that same `<init>`. Scala callers
     * only ever reach the module getter, so the forwarder's own probe stays at zero. Judging each
     * probe on its own would report the forwarder as always supplied beside the module getter's
     * never supplied for the same parameter; both must be summed and judged once. See ADR 0023.
     */
    @Test
    fun `neverSupplied and alwaysSupplied sum every omission probe naming the same target parameter`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(
                    methodProbe(3, 0, "com.example.scalatarget.Cc", "<init>", "(II)V", 71),
                    omissionProbe(
                        1,
                        0,
                        "com.example.scalatarget.Cc\$",
                        "<init>",
                        "(II)V",
                        71,
                        parameterIndex = 0,
                        parameterName = "a",
                        targetClassName = "com.example.scalatarget.Cc",
                    ),
                    omissionProbe(
                        3,
                        1,
                        "com.example.scalatarget.Cc",
                        "<init>",
                        "(II)V",
                        71,
                        parameterIndex = 0,
                        parameterName = "a",
                    ),
                ),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                listOf(
                    ProbeDelta(3, 0, ProbeKind.METHOD, 1L, 4L),
                    ProbeDelta(1, 0, ProbeKind.OPTIONAL_ARGUMENT, 1L, 4L),
                    ProbeDelta(3, 1, ProbeKind.OPTIONAL_ARGUMENT, 1L, 0L),
                ),
            ),
        )

        assertEquals(4L, target.omissionCount("com.example.scalatarget.Cc", "<init>", parameterIndex = 0))

        val neverSupplied = target.neverSupplied()
        assertEquals(1, neverSupplied.size)
        assertEquals("com.example.scalatarget.Cc", neverSupplied.single().className)
        assertEquals("<init>", neverSupplied.single().methodName)
        assertEquals(0, neverSupplied.single().parameterIndex)
        assertTrue(
            target.alwaysSupplied().isEmpty(),
            "the forwarder's own zero must not be judged on its own now that both probes are summed",
        )
    }

    @Test
    fun `omissionCount throws naming the target's own class when a cross-class query finds nothing`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                listOf(methodProbe(2, 0, "com.example.scalatarget.Cc", "<init>", "(II)V", 71)),
                serviceInstanceId = "i-1",
            ),
        )

        val error =
            assertFailsWith<UnknownProbeException> {
                target.omissionCount("com.example.scalatarget.Cc", "<init>", parameterIndex = 0)
            }
        assertTrue(error.message!!.contains("no omission probe"))
    }

    @Test
    fun `skippedClasses reflects the manifest's skipped list, distinct by class name`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                skippedClasses = listOf(SkippedClass("com.acme.B", "r2", 2L)),
                serviceInstanceId = "i-1",
            ),
        )
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                skippedClasses = listOf(SkippedClass("com.acme.A", "r1", 1L), SkippedClass("com.acme.B", "r2-dup", 3L)),
                serviceInstanceId = "i-2",
            ),
        )

        assertEquals(listOf("com.acme.A", "com.acme.B"), target.skippedClasses().map { it.className })
    }

    @Test
    fun `neverLoaded throws when no complete static baseline scan has ever arrived`() {
        val target = startCollector()

        assertFailsWith<IllegalStateException> { target.neverLoaded() }
    }

    @Test
    fun `neverLoaded ignores an incomplete chunked scan`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource = ResourceAttributes("svc", null, "i-1", null),
                declaredClasses = listOf(DeclaredClass("com.acme.Foo", listOf(DeclaredMethod("m", "()V")))),
                scannedAt = 1000L,
                chunkIndex = 0,
                chunkCount = 2,
            ),
        )

        assertFailsWith<IllegalStateException> { target.neverLoaded() }
    }

    @Test
    fun `neverLoaded diffs a complete scan correctly, excluding unsafe, unreadable, and unprobed buckets`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource = ResourceAttributes("svc", null, "i-1", null),
                declaredClasses =
                    listOf(
                        DeclaredClass("com.acme.Loaded", listOf(DeclaredMethod("m", "()V"))),
                        DeclaredClass("com.acme.Dead", listOf(DeclaredMethod("m", "()V"))),
                    ),
                staticallyUnsafeClasses = listOf(StaticallyUnsafeClass("com.acme.Unsafe", "jvmname")),
                unreadableClasses = listOf(UnreadableClass("com.acme.Bad", "corrupt")),
                unprobedClasses = listOf(UnprobedClass("com.acme.Marker", "interface only")),
                scannedAt = 1000L,
                chunkIndex = 0,
                chunkCount = 1,
            ),
        )
        exporter.exportManifest(
            ProbeManifest("svc", null, listOf(methodProbe(1, 0, "com.acme.Loaded", "m", "()V", 1)), serviceInstanceId = "i-1"),
        )

        assertEquals(listOf("com.acme.Dead"), target.neverLoaded())
    }

    @Test
    fun `neverLoaded excludes a declared class whose every declared method is inline`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource = ResourceAttributes("svc", null, "i-1", null),
                declaredClasses =
                    listOf(
                        DeclaredClass("com.acme.Dead", listOf(DeclaredMethod("m", "()V"))),
                        DeclaredClass("com.acme.AllInline", listOf(DeclaredMethod("m", "()V", inline = true))),
                    ),
                scannedAt = 1000L,
                chunkIndex = 0,
                chunkCount = 1,
            ),
        )

        assertEquals(listOf("com.acme.Dead"), target.neverLoaded())
    }

    @Test
    fun `a malformed body is rejected with 400 and nothing is recorded`() {
        val target = startCollector()
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest
                .newBuilder(URI.create("${target.endpoint}/v1/yukon/deltas"))
                .header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(0, 0, 0, 0)))
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.discarding())

        assertEquals(400, response.statusCode())
        assertFailsWith<TimeoutException> { target.awaitNextFlush(Duration.ofMillis(150)) }
    }

    @Test
    fun `wasCalled and callCount reflect a hit-bearing delta, neverCalled lists exactly the other endpoint`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                serviceInstanceId = "i-1",
                endpoints = listOf(endpoint(0, "GET", "/checkout"), endpoint(1, "GET", "/promo")),
            ),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                ResourceAttributes("svc", null, "i-1", null),
                emptyList(),
                endpointDeltas = listOf(EndpointDelta(0, 1L, 3L)),
            ),
        )

        assertTrue(target.wasCalled("GET", "/checkout"))
        assertFalse(target.wasCalled("GET", "/promo"))
        assertEquals(3L, target.callCount("GET", "/checkout"))
        assertEquals(0L, target.callCount("GET", "/promo"))
        assertEquals(listOf("GET"), target.neverCalled().map { it.verb })
        assertEquals(listOf("/promo"), target.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `two instances registering the same endpoint sum into one EndpointRef with a combined call count`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest("svc", null, emptyList(), serviceInstanceId = "i-1", endpoints = listOf(endpoint(0, "GET", "/checkout"))),
        )
        exporter.exportManifest(
            ProbeManifest("svc", null, emptyList(), serviceInstanceId = "i-2", endpoints = listOf(endpoint(0, "GET", "/checkout"))),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList(), endpointDeltas = listOf(EndpointDelta(0, 1L, 2L))),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, "i-2", null), emptyList(), endpointDeltas = listOf(EndpointDelta(0, 1L, 5L))),
        )

        assertEquals(7L, target.callCount("GET", "/checkout"))
        assertEquals(1, target.endpoints().count { it.verb == "GET" && it.routeTemplate == "/checkout" })
    }

    @Test
    fun `a re-delivered record carrying a handler join updates the EndpointRef`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest("svc", null, emptyList(), serviceInstanceId = "i-1", endpoints = listOf(endpoint(0, "GET", "/checkout"))),
        )
        assertEquals(null, target.endpoints().single().handlerClass)

        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                serviceInstanceId = "i-1",
                endpoints = listOf(endpoint(0, "GET", "/checkout", handlerClass = "com.acme.Checkout", handlerMethod = "handle")),
            ),
        )

        val ref = target.endpoints().single()
        assertEquals("com.acme.Checkout", ref.handlerClass)
        assertEquals("handle", ref.handlerMethod)
    }

    @Test
    fun `a re-delivered delta carrying a lower total does not reduce callCount`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest("svc", null, emptyList(), serviceInstanceId = "i-1", endpoints = listOf(endpoint(0, "GET", "/checkout"))),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList(), endpointDeltas = listOf(EndpointDelta(0, 1L, 5L))),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList(), endpointDeltas = listOf(EndpointDelta(0, 1L, 2L))),
        )

        assertEquals(5L, target.callCount("GET", "/checkout"))
    }

    @Test
    fun `wasCalled and callCount normalise their verb and route template arguments`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest("svc", null, emptyList(), serviceInstanceId = "i-1", endpoints = listOf(endpoint(0, "GET", "/checkout"))),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, "i-1", null), emptyList(), endpointDeltas = listOf(EndpointDelta(0, 1L, 1L))),
        )

        assertTrue(target.wasCalled("get", "/checkout/"))
        assertEquals(1L, target.callCount("get", "/checkout/"))
    }

    @Test
    fun `an unknown endpoint reports it was never mentioned anywhere as a last resort`() {
        val target = startCollector()

        val failure = assertFailsWith<UnknownEndpointException> { target.wasCalled("GET", "/nowhere") }
        assertTrue(failure.message!!.contains("never mentioned"), failure.message)
    }

    @Test
    fun `an unknown endpoint names a disabled module reported for the same framework`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                serviceInstanceId = "i-1",
                disabledEndpointModules = listOf(DisabledEndpointModule("spring-mvc", "linkage error against Spring 7", 1L)),
            ),
        )

        val failure = assertFailsWith<UnknownEndpointException> { target.wasCalled("GET", "/checkout") }
        assertTrue(failure.message!!.contains("spring-mvc"), failure.message)
        assertTrue(failure.message!!.contains("linkage error against Spring 7"), failure.message)
    }

    @Test
    fun `awaitEndpoint returns once a manifest mentions it, and times out if it never does`() {
        val target = startCollector()
        val exporter = exporterFor(target)

        thread(isDaemon = true) {
            Thread.sleep(50)
            exporter.exportManifest(
                ProbeManifest("svc", null, emptyList(), serviceInstanceId = "i-1", endpoints = listOf(endpoint(0, "GET", "/checkout"))),
            )
        }
        target.awaitEndpoint("GET", "/checkout", Duration.ofSeconds(2))

        assertFailsWith<TimeoutException> {
            target.awaitEndpoint("GET", "/never", Duration.ofMillis(150))
        }
    }

    @Test
    fun `disabledEndpointModules is distinct by module name and sorted`() {
        val target = startCollector()
        val exporter = exporterFor(target)
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                serviceInstanceId = "i-1",
                disabledEndpointModules =
                    listOf(
                        DisabledEndpointModule("ktor", "r1", 1L),
                        DisabledEndpointModule("jax-rs", "r2", 2L),
                    ),
            ),
        )
        exporter.exportManifest(
            ProbeManifest(
                "svc",
                null,
                emptyList(),
                serviceInstanceId = "i-2",
                disabledEndpointModules = listOf(DisabledEndpointModule("ktor", "r1-dup", 3L)),
            ),
        )

        assertEquals(listOf("jax-rs", "ktor"), target.disabledEndpointModules().map { it.module })
    }
}
