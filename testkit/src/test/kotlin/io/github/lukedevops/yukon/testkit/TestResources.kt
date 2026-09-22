package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ResourceAttributes

/**
 * Builds the [ResourceAttributes] a test hands to an `ExportScheduler`. The run id is fixed, so a
 * test can name it in an assertion. Mirrors the root project's own
 * `io.github.lukedevops.yukon.export.TestResources`, which this module's tests cannot see.
 */
object TestResources {
    const val RUN_ID = "run-1"

    /** [config]'s identity with [RUN_ID] as its run id. */
    fun forConfig(config: AgentConfig): ResourceAttributes =
        ResourceAttributes(
            serviceName = config.serviceName,
            serviceVersion = config.serviceVersion,
            serviceInstanceId = config.serviceInstanceId,
            environment = config.environment,
            runId = RUN_ID,
        )
}
