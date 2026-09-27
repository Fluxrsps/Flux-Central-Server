package dev.or2.central.exchange

import dev.or2.central.exchange.model.ExchangeOrder

fun interface ExecutionPricePolicy {
    fun price(incoming: ExchangeOrder, resting: ExchangeOrder): Long
}

object RestingOrderPricePolicy : ExecutionPricePolicy {
    override fun price(incoming: ExchangeOrder, resting: ExchangeOrder): Long = resting.limitPrice
}
