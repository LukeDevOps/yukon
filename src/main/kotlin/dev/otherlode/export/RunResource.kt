package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import java.util.UUID

/** [config]'s identity with a new random run id. The agent calls this once per process; see ADR 0032. */
fun ResourceAttributes.Companion.forNewRun(config: AgentConfig): ResourceAttributes =
    ResourceAttributes(
        serviceName = config.serviceName,
        serviceVersion = config.serviceVersion,
        serviceInstanceId = config.serviceInstanceId,
        environment = config.environment,
        runId = UUID.randomUUID().toString(),
        serviceNamespace = config.serviceNamespace,
        testRun = config.testRun,
    )
