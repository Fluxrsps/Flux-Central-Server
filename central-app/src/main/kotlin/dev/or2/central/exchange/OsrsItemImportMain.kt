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
