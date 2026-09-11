package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.instrument.Instrumentation

/** `-javaagent:yukon-agent.jar` entry point. */
object Agent {

    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        val config = AgentConfig.parse(agentArgs)
        val registry = ProbeRegistry()

        YukonInstrumentation(config, registry).install(instrumentation)

        val exporter = HttpOtlpStyleExporter(config.collectorEndpoint)
        ExportScheduler(config, registry, exporter).start()
    }
}
