package dev.or2.central.exchange

/**
 * Seller-side tax, computed per unit so the total is the same however an order is split into
 * fills. Integer maths only; the split form of floor(price * bps / 10000) stays exact without
 * overflowing for any price that fits a BIGINT.
 */
object TaxPolicy {
    const val BPS_SCALE: Long = 10_000

    fun unitTax(unitPrice: Long, rateBps: Int, capPerItem: Long, exempt: Boolean): Long {
        if (exempt || rateBps <= 0 || unitPrice <= 0) {
            return 0
        }
        val whole = (unitPrice / BPS_SCALE) * rateBps
        val part = ((unitPrice % BPS_SCALE) * rateBps) / BPS_SCALE
        return minOf(whole + part, capPerItem).coerceAtLeast(0)
    }

    fun fillTax(unitTax: Long, quantity: Long): Long = Math.multiplyExact(unitTax, quantity)
}
