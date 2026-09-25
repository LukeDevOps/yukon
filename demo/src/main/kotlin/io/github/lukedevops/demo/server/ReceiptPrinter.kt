package io.github.lukedevops.demo.server

/**
 * Loaded and never instantiated. [main] names this class through a class literal, as it does
 * [AuditLog], but this class has no static initialiser, so the only signal left is that none of
 * its constructors ran while it declares an instance method.
 */
class ReceiptPrinter(
    private val width: Int,
) {
    fun print(lines: List<String>): String = lines.joinToString("\n") { it.padEnd(width) }
}
