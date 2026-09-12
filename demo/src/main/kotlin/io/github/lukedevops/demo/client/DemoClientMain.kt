package io.github.lukedevops.demo.client

import io.github.lukedevops.demo.DemoPorts
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val REQUEST_COUNT = 20
private const val BELOW_THRESHOLD_TOTAL = 42.50

/**
 * Calls `/checkout` repeatedly, always with a total below the free-shipping threshold.
 * Never calls `/promo` at all.
 */
fun main() {
    val client = HttpClient.newHttpClient()
    repeat(REQUEST_COUNT) {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${DemoPorts.SERVER_PORT}/checkout?total=$BELOW_THRESHOLD_TOTAL"))
                .GET()
                .build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }
    println("yukon demo client: sent $REQUEST_COUNT checkout requests, never called /promo")
}
