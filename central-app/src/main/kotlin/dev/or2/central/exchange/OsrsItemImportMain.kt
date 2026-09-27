package dev.or2.central.exchange

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.or2.central.config.CentralConfig
import dev.or2.central.config.DataSourceFactory
import dev.or2.central.db.FlywayMigrator
import dev.or2.central.embed.EmbeddedPostgres
import dev.or2.central.exchange.health.AlertService
import dev.or2.central.exchange.ops.ItemAdminService
import dev.or2.central.exchange.ops.OsrsItemImporter
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * Seeds `exchange_items` from the OSRS real-time prices API and exits. Run it after a database
 * reset, or whenever the live OSRS figures should be pulled again:
 *
 * ```
 * gradlew :central-app:importOsrsItems
 * gradlew :central-app:importOsrsItems --args="--overwrite"
 * gradlew :central-app:importOsrsItems --args="--jdbc-url=jdbc:postgresql://127.0.0.1:5432/openrune"
 * ```
 *
 * With no `--jdbc-url` it reads the same `central-config.yaml` the server does, which may mean
 * starting an embedded PostgreSQL. Pass the URL when the database is already running and owned by
 * something else - the game server's same-instance PostgreSQL, for one - so this never manages a
 * lifecycle it does not own.
 *
 * Safe against a live database: base prices and buy limits are only filled where they are null
 * unless `--overwrite` is passed.
 */
object OsrsItemImportMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val overwrite = args.any { it == "--overwrite" || it == "-o" }
        val f2pOnly = args.any { it == "--f2p" }
        val jdbcUrl = args.value("--jdbc-url")
        val user = args.value("--user") ?: "postgres"
        val password = args.value("--password") ?: ""

        val config = CentralConfig.load()
        var ownsEmbedded = false
        val dataSource: DataSource =
            if (jdbcUrl != null) {
                HikariDataSource(
                    HikariConfig().apply {
                        this.jdbcUrl = jdbcUrl
                        this.username = user
                        this.password = password
                        maximumPoolSize = 2
                        poolName = "osrs-item-import"
                    },
                )
            } else {
                val (resolved, embedded) = EmbeddedPostgres.resolveConfig(config)
                ownsEmbedded = embedded
                DataSourceFactory.create(resolved)
            }

        try {
            FlywayMigrator.migrate(dataSource)
            val items = ItemAdminService(dataSource, engine = null, config = { ExchangeConfig.DEFAULT }, alerts = AlertService(dataSource))
            val before = items.itemCount()
            val importer =
                OsrsItemImporter(
                    items,
                    OsrsItemImporter.Settings(includeMembers = !f2pOnly),
                )
            val result = importer.import(staffCharacterId = 0, reason = "OSRS item seed (manual run)", overwrite = overwrite)
            println("exchange_items: $before -> ${items.itemCount()}")
            println(result.toString())
            printSample(dataSource)
        } catch (e: Exception) {
            System.err.println("OSRS item import failed: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        } finally {
            (dataSource as? HikariDataSource)?.close()
            if (ownsEmbedded) {
                EmbeddedPostgres.stop()
            }
        }
        exitProcess(0)
    }

    private fun Array<String>.value(flag: String): String? =
        firstOrNull { it.startsWith("$flag=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    /** A few familiar items, so an operator can see at a glance that the columns landed right. */
    private fun printSample(dataSource: DataSource) {
        val sample = listOf(4151 to "Abyssal whip", 385 to "Shark", 561 to "Nature rune", 20997 to "Twisted bow")
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT base_price, buy_limit, high_alch_value FROM exchange_items WHERE obj_id = ?").use { ps ->
                println("%-14s %14s %10s %12s".format("item", "base price", "limit", "high alch"))
                for ((id, name) in sample) {
                    ps.setInt(1, id)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            println("%-14s %14s %10s %12s".format(name, "%,d".format(rs.getLong(1)), rs.getInt(2), "%,d".format(rs.getLong(3))))
                        } else {
                            println("%-14s %14s".format(name, "(absent)"))
                        }
                    }
                }
            }
        }
    }
}
