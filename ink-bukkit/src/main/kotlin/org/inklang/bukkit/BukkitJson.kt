package org.inklang.bukkit

import org.inklang.InkJson
import org.inklang.lang.Value
import org.inklang.lang.Builtins

/**
 * Bukkit implementation of InkJson using org.json.
 */
class BukkitJson : InkJson {
    override fun parse(json: String): Value {
        return try {
            parseJsonValue(org.json.JSONObject(json))
        } catch (e: Exception) {
            try {
                parseJsonArray(org.json.JSONArray(json))
            } catch (e2: Exception) {
                throw RuntimeException("Invalid JSON: ${e.message}")
            }
        }
    }

    override fun stringify(value: Value): String = stringifyJsonValue(value)

    private fun parseJsonValue(json: org.json.JSONObject): Value.Instance {
        val map = Builtins.newMap()
        json.keys().forEach { key ->
            val v = json.get(key)
            val inkValue = when (v) {
                is org.json.JSONObject -> parseJsonValue(v)
                is org.json.JSONArray -> parseJsonArray(v)
                is String -> Value.String(v)
                is Int -> Value.Int(v)
                is Double -> Value.Double(v)
                is Boolean -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
                else -> Value.Null
            }
            (map.fields["__entries"] as Value.InternalMap).entries[Value.String(key)] = inkValue
        }
        return map
    }

    private fun parseJsonArray(arr: org.json.JSONArray): Value.Instance {
        val list = mutableListOf<Value>()
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            list.add(
                when (v) {
                    is org.json.JSONObject -> parseJsonValue(v)
                    is org.json.JSONArray -> parseJsonArray(v)
                    is String -> Value.String(v)
                    is Int -> Value.Int(v)
                    is Double -> Value.Double(v)
                    is Boolean -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
                    else -> Value.Null
                }
            )
        }
        return Builtins.newArray(list)
    }

    private fun stringifyJsonValue(value: Value): String {
        return when (value) {
            is Value.Instance -> {
                val entries = value.fields["__entries"]
                if (entries is Value.InternalMap) {
                    val sb = StringBuilder("{")
                    entries.entries.entries.forEachIndexed { i, (k, v) ->
                        if (i > 0) sb.append(", ")
                        sb.append("\"${(k as Value.String).value}\": ${stringifyJsonValue(v)}")
                    }
                    sb.append("}")
                    sb.toString()
                } else if (value.fields["__items"] is Value.InternalList) {
                    val items = (value.fields["__items"] as Value.InternalList).items
                    val sb = StringBuilder("[")
                    items.forEachIndexed { i, v ->
                        if (i > 0) sb.append(", ")
                        sb.append(stringifyJsonValue(v))
                    }
                    sb.append("]")
                    sb.toString()
                } else {
                    value.toString()
                }
            }
            is Value.String -> "\"${value.value}\""
            is Value.Int -> value.value.toString()
            is Value.Double -> value.value.toString()
            is Value.Boolean -> if (value.value) "true" else "false"
            is Value.Null -> "null"
            else -> value.toString()
        }
    }
}
