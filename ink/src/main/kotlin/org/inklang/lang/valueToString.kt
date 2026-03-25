package org.inklang.lang

fun valueToString(v: Value): String = when (v) {
    is Value.Boolean -> v.value.toString()
    is Value.Instance -> {
        val items = v.fields["__items"]
        val entries = v.fields["__entries"]
        val tuple = v.fields["__tuple"]
        when {
            items is Value.InternalList -> items.toString()
            entries is Value.InternalMap -> entries.toString()
            entries is Value.InternalSet -> entries.toString()
            tuple is Value.InternalTuple -> tuple.toString()
            v.fields.containsKey("name") && v.clazz.name == "EnumValue" -> v.fields["name"].toString()
            else -> v.toString()
        }
    }
    else -> v.toString()
}
