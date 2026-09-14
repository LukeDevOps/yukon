package io.github.lukedevops.demo.spring

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Exits the process directly instead of relying on SIGTERM, which can race the agent's
 * shutdown-hook flush: the signal can arrive while that flush is still writing its final batch,
 * the same reasoning the plain demo server's own `/__shutdown` endpoint carries.
 */
@RestController
class ShutdownController {
    @PostMapping("/__shutdown")
    fun shutdown(): String {
        Thread { System.exit(0) }.start()
        return "shutting down"
    }
}
