package com.willfp.ecobits.currencies

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.ecobits.plugin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.ExposedConnectionImpl
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val timestampFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneOffset.UTC)

private val dateFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd")
    .withZone(ZoneOffset.UTC)

data class TransactionEntry(
    val id: String,
    val timestamp: Instant,
    val type: TransactionType,
    val playerUUID: UUID,
    val playerName: String,
    val currencyId: String,
    val delta: BigDecimal,
    val oldBalance: BigDecimal,
    val newBalance: BigDecimal,
    val actorUUID: UUID?,
    val actorName: String?
)

private sealed interface TransactionBackend {
    fun write(entry: TransactionEntry)
    fun close() {}
}

private const val CSV_HEADER =
    "timestamp,id,type,player_uuid,player_name,currency,delta,old_balance,new_balance,actor_uuid,actor_name"

private class CsvBackend : TransactionBackend {

    override fun write(entry: TransactionEntry) {
        try {
            val date = dateFormatter.format(entry.timestamp)
            val logFile = File(plugin.dataFolder, "logs/transactions_$date.csv")
            logFile.parentFile.mkdirs()

            val isNew = !logFile.exists()
            logFile.appendText(buildString {
                if (isNew) appendLine(CSV_HEADER)
                appendLine(
                    "${timestampFormatter.format(entry.timestamp)}," +
                            "${entry.id}," +
                            "${entry.type}," +
                            "${entry.playerUUID}," +
                            "${escapeCsv(entry.playerName)}," +
                            "${entry.currencyId}," +
                            "${entry.delta.toPlainString()}," +
                            "${entry.oldBalance.toPlainString()}," +
                            "${entry.newBalance.toPlainString()}," +
                            "${entry.actorUUID ?: ""}," +
                            escapeCsv(entry.actorName ?: "")
                )
            })
        } catch (e: Exception) {
            plugin.logger.warning("[EcoBits] Failed to write transaction CSV: ${e.message}")
        }
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\""
        else value
}

private class TransactionTable(prefix: String) : Table("${prefix}transactions") {
    val rowId = integer("row_id").autoIncrement()
    val id = varchar("id", 36).index()

    /** ISO-8601 timestamp string: "yyyy-MM-dd HH:mm:ss" */
    val ts = varchar("timestamp", 32)
    val type = varchar("type", 16)
    val playerUuid = varchar("player_uuid", 36).index()
    val playerName = varchar("player_name", 64)
    val currency = varchar("currency", 64).index()
    val delta = decimal("delta", 20, 6)
    val oldBalance = decimal("old_balance", 20, 6)
    val newBalance = decimal("new_balance", 20, 6)
    val actorUuid = varchar("actor_uuid", 36).nullable()
    val actorName = varchar("actor_name", 64).nullable()
    override val primaryKey = PrimaryKey(rowId)
}

private class MariaDbBackend(cfg: Config) : TransactionBackend {

    private val dataSource: HikariDataSource
    private val database: Database
    private val table: TransactionTable

    init {
        val host = cfg.getString("host")
        val port = cfg.getInt("port")
        val dbName = cfg.getString("database")
        val user = cfg.getString("user")
        val password = cfg.getString("password")
        val connections = cfg.getInt("connections")
        val prefix = cfg.getString("table-prefix")

        val hikariCfg = HikariConfig().apply {
            driverClassName = "org.mariadb.jdbc.Driver"
            jdbcUrl = "jdbc:mariadb://$host:$port/$dbName"
            username = user
            this.password = password
            maximumPoolSize = connections
            poolName = "EcoBits-TxLog"
        }

        dataSource = HikariDataSource(hikariCfg)
        database = Database.connect(dataSource, connectionAutoRegistration = ExposedConnectionImpl())
        table = TransactionTable(prefix)

        @Suppress("DEPRECATION")
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(table)
        }

        plugin.logger.info("[EcoBits] Transaction logger connected to MariaDB ($host:$port/$dbName).")
    }

    override fun write(entry: TransactionEntry) {
        try {
            transaction(database) {
                table.insert {
                    it[table.id] = entry.id
                    it[table.ts] = timestampFormatter.format(entry.timestamp)
                    it[table.type] = entry.type.name
                    it[table.playerUuid] = entry.playerUUID.toString()
                    it[table.playerName] = entry.playerName
                    it[table.currency] = entry.currencyId
                    it[table.delta] = entry.delta.setScale(6, RoundingMode.HALF_UP)
                    it[table.oldBalance] = entry.oldBalance.setScale(6, RoundingMode.HALF_UP)
                    it[table.newBalance] = entry.newBalance.setScale(6, RoundingMode.HALF_UP)
                    it[table.actorUuid] = entry.actorUUID?.toString()
                    it[table.actorName] = entry.actorName
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[EcoBits] Failed to write transaction to MariaDB: ${e.message}")
        }
    }

    override fun close() {
        dataSource.close()
    }
}

object TransactionLogger {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ecobits-txlog").also { it.isDaemon = true }
    }

    @Volatile
    private var backend: TransactionBackend? = null

    /**
     * Reads the `transaction-log` subsection from [configYml] and starts the
     * appropriate backend.  Safe to call on plugin enable; reconnecting on
     * live reload is not supported – restart the server to apply connection
     * changes.
     */
    fun initialize(configYml: Config) {
        val cfg = configYml.getSubsection("transaction-log")
        val newBackend: TransactionBackend? = when (cfg.getString("backend").lowercase()) {
            "mariadb", "mysql" -> {
                try {
                    MariaDbBackend(cfg)
                } catch (e: Exception) {
                    plugin.logger.severe(
                        "[EcoBits] Failed to start MariaDB transaction logger: ${e.message}"
                    )
                    null
                }
            }

            "csv" -> CsvBackend()
            else -> null   // "none" or unrecognised → disabled
        }

        // Swap backend (old one stays open until the executor drains; we only
        // close it from shutdown() to avoid discarding in-flight writes).
        backend = newBackend
    }

    fun log(entry: TransactionEntry) {
        val b = backend ?: return
        executor.submit { b.write(entry) }
    }

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        backend?.close()
        backend = null
    }
}
