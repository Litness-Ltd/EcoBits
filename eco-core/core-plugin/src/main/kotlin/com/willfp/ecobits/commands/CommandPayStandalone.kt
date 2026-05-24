package com.willfp.ecobits.commands

import com.willfp.eco.core.command.impl.PluginCommand
import com.willfp.eco.util.StringUtils
import com.willfp.eco.util.savedDisplayName
import com.willfp.ecobits.currencies.Currencies
import com.willfp.ecobits.currencies.TransactionType
import com.willfp.ecobits.currencies.adjustBalance
import com.willfp.ecobits.currencies.decimalFormat
import com.willfp.ecobits.currencies.decimalFormatShort
import com.willfp.ecobits.currencies.format
import com.willfp.ecobits.currencies.formatShort
import com.willfp.ecobits.currencies.getBalance
import com.willfp.ecobits.currencies.hasDecimals
import com.willfp.ecobits.currencies.numOfDecimals
import com.willfp.ecobits.plugin
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil
import java.math.BigDecimal
import java.util.UUID

/**
 * Standalone /pay <player> <currency> <amount> command.
 *
 * Only currencies with `payable: true` in their config are accepted and
 * suggested in tab completion.
 */
object CommandPayStandalone : PluginCommand(
    plugin,
    "pay",
    "ecobits.command.pay",
    false
) {
    override fun onExecute(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(plugin.langYml.getMessage("not-player"))
            return
        }

        val player: Player = sender

        if (args.isEmpty()) {
            player.sendMessage(plugin.langYml.getMessage("must-specify-player"))
            return
        }

        @Suppress("DEPRECATION")
        val recipient = Bukkit.getOfflinePlayerIfCached(args[0])

        if (recipient == null || (!recipient.hasPlayedBefore() && !recipient.isOnline) || recipient.uniqueId == player.uniqueId) {
            player.sendMessage(plugin.langYml.getMessage("invalid-player"))
            return
        }

        if (args.size < 2) {
            player.sendMessage(plugin.langYml.getMessage("must-specify-currency"))
            return
        }

        val currency = Currencies.getByID(args[1].lowercase())

        if (currency == null || !currency.isPayable) {
            player.sendMessage(plugin.langYml.getMessage("invalid-currency"))
            return
        }

        if (args.size < 3) {
            player.sendMessage(plugin.langYml.getMessage("must-specify-amount"))
            return
        }

        val amount = args[2].toBigDecimalOrNull()

        if (amount == null || amount <= BigDecimal.ZERO) {
            player.sendMessage(plugin.langYml.getMessage("invalid-amount"))
            return
        }

        if (amount.hasDecimals() && !currency.isDecimal) {
            player.sendMessage(plugin.langYml.getMessage("invalid-amount"))
            return
        }

        if (currency.maxDecimals != null && amount.numOfDecimals() > currency.maxDecimals && currency.isDecimal) {
            player.sendMessage(plugin.langYml.getMessage("invalid-amount"))
            return
        }

        if (player.getBalance(currency) < amount) {
            player.sendMessage(plugin.langYml.getMessage("cannot-afford"))
            return
        }

        if (currency.max != null && recipient.getBalance(currency) + amount > currency.max) {
            player.sendMessage(plugin.langYml.getMessage("too-much"))
            return
        }

        val txId = UUID.randomUUID().toString()
        recipient.adjustBalance(currency, amount, TransactionType.PAY, player.name, player.uniqueId, txId)
        player.adjustBalance(currency, -amount, TransactionType.PAY, player.name, player.uniqueId, txId)

        // Notify recipient if online
        if (recipient.isOnline) {
            (recipient.player as Player).sendMessage(
                plugin.langYml.getMessage("received-money", StringUtils.FormatOption.WITHOUT_PLACEHOLDERS)
                    .replace("%player%", player.savedDisplayName)
                    .replace("%amount%", amount.decimalFormat(currency))
                    .replace("%amount_short%", amount.decimalFormatShort(currency))
                    .replace("%amount_formatted%", amount.format(currency))
                    .replace("%amount_formatted_short%", amount.formatShort(currency))
                    .replace("%amount_raw%", amount.toPlainString())
                    .replace("%amount_integer%", amount.toInt().toString())
                    .replace("%currency%", currency.name)
                    .replace("%symbol%", currency.symbol)
            )
        }

        player.sendMessage(
            plugin.langYml.getMessage("paid-player", StringUtils.FormatOption.WITHOUT_PLACEHOLDERS)
                .replace("%player%", recipient.savedDisplayName)
                .replace("%amount%", amount.decimalFormat(currency))
                .replace("%amount_short%", amount.decimalFormatShort(currency))
                .replace("%amount_formatted%", amount.format(currency))
                .replace("%amount_formatted_short%", amount.formatShort(currency))
                .replace("%amount_raw%", amount.toPlainString())
                .replace("%amount_integer%", amount.toInt().toString())
                .replace("%currency%", currency.name)
                .replace("%symbol%", currency.symbol)
        )
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        val completions = mutableListOf<String>()

        if (args.isEmpty()) {
            return Bukkit.getOnlinePlayers().map { it.name }
        }

        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0],
                Bukkit.getOnlinePlayers().map { it.name },
                completions
            )
        }

        if (args.size == 2) {
            // Only suggest currencies that have payable: true
            StringUtil.copyPartialMatches(
                args[1],
                Currencies.values().filter { it.isPayable }.map { it.id },
                completions
            )
        }

        if (args.size == 3) {
            StringUtil.copyPartialMatches(
                args[2],
                listOf("1", "2", "3", "4", "5"),
                completions
            )
        }

        return completions
    }
}
