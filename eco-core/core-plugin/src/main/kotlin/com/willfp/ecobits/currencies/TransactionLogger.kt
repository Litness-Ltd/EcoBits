package com.willfp.ecobits.currencies

import com.willfp.ecobits.plugin
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
private const val CSV_HEADER =
    "timestamp,id,type,player_uuid,player_name,currency,delta,old_balance,new_balance,actor_uuid,actor_name"

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

object TransactionLogger {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ecobits-txlog").also { it.isDaemon = true }
    }

    fun log(entry: TransactionEntry) {
        executor.submit { writeEntry(entry) }
    }

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun writeEntry(entry: TransactionEntry) {
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
            plugin.logger.warning("Failed to write transaction log: ${e.message}")
        }
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
