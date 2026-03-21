package org.inklang.lang

import java.sql.Connection
import java.sql.DriverManager

object TableRuntime {
    private var connection: Connection? = null

    fun getConnection(dbPath: String): Connection {
        if (connection == null || connection!!.isClosed) {
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        }
        return connection!!
    }

    fun createTable(conn: Connection, tableName: String, fields: List<TableFieldDef>) {
        val columns = fields.joinToString(", ") { field ->
            val sqlType = when (field.type) {
                "int" -> "INTEGER"
                "float", "double" -> "REAL"
                "string" -> "TEXT"
                "bool" -> "INTEGER"
                else -> "TEXT"
            }
            val pk = if (field.isKey) " PRIMARY KEY" else ""
            "${field.name} $sqlType$pk"
        }
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS $tableName ($columns)")
    }

    fun buildTableClass(tableName: String, fields: List<TableFieldDef>, dbPath: String): ClassDescriptor {
        val conn = getConnection(dbPath)
        createTable(conn, tableName, fields)

        return ClassDescriptor(
            name = tableName,
            superClass = null,
            methods = mapOf(
                "get" to Value.NativeFunction { args ->
                    val keyValue = args[1]
                    val keyField = fields.first { it.isKey }
                    val stmt = conn.prepareStatement("SELECT * FROM $tableName WHERE ${keyField.name} = ?")
                    bindValue(stmt, 1, keyValue)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        val instance = Value.Instance(Builtins.EnumValueClass)
                        for (field in fields) {
                            instance.fields[field.name] = readColumn(rs, field)
                        }
                        instance
                    } else {
                        Value.Null
                    }
                },
                "set" to Value.NativeFunction { args ->
                    val keyValue = args[1]
                    val mapInstance = args[2] as? Value.Instance
                        ?: error("Table set requires a map value")
                    val entries = (mapInstance.fields["__entries"] as? Value.InternalMap)?.entries
                        ?: error("Expected map with entries")

                    val columns = fields.joinToString(", ") { it.name }
                    val placeholders = fields.joinToString(", ") { "?" }
                    val stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO $tableName ($columns) VALUES ($placeholders)"
                    )
                    for ((i, field) in fields.withIndex()) {
                        val value = if (field.isKey) {
                            keyValue
                        } else {
                            entries[Value.String(field.name)] ?: field.defaultValue ?: Value.Null
                        }
                        bindValue(stmt, i + 1, value)
                    }
                    stmt.executeUpdate()
                    Value.Null
                },
                "delete" to Value.NativeFunction { args ->
                    val keyValue = args[1]
                    val keyField = fields.first { it.isKey }
                    val stmt = conn.prepareStatement("DELETE FROM $tableName WHERE ${keyField.name} = ?")
                    bindValue(stmt, 1, keyValue)
                    stmt.executeUpdate()
                    Value.Null
                }
            )
        )
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

    private fun readColumn(rs: java.sql.ResultSet, field: TableFieldDef): Value {
        return when (field.type) {
            "int" -> Value.Int(rs.getInt(field.name))
            "float" -> Value.Float(rs.getFloat(field.name))
            "double" -> Value.Double(rs.getDouble(field.name))
            "string" -> Value.String(rs.getString(field.name) ?: "")
            "bool" -> if (rs.getInt(field.name) != 0) Value.Boolean.TRUE else Value.Boolean.FALSE
            else -> Value.String(rs.getString(field.name) ?: "")
        }
    }
}

data class TableFieldDef(
    val name: String,
    val type: String,
    val isKey: Boolean,
    val defaultValue: Value?
)
