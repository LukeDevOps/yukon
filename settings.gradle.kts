plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "yukon"

include("bootstrap")
include("demo")
include("demo-spring")
include("testkit")
include("endpoints-api")
include("endpoints-jdk-httpserver")
include("endpoints-spring-webmvc")
include("endpoints-ktor-2")
include("endpoints-ktor-3")
include("endpoints-jaxrs")
include("endpoints-otel-bridge")
