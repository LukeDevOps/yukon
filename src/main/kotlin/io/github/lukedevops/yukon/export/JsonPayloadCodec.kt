package io.github.lukedevops.yukon.export

import java.nio.charset.StandardCharsets

/**
 * Minimal, dependency-free JSON encoding for the two payload shapes. An
 * interim wire format pending a compact binary schema; the [Exporter]
 * interface doesn't need to change when this codec is swapped out for one.
 */
object JsonPayloadCodec {
    fun encode(batch: DeltaBatch): ByteArray {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"resource\":")
        writeResource(sb, batch.resource)
        sb.append(",\"deltas\":[")
        batch.deltas.forEachIndexed { index, delta ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"classId\":").append(delta.classId)
            sb.append(",\"probeIndex\":").append(delta.probeIndex)
            sb.append(",\"kind\":\"").append(delta.kind.name).append('"')
            sb.append(",\"firstSeenAt\":").append(delta.firstSeenAt)
            sb.append(",\"hitsSinceLastFlush\":").append(delta.hitsSinceLastFlush)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun encode(manifest: ProbeManifest): ByteArray {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"serviceName\":").append(quote(manifest.serviceName))
        sb.append(",\"serviceVersion\":").append(manifest.serviceVersion?.let { quote(it) } ?: "null")
        sb.append(",\"probes\":[")
        manifest.probes.forEachIndexed { index, probe ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"classId\":").append(probe.classId)
            sb.append(",\"probeIndex\":").append(probe.probeIndex)
            sb.append(",\"kind\":\"").append(probe.kind.name).append('"')
            sb.append(",\"className\":").append(quote(probe.className))
            sb.append(",\"methodName\":").append(quote(probe.methodName))
            sb.append(",\"methodDescriptor\":").append(quote(probe.methodDescriptor))
            sb.append(",\"line\":").append(probe.line)
            sb.append(",\"branchIndex\":").append(probe.branchIndex ?: "null")
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeResource(
        sb: StringBuilder,
        resource: ResourceAttributes,
    ) {
        sb.append('{')
        sb.append("\"serviceName\":").append(quote(resource.serviceName))
        sb.append(",\"serviceVersion\":").append(resource.serviceVersion?.let { quote(it) } ?: "null")
        sb.append(",\"serviceInstanceId\":").append(quote(resource.serviceInstanceId))
        sb.append(",\"environment\":").append(resource.environment?.let { quote(it) } ?: "null")
        sb.append('}')
    }

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
