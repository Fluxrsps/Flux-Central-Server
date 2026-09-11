package dev.or2.central.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import javax.sql.DataSource

object FlywayMigrator {
    private val log = LoggerFactory.getLogger(FlywayMigrator::class.java)
    private const val DEFAULT_SCHEMA = "public"

    private fun configure(dataSource: DataSource): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(DEFAULT_SCHEMA)
            .defaultSchema(DEFAULT_SCHEMA)
            .createSchemas(true)
            .baselineOnMigrate(true)
            .outOfOrder(true)
            .initSql(
                """
                SET search_path TO $DEFAULT_SCHEMA;
                SET client_min_messages TO WARNING;
                """.trimIndent(),
            )
            .load()

    fun migrate(dataSource: DataSource) {
        ensurePublicSchema(dataSource)
        val result = configure(dataSource).migrate()
        dataSource.connection.use { conn ->
            val meta = conn.metaData
            PostgresStartupLog.logMigrations(log, meta.url, meta.userName.orEmpty(), result.migrationsExecuted)
        }
    }

    fun migrate(jdbcUrl: String, user: String, password: String) {
        val dataSource =
            object : DataSource {
                override fun getConnection() = DriverManager.getConnection(jdbcUrl, user, password)

                override fun getConnection(username: String?, password: String?) =
                    DriverManager.getConnection(jdbcUrl, username, password)

                override fun getLogWriter() = null

                override fun setLogWriter(out: java.io.PrintWriter?) = Unit

                override fun setLoginTimeout(seconds: Int) = Unit

                override fun getLoginTimeout(): Int = 0

                override fun getParentLogger() = throw java.sql.SQLFeatureNotSupportedException()

                override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLFeatureNotSupportedException()

                override fun isWrapperFor(iface: Class<*>?) = false
            }
        ensurePublicSchema(dataSource)
        val result =
            configure(
                dataSource,
            ).migrate()
        PostgresStartupLog.logMigrations(log, jdbcUrl, user, result.migrationsExecuted)
    }

    private fun ensurePublicSchema(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val exists =
                    stmt.executeQuery(
                        """
                        SELECT 1
                        FROM information_schema.schemata
                        WHERE schema_name = '$DEFAULT_SCHEMA'
                        """.trimIndent(),
                    ).use { it.next() }
                if (exists) {
                    return
                }
                stmt.execute("CREATE SCHEMA $DEFAULT_SCHEMA")
                stmt.execute("GRANT ALL ON SCHEMA $DEFAULT_SCHEMA TO public")
                stmt.execute("GRANT ALL ON SCHEMA $DEFAULT_SCHEMA TO postgres")
            }
        }
    }
}
