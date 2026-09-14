package io.github.lukedevops.demo.client

import io.github.lukedevops.demo.DemoPorts
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val GET_ORDER_COUNT = 15
private const val CREATE_ORDER_COUNT = 5

/**
 * Drives the Spring Boot demo server: `GET /orders/{id}` and `POST /orders` repeatedly, plus one
 * unconstrained-verb `GET /ping`. Never calls the invoice endpoint or `DELETE /orders/{id}`, so
 * both stay "never called" in the endpoint report.
 */
fun main() {
    val client = HttpClient.newHttpClient()
    val base = "http://localhost:${DemoPorts.SPRING_SERVER_PORT}"

    repeat(GET_ORDER_COUNT) {
        val request = HttpRequest.newBuilder(URI.create("$base/orders/42")).GET().build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }
    repeat(CREATE_ORDER_COUNT) {
        val request =
            HttpRequest
                .newBuilder(URI.create("$base/orders"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }
    val pingRequest = HttpRequest.newBuilder(URI.create("$base/ping")).GET().build()
    client.send(pingRequest, HttpResponse.BodyHandlers.discarding())

    println(
        "yukon spring demo client: sent $GET_ORDER_COUNT GET /orders/{id}, $CREATE_ORDER_COUNT POST /orders, " +
            "and 1 GET /ping; never called the invoice endpoint or DELETE /orders/{id}",
    )
}
