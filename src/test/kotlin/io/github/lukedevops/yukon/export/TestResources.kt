package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig

/**
 * Builds the [ResourceAttributes] a test hands to an [ExportScheduler]. The run id is fixed, so a
 * test can name it in an assertion. The agent itself makes a random one per process with
 * [ResourceAttributes.forNewRun].
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
