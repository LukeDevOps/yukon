plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "yukon"

include("bootstrap")
include("fixtures-scala3")
include("fixtures-scala2")
include("fixtures-kotlin-jvm-default-disable")
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
