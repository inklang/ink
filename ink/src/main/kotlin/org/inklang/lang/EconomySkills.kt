package org.inklang.lang

import java.util.UUID

/**
 * Economy built-in functions exposed to Ink scripts.
 * eco_balance(player) -> Int
 * eco_give(player, amount) -> Int
 * eco_take(player, amount) -> Int
 * eco_set(player, amount) -> Int
 * eco_transfer(from, to, amount) -> Boolean
 * eco_top(n) -> Array
 */
object EconomySkills {

    /**
     * Resolves a player name to a UUID.
     * Subclasses or runtime implementations can override this to use Bukkit APIs.
     */
    fun playerNameToUuid(name: String): String {
        return UUID.nameUUIDFromBytes("Offline:$name".toByteArray()).toString()
    }

    /**
     * Gets the current EconoDb instance. Must be set before eco_* functions can be used.
     */
    var dbProvider: (() -> EconoDb)? = null

    val db: EconoDb
        get() = dbProvider?.invoke() ?: error("Economy database not initialized. Set EconomySkills.dbProvider first.")

    // eco_balance(player: String) -> Int
    val BALANCE = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(0), "eco_balance")
        val uuid = playerNameToUuid(name)
        Value.Int(db.getBalance(uuid).toInt())
    }

    // eco_give(player: String, amount: Int) -> Int
    val GIVE = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(0), "eco_give")
        val amount = expectLong(args.getOrNull(1), "eco_give")
        val uuid = playerNameToUuid(name)
        Value.Int(db.giveCoins(uuid, name, amount).toInt())
    }

    // eco_take(player: String, amount: Int) -> Int
    val TAKE = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(0), "eco_take")
        val amount = expectLong(args.getOrNull(1), "eco_take")
        val uuid = playerNameToUuid(name)
        Value.Int(db.takeCoins(uuid, name, amount).toInt())
    }

    // eco_set(player: String, amount: Int) -> Int
    val SET = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(0), "eco_set")
        val amount = expectLong(args.getOrNull(1), "eco_set")
        val uuid = playerNameToUuid(name)
        Value.Int(db.setBalance(uuid, name, amount).toInt())
    }

    // eco_transfer(from: String, to: String, amount: Int) -> Boolean
    val TRANSFER = Value.NativeFunction { args ->
        val fromName = expectString(args.getOrNull(0), "eco_transfer")
        val toName = expectString(args.getOrNull(1), "eco_transfer")
        val amount = expectLong(args.getOrNull(2), "eco_transfer")
        val fromUuid = playerNameToUuid(fromName)
        val toUuid = playerNameToUuid(toName)
        val success = db.transferCoins(fromUuid, fromName, toUuid, toName, amount)
        if (success) Value.Boolean.TRUE else Value.Boolean.FALSE
    }

    // eco_top(n: Int) -> Array
    val TOP = Value.NativeFunction { args ->
        val n = (args.getOrNull(0) as? Value.Int)?.value?.toInt() ?: 5
        val top = db.getTopN(n)
        val rows = top.map { (playerName, balance) ->
            Builtins.newArray(mutableListOf(Value.String(playerName), Value.Int(balance.toInt())))
        }
        Builtins.newArray(rows.toMutableList())
    }

    private fun expectString(v: Value?, name: String): String = when (v) {
        is Value.String -> v.value
        is Value.Int -> v.value.toString()
        is Value.Double -> v.value.toLong().toString()
        else -> error("$name: expected string argument")
    }

    private fun expectLong(v: Value?, name: String): Long = when (v) {
        is Value.Int -> v.value.toLong()
        is Value.Double -> v.value.toLong()
        else -> error("$name: expected integer argument")
    }

    /**
     * All eco_* functions as a map for registration.
     */
    val ALL = mapOf(
        "eco_balance" to BALANCE,
        "eco_give" to GIVE,
        "eco_take" to TAKE,
        "eco_set" to SET,
        "eco_transfer" to TRANSFER,
        "eco_top" to TOP
    )
}
