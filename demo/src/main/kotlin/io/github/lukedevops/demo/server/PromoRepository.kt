package io.github.lukedevops.demo.server

/**
 * Looked up only from [applyPromoCode][io.github.lukedevops.demo.server.applyPromoCode], which
 * `/promo`'s handler alone calls. Since the demo client never calls `/promo`, [find] never runs,
 * and this class itself never loads: it exists to grow the unreached cluster rooted at
 * `PromoHandler.handle` by one more method and one more class, alongside the endpoint method itself.
 */
internal object PromoRepository {
    fun find(code: String): Int = if (code.isBlank()) 0 else code.length
}
