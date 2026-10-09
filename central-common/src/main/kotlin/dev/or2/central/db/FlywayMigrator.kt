package dev.or2.central.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

object FlywayMigrator {
    private val log = LoggerFactory.getLogger(FlywayMigrator::class.java)
    private const val DEFAULT_SCHEMA = "public"

    /**
     * `main` holds the saves relocated out of `public`, so its schema always exists even on a
     * database whose worlds all serve seasonal events only.
     */
    const val DEFAULT_WORLD_TYPE: String = "main"

    /** World-type keys double as schema names, so they must be safe to use as an identifier. */
    private val WORLD_TYPE_PATTERN = Regex("^[a-z][a-z0-9_]{0,62}$")

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

    /**
     * The per-world-type player-save set. Each mode's schema keeps its own `flyway_schema_history`.
     *
     * Baselines at version 0 rather than the default 1: the `main` schema already holds the tables
     * relocated out of `public`, and a version-1 baseline would mark V1 as applied without running
     * it, leaving every other mode's freshly created schema without its history row.
     */
    private fun configureWorldType(dataSource: DataSource, schema: String): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/world_type")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .initSql(
                """
                SET search_path TO "$schema", $DEFAULT_SCHEMA;
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
        migrateWorldTypes(dataSource, emptyList())
    }

    fun migrate(jdbcUrl: String, user: String, password: String) {
        val dataSource = driverManagerDataSource(jdbcUrl, user, password)
        ensurePublicSchema(dataSource)
        val result = configure(dataSource).migrate()
        PostgresStartupLog.logMigrations(log, jdbcUrl, user, result.migrationsExecuted)
        migrateWorldTypes(dataSource, emptyList())
    }

    fun migrateWorldTypes(jdbcUrl: String, user: String, password: String, worldTypes: Collection<String>) {
        migrateWorldTypes(driverManagerDataSource(jdbcUrl, user, password), worldTypes)
    }

    /**
     * Creates and migrates a player-save schema for `main` plus every world type in [worldTypes],
     * then rebuilds the cross-mode views. Safe to call repeatedly; a world that starts serving a new
     * mode picks it up on the next boot.
     *
     * `main` is included even when no world serves it, because it holds the saves V28 relocated out
     * of `public` and the cross-mode views would otherwise lose them.
     */
    fun migrateWorldTypes(dataSource: DataSource, worldTypes: Collection<String>) {
        val schemas =
            (listOf(DEFAULT_WORLD_TYPE) + worldTypes)
                .map { it.trim().lowercase() }
                .distinct()
                .filter { key ->
                    WORLD_TYPE_PATTERN.matches(key).also { valid ->
                        if (!valid) {
                            log.error("Skipping world type '{}': not a valid schema identifier.", key)
                        }
                    }
                }
        for (schema in schemas) {
            val executed = configureWorldType(dataSource, schema).migrate().migrationsExecuted
            if (executed > 0) {
                log.info("Applied {} player-save migration(s) to world type '{}'.", executed, schema)
            }
        }
        rebuildCrossWorldTypeViews(dataSource)
    }

    /**
     * Unions every mode's save tables into `public`, so queries that span world types - admin
     * tooling, and Central's own login gates and presence checks - have something to read. Character
     * identity needs no view: it already lives in `public.account_characters`.
     *
     * The schema set is discovered rather than passed in, because only the game server knows which
     * modes a world serves and Central must still see them all.
     */
    private fun rebuildCrossWorldTypeViews(dataSource: DataSource) {
        val schemas = worldTypeSchemas(dataSource)
        if (schemas.isEmpty()) {
            log.warn("No world-type schemas found; skipped the cross-mode save views.")
            return
        }
        val progress =
            schemas.joinToString("\nUNION ALL\n") { schema ->
                """
                SELECT p.character_id, '$schema' AS world_type,
                       p.world_id, p.x, p.z, p.level, p.last_login, p.last_logout, p.run_energy,
                       p.xp_rate_in_hundreds, p.online_central_world_id, p.online_session_heartbeat
                FROM "$schema".character_progress p
                """.trimIndent()
            }
        val stats =
            schemas.joinToString("\nUNION ALL\n") { schema ->
                """
                SELECT s.character_id, s.stat_id, s.vis_level, s.base_level, s.fine_xp,
                       s.updated_at, '$schema' AS world_type
                FROM "$schema".stats s
                """.trimIndent()
            }
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP VIEW IF EXISTS public.progress_all")
                stmt.execute("DROP VIEW IF EXISTS public.stats_all")
                stmt.execute("CREATE VIEW public.progress_all AS\n$progress")
                stmt.execute("CREATE VIEW public.stats_all AS\n$stats")
            }
        }
    }

    /** The world types this database holds saves for, identified by their `character_progress` table. */
    fun worldTypeSchemas(dataSource: DataSource): List<String> =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt
                    .executeQuery(
                        """
                        SELECT table_schema
                        FROM information_schema.tables
                        WHERE table_name = 'character_progress'
                          AND table_schema <> '$DEFAULT_SCHEMA'
                        ORDER BY table_schema
                        """.trimIndent(),
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                val schema = rs.getString("table_schema") ?: continue
                                if (WORLD_TYPE_PATTERN.matches(schema)) {
                                    add(schema)
                                }
                            }
                        }
                    }
            }
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

    private fun driverManagerDataSource(jdbcUrl: String, user: String, password: String): DataSource =
        object : DataSource {
            override fun getConnection(): Connection = DriverManager.getConnection(jdbcUrl, user, password)

            override fun getConnection(username: String?, password: String?): Connection =
                DriverManager.getConnection(jdbcUrl, username, password)

            override fun getLogWriter() = null

            override fun setLogWriter(out: java.io.PrintWriter?) = Unit

            override fun setLoginTimeout(seconds: Int) = Unit

            override fun getLoginTimeout(): Int = 0

            override fun getParentLogger() = throw java.sql.SQLFeatureNotSupportedException()

            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLFeatureNotSupportedException()

            override fun isWrapperFor(iface: Class<*>?) = false
        }
}
