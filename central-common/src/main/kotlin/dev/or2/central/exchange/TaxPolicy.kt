package dev.or2.central.exchange

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
