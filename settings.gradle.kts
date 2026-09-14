plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "yukon"

include("bootstrap")
include("demo")
include("testkit")
include("endpoints-api")
include("endpoints-jdk-httpserver")
include("endpoints-spring-webmvc")
