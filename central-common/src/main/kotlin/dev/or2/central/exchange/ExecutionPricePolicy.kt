package dev.or2.central.exchange

import dev.or2.central.exchange.model.ExchangeOrder

/**
 * Decides what a fill settles at, given the arriving order and the one already on the book. The
 * result must sit between the two limits inclusive. Implementations see only the two orders and
 * never the market price, by design.
 */
fun interface ExecutionPricePolicy {
    fun price(incoming: ExchangeOrder, resting: ExchangeOrder): Long
}

/** OSRS behaviour: whoever was on the book first sets the price. */
object RestingOrderPricePolicy : ExecutionPricePolicy {
    override fun price(incoming: ExchangeOrder, resting: ExchangeOrder): Long = resting.limitPrice
}
