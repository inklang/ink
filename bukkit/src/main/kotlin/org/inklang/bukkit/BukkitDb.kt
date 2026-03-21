package org.inklang.bukkit

import org.inklang.InkDb
import org.inklang.InkQueryBuilder
import org.inklang.InkTableRef
import org.inklang.lang.Value
import org.inklang.lang.Builtins
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Bukkit implementation of InkDb using SQLite via JDBC.
 */
class BukkitDb(private val dbPath: String) : InkDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val tableInfoCache = mutableMapOf<String, TableInfo>()

    init {
        conn.createStatement().execute("PRAGMA foreign_keys = ON")
    }

    /** Closes the database connection. Call this when done with the database. */
    fun close() {
        conn.close()
    }

    override fun from(table: String): InkTableRef {
        val info = tableInfoCache[table]
            ?: error("Table '$table' not declared. Add 'table $table { ... }' at the top of your script.")
        return TableRefImpl(conn, info)
    }

    override fun registerTable(name: String, fields: List<String>, keyIndex: Int) {
        tableInfoCache[name] = TableInfo(name, fields, keyIndex)
    }

    data class TableInfo(
        val name: String,
        val fields: List<String>,
        val keyIndex: Int
    )

    inner class TableRefImpl(
        private val conn: Connection,
        private val info: TableInfo
    ) : InkTableRef {
        override fun all(): Value {
            val rs = conn.createStatement().executeQuery("SELECT * FROM ${info.name}")
            return resultSetToArray(rs)
        }

        override fun find(key: Value): Value? {
            val keyCol = info.fields[info.keyIndex]
            val pstmt = conn.prepareStatement("SELECT * FROM ${info.name} WHERE $keyCol = ?")
            bindValue(pstmt, 1, key)
            val rs = pstmt.executeQuery()
            return if (rs.next()) resultSetToInstance(rs) else null
        }

        override fun insert(data: Map<String, Value>): Value {
            // Auto-create table if not exists
            try {
                val columns = data.keys.joinToString(", ") { "\"$it\" TEXT" }
                conn.createStatement().execute("CREATE TABLE IF NOT EXISTS ${info.name} ($columns)")
            } catch (e: Exception) { /* table exists */ }

            val columns = data.keys.joinToString(", ") { "\"$it\"" }
            val placeholders = data.keys.joinToString(", ") { "?" }
            val pstmt = conn.prepareStatement("INSERT INTO ${info.name} ($columns) VALUES ($placeholders)")
            data.entries.forEachIndexed { i, (_, v) -> bindValue(pstmt, i + 1, v) }
            pstmt.executeUpdate()

            val keyCol = info.fields[info.keyIndex]
            val keyVal = data.entries.find { it.key == keyCol }?.value
            return if (keyVal != null) {
                find(keyVal) ?: error("Insert failed")
            } else {
                val rs = conn.createStatement().executeQuery("SELECT last_insert_rowid()")
                if (rs.next()) find(Value.Int(rs.getInt(1))) ?: error("Insert failed")
                else error("Insert failed")
            }
        }

        override fun update(key: Value, data: Map<String, Value>) {
            val keyCol = info.fields[info.keyIndex]
            val setClause = data.keys.joinToString(", ") { "\"$it\" = ?" }
            val pstmt = conn.prepareStatement("UPDATE ${info.name} SET $setClause WHERE $keyCol = ?")
            data.entries.forEachIndexed { i, (_, v) -> bindValue(pstmt, i + 1, v) }
            bindValue(pstmt, data.size + 1, key)
            pstmt.executeUpdate()
        }

        override fun delete(key: Value) {
            val keyCol = info.fields[info.keyIndex]
            val pstmt = conn.prepareStatement("DELETE FROM ${info.name} WHERE $keyCol = ?")
            bindValue(pstmt, 1, key)
            pstmt.executeUpdate()
        }

        override fun where(condition: String, vararg args: Value): InkQueryBuilder {
            return QueryBuilderImpl(this, conn, info, condition, args.toList())
        }

        override fun order(field: String, direction: String): InkTableRef {
            // Chainable but returns same TableRef; actual ordering applied in QueryBuilder
            return this
        }

        override fun limit(n: Int): InkTableRef {
            return this
        }

        internal fun resultSetToArray(rs: ResultSet): Value.Instance {
            val list = mutableListOf<Value>()
            while (rs.next()) { list.add(resultSetToInstance(rs)) }
            return Builtins.newArray(list)
        }

        internal fun resultSetToInstance(rs: ResultSet): Value.Instance {
            val instance = Value.Instance(Builtins.EnumValueClass, mutableMapOf())
            for (i in 1..rs.metaData.columnCount) {
                val colName = rs.metaData.getColumnName(i)
                val colValue = rs.getObject(i)
                instance.fields[colName] = resultSetValueToInkValue(colValue)
            }
            return instance
        }

        private fun resultSetValueToInkValue(v: Any?): Value = when (v) {
            is Int -> Value.Int(v)
            is Long -> Value.Int(v.toInt())
            is Double -> Value.Double(v)
            is Float -> Value.Float(v)
            is String -> Value.String(v)
            is Boolean -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
            null -> Value.Null
            else -> Value.String(v.toString())
        }

        private fun bindValue(stmt: java.sql.PreparedStatement, idx: Int, value: Value) {
            when (value) {
                is Value.Int -> stmt.setInt(idx, value.value)
                is Value.Float -> stmt.setFloat(idx, value.value)
                is Value.Double -> stmt.setDouble(idx, value.value)
                is Value.String -> stmt.setString(idx, value.value)
                is Value.Boolean -> stmt.setInt(idx, if (value.value) 1 else 0)
                is Value.Null -> stmt.setNull(idx, java.sql.Types.NULL)
                else -> stmt.setString(idx, value.toString())
            }
        }
    }

    inner class QueryBuilderImpl(
        private val tableRef: TableRefImpl,
        private val conn: Connection,
        private val info: TableInfo,
        private var condition: String,
        private var args: List<Value>
    ) : InkQueryBuilder {
        override fun order(field: String, direction: String): InkQueryBuilder {
            condition += " ORDER BY \"$field\" $direction"
            return this
        }

        override fun limit(n: Int): InkQueryBuilder {
            condition += " LIMIT $n"
            return this
        }

        override fun all(): Value {
            val pstmt = conn.prepareStatement("SELECT * FROM ${info.name} WHERE $condition")
            args.forEachIndexed { i, v -> bindValue(pstmt, i + 1, v) }
            val rs = pstmt.executeQuery()
            return tableRef.resultSetToArray(rs)
        }

        override fun first(): Value? {
            val pstmt = conn.prepareStatement("SELECT * FROM ${info.name} WHERE $condition LIMIT 1")
            args.forEachIndexed { i, v -> bindValue(pstmt, i + 1, v) }
            val rs = pstmt.executeQuery()
            return if (rs.next()) tableRef.resultSetToInstance(rs) else null
        }

        private fun bindValue(stmt: java.sql.PreparedStatement, idx: Int, value: Value) {
            when (value) {
                is Value.Int -> stmt.setInt(idx, value.value)
                is Value.Float -> stmt.setFloat(idx, value.value)
                is Value.Double -> stmt.setDouble(idx, value.value)
                is Value.String -> stmt.setString(idx, value.value)
                is Value.Boolean -> stmt.setInt(idx, if (value.value) 1 else 0)
                is Value.Null -> stmt.setNull(idx, java.sql.Types.NULL)
                else -> stmt.setString(idx, value.toString())
            }
        }
    }
}
