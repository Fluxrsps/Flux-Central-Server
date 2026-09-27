package dev.or2.central.exchange.sim

import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.model.ClaimResult
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.RecoveryMarkers
import dev.or2.central.exchange.model.Side
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.random.Random

/**
 * Simulated traders for tuning and abuse testing, Part P14.
 *
 * Every action a player can take is here, including the ones that go wrong: orders abandoned
 * between the two creation steps (a crash), recovery with and without the save marker, cancels
 * racing fills. The point is not that any single sequence is realistic but that no sequence,
 * however ugly, can break conservation.
 *
 * Test-only by construction: it lives in the test source set and [TestDatabaseGuard] refuses to
 * run it against anything but a disposable database.
 */
class ExchangeSimulation(
    private val dataSource: DataSource,
    private val engine: ExchangeEngine,
    private val traderCount: Int,
    private val itemCount: Int,
    private val seed: Long = 1,
) {
    init {
        TestDatabaseGuard.require(dataSource)
    }

    val traders: List<Int> by lazy { (1..traderCount).map { newCharacter("sim_${runId}_$it") } }
    val items: List<Int> by lazy { (1..itemCount).map { newItem(nextItemId.incrementAndGet()) } }

    /** Orders whose assets the player "has" but which were never opened: a crash mid-creation. */
    private val abandoned = HashMap<Int, MutableSet<Long>>()

    private val random = Random(seed)

    /** Per instance, not per JVM: several simulations share one database inside a test class. */
    private val runId = UUID.randomUUID().toString().take(8)

    class Stats {
        val created = AtomicInteger()
        val opened = AtomicInteger()
        val cancelled = AtomicInteger()
        val claimed = AtomicInteger()
        val abandoned = AtomicInteger()
        val recovered = AtomicInteger()
        val fills = AtomicLong()
        val rejected = AtomicInteger()

        override fun toString(): String =
            "created=$created opened=$opened fills=$fills cancelled=$cancelled claimed=$claimed " +
                "abandoned=$abandoned recovered=$recovered rejected=$rejected"
    }

    val stats = Stats()

    /** Runs [steps] random actions in sequence. Returns the stats for the run. */
    fun run(steps: Int): Stats {
        repeat(steps) { step(random) }
        return stats
    }

    /** One random action. Safe to call from several threads; each gets its own [rng]. */
    fun step(rng: Random) {
        when (rng.nextInt(100)) {
            in 0..44 -> placeOrder(rng)
            in 45..59 -> abandonOrder(rng)
            in 60..69 -> cancelRandom(rng)
            in 70..79 -> claimRandom(rng)
            in 80..87 -> recoverRandom(rng)
            in 88..94 -> engine.sweep().also { stats.fills.addAndGet(it.toLong()) }
            else -> engine.expireDue()
        }
    }

    fun placeOrder(rng: Random) {
        val trader = traders.random(rng)
        val item = items.random(rng)
        val side = if (rng.nextBoolean()) Side.BUY else Side.SELL
        val quantity = rng.nextLong(1, 200)
        val price = rng.nextLong(50, 500)
        val created = engine.createOrder(request(trader, item, side, quantity, price))
        if (created !is CreateOrderResult.Pending) {
            stats.rejected.incrementAndGet()
            return
        }
        stats.created.incrementAndGet()
        engine.openOrder(created.order.id, trader)
        stats.opened.incrementAndGet()
        var pass = engine.matchPass(created.order.id)
        stats.fills.addAndGet(pass.fills.size.toLong())
        var guard = 0
        while (pass.fills.isNotEmpty() && pass.order?.status?.live == true && guard++ < 10) {
            pass = engine.matchPass(created.order.id)
            stats.fills.addAndGet(pass.fills.size.toLong())
        }
    }

    /** Creates an order and stops: the world took the assets but never told the exchange. */
    fun abandonOrder(rng: Random) {
        val trader = traders.random(rng)
        val item = items.random(rng)
        val side = if (rng.nextBoolean()) Side.BUY else Side.SELL
        val created = engine.createOrder(request(trader, item, side, rng.nextLong(1, 50), rng.nextLong(50, 500)))
        if (created !is CreateOrderResult.Pending) {
            stats.rejected.incrementAndGet()
            return
        }
        stats.abandoned.incrementAndGet()
        // Half the time the player's save carries the marker, half the time it never got written.
        if (rng.nextBoolean()) {
            synchronized(abandoned) { abandoned.getOrPut(trader) { linkedSetOf() }.add(created.order.id) }
        }
    }

    fun cancelRandom(rng: Random) {
        val trader = traders.random(rng)
        val live = engine.liveOrders(trader).filter { it.status.live }
        val order = live.randomOrNull(rng) ?: return
        engine.cancel(order.id, trader, CancelReason.PLAYER)
        stats.cancelled.incrementAndGet()
    }

    fun claimRandom(rng: Random) {
        val trader = traders.random(rng)
        val box = engine.collectionBox(trader)
        if (box.isEmpty) return
        val claimItems = box.items.entries.toList()
        val result =
            if (claimItems.isNotEmpty() && (box.gp == 0L || rng.nextBoolean())) {
                val (objId, held) = claimItems.random(rng)
                engine.beginClaim(trader, UUID.randomUUID().toString(), objId, rng.nextLong(1, held + 1), 0)
            } else {
                engine.beginClaim(trader, UUID.randomUUID().toString(), null, 0, rng.nextLong(1, box.gp + 1))
            }
        if (result !is ClaimResult.Started) return
        stats.claimed.incrementAndGet()
        // Most claims reach the player; some are released, standing in for a full inventory.
        if (rng.nextInt(10) == 0) engine.releaseClaim(result.claim.id) else engine.completeClaim(result.claim.id)
    }

    /** Login recovery for one trader, using whatever markers their "save" holds. */
    fun recoverRandom(rng: Random) {
        val trader = traders.random(rng)
        val markers = synchronized(abandoned) { abandoned[trader]?.toSet() ?: emptySet() }
        val actions = engine.recover(trader, RecoveryMarkers(markers, emptySet()))
        if (actions.isEmpty()) return
        stats.recovered.addAndGet(actions.size)
        synchronized(abandoned) { abandoned.remove(trader) }
    }

    /** Recovers every trader, so nothing is left PENDING at the end of a run. */
    fun recoverAll() {
        for (trader in traders) {
            val markers = synchronized(abandoned) { abandoned[trader]?.toSet() ?: emptySet() }
            stats.recovered.addAndGet(engine.recover(trader, RecoveryMarkers(markers, emptySet())).size)
        }
        synchronized(abandoned) { abandoned.clear() }
    }

    private fun request(trader: Int, item: Int, side: Side, quantity: Long, price: Long) =
        CreateOrderRequest(trader, item, side, quantity, price, UUID.randomUUID().toString())

    private fun newItem(id: Int): Int {
        exec("INSERT INTO exchange_items (obj_id, base_price) VALUES ($id, 100) ON CONFLICT DO NOTHING")
        return id
    }

    private fun newCharacter(name: String): Int {
        exec("INSERT INTO accounts (account_name, password_hash) VALUES ('$name', 'x')")
        exec("INSERT INTO account_characters (account_id, display_name) SELECT id, '$name' FROM accounts WHERE account_name = '$name'")
        return dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id FROM account_characters WHERE display_name = '$name'").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    private fun exec(sql: String) {
        dataSource.connection.use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    private companion object {
        val nextItemId = AtomicInteger(500_000)
    }
}
