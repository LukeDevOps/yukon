package io.github.lukedevops.demo.spring

import org.springframework.stereotype.Service

/**
 * Reached only through [OrderController]'s invoice endpoint, which the demo client never calls.
 * Loaded (it registers as a bean at startup) but never hit, the method-tier counterpart to the
 * invoice endpoint's own "never called" finding.
 */
@Service
class InvoiceService {
    fun render(id: String): String = "invoice for order $id"
}
