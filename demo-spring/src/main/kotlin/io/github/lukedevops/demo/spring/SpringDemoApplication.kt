package io.github.lukedevops.demo.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * A Spring Boot fat-jar app, the first this project has run under the agent. It proves the
 * Spring MVC endpoint module against Boot's own launcher classloader and component-scanned
 * beans, and proves the static baseline's `BOOT-INF/classes` handling against a real `bootJar`,
 * rather than the flat classpath directory the plain demo server uses.
 */
@SpringBootApplication
class SpringDemoApplication

fun main(args: Array<String>) {
    runApplication<SpringDemoApplication>(*args)
}
