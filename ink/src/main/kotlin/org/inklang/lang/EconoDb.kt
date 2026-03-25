package org.inklang.lang

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * SQLite-backed economy account store.
 * All operations are blocking — call from async context if needed for production.
 */
class EconoDb(dbPath: String) {

    private val connection: Connection

    init {
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.autoCommit = false
        createTable()
    }

    private fun createTable() {
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS economy_accounts (
                    player_uuid TEXT PRIMARY KEY,
                    player_name TEXT NOT NULL,
                    balance INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
        connection.commit()
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    /**
     * Get player's balance. Returns 0 if account doesn't exist.
     */
    fun getBalance(uuid: String): Long {
        connection.prepareStatement("SELECT balance FROM economy_accounts WHERE player_uuid = ?").use { ps ->
            ps.setString(1, uuid)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong("balance") else 0L
            }
        }
    }

    /**
     * Ensure an account exists (creates with 0 balance if not).
     */
    fun ensureAccount(uuid: String, name: String) {
        connection.prepareStatement("""
            INSERT OR IGNORE INTO economy_accounts (player_uuid, player_name, balance, updated_at)
            VALUES (?, ?, 0, ?)
        """.trimIndent()).use { ps ->
            ps.setString(1, uuid)
            ps.setString(2, name)
            ps.setLong(3, now())
            ps.executeUpdate()
        }
        connection.commit()
    }

    /**
     * Update player's display name.
     */
    fun updateName(uuid: String, name: String) {
        connection.prepareStatement("UPDATE economy_accounts SET player_name = ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
            ps.setString(1, name)
            ps.setLong(2, now())
            ps.setString(3, uuid)
            ps.executeUpdate()
        }
        connection.commit()
    }

    /**
     * Set player's balance to exactly `amount`. Clamps to 0 if negative.
     * Returns the new balance.
     */
    fun setBalance(uuid: String, name: String, amount: Long): Long {
        val clamped = amount.coerceAtLeast(0)
        ensureAccount(uuid, name)
        connection.prepareStatement("UPDATE economy_accounts SET balance = ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
            ps.setLong(1, clamped)
            ps.setLong(2, now())
            ps.setString(3, uuid)
            ps.executeUpdate()
        }
        connection.commit()
        return clamped
    }

    /**
     * Add `amount` coins to player. Returns new balance.
     * Errors if result would exceed Long.MAX_VALUE.
     */
    fun giveCoins(uuid: String, name: String, amount: Long): Long {
        if (amount <= 0) return getBalance(uuid)
        ensureAccount(uuid, name)
        val current = getBalance(uuid)
        val newBalance = current + amount
        if (newBalance < 0) error("eco_give: balance overflow")
        setBalance(uuid, name, newBalance)
        return newBalance
    }

    /**
     * Remove up to `amount` coins from player. Returns new balance (clamped to 0).
     */
    fun takeCoins(uuid: String, name: String, amount: Long): Long {
        if (amount <= 0) return getBalance(uuid)
        ensureAccount(uuid, name)
        val current = getBalance(uuid)
        val newBalance = (current - amount).coerceAtLeast(0)
        setBalance(uuid, name, newBalance)
        return newBalance
    }

    /**
     * Transfer coins atomically from one player to another.
     * Returns true if successful, false if from player has insufficient funds.
     */
    fun transferCoins(fromUuid: String, fromName: String, toUuid: String, toName: String, amount: Long): Boolean {
        if (amount <= 0) return false
        ensureAccount(fromUuid, fromName)
        ensureAccount(toUuid, toName)
        val fromBalance = getBalance(fromUuid)
        if (fromBalance < amount) return false

        try {
            connection.autoCommit = false
            connection.prepareStatement("UPDATE economy_accounts SET balance = balance - ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
                ps.setLong(1, amount)
                ps.setLong(2, now())
                ps.setString(3, fromUuid)
                ps.executeUpdate()
            }
            connection.prepareStatement("UPDATE economy_accounts SET balance = balance + ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
                ps.setLong(1, amount)
                ps.setLong(2, now())
                ps.setString(3, toUuid)
                ps.executeUpdate()
            }
            connection.commit()
            return true
        } catch (e: SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Get top N richest players as list of [name, balance] pairs.
     */
    fun getTopN(n: Int): List<Pair<String, Long>> {
        if (n <= 0) return emptyList()
        connection.prepareStatement("SELECT player_name, balance FROM economy_accounts ORDER BY balance DESC LIMIT ?").use { ps ->
            ps.setInt(1, n)
            ps.executeQuery().use { rs ->
                val results = mutableListOf<Pair<String, Long>>()
                while (rs.next()) {
                    results.add(rs.getString("player_name") to rs.getLong("balance"))
                }
                return results
            }
        }
    }

    fun close() {
        connection.close()
    }
}
