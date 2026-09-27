package dev.or2.central.exchange.sim

import javax.sql.DataSource

/**
 * Refuses to let the simulation touch anything but a throwaway database.
 *
 * The harness writes thousands of fabricated accounts, orders and trades. If it ever ran against
 * a live database it would poison the ledger the whole economy is audited from, and there is no
 * undo. So the check is here, in code, rather than left to whoever runs it: the connection must
 * be an embedded instance or a local database whose name says it is for tests.
 */
object TestDatabaseGuard {
    private val ALLOWED_HOSTS = setOf("localhost", "127.0.0.1", "::1")

    fun require(dataSource: DataSource) {
        val url = dataSource.connection.use { it.metaData.url }
        check(isDisposable(url)) {
            "Refusing to run the exchange simulation against '$url'. It must be an embedded " +
                "PostgreSQL instance or a local database whose name contains 'test'."
        }
    }

    fun isDisposable(url: String): Boolean {
        if (!url.startsWith("jdbc:postgresql://")) return false
        val authority = url.removePrefix("jdbc:postgresql://").substringBefore('?')
        val host = authority.substringBefore('/').substringBefore(':')
        if (host !in ALLOWED_HOSTS) return false
        val database = authority.substringAfter('/', "")
        // zonky's embedded instances are ephemeral and always local.
        if (database == "postgres" && authority.substringAfter(':', "").substringBefore('/').toIntOrNull()?.let { it != 5432 } == true) {
            return true
        }
        return database.contains("test", ignoreCase = true)
    }
}
