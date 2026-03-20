package org.inklang.lang

import org.yaml.snakeyaml.Yaml
import java.io.File

object ConfigRuntime {
    fun loadConfig(
        configName: String,
        fields: List<ConfigFieldDef>,
        scriptDir: String
    ): Value.Instance {
        val fileName = configName.replace(Regex("([a-z])([A-Z])")) {
            "${it.groupValues[1]}-${it.groupValues[2].lowercase()}"
        }.lowercase() + ".yml"

        val file = File(scriptDir, fileName)
        @Suppress("UNCHECKED_CAST")
        val yamlData: Map<String, Any?> = if (file.exists()) {
            val yaml = Yaml()
            yaml.load<Map<String, Any?>>(file.inputStream()) ?: emptyMap()
        } else {
            emptyMap()
        }

        val configClass = ClassDescriptor(
            name = configName,
            superClass = null,
            methods = emptyMap(),
            readOnly = true
        )

        val instance = Value.Instance(configClass)
        for (field in fields) {
            val yamlValue = yamlData[field.name]
            val value = if (yamlValue != null) {
                convertYamlValue(yamlValue, field.type)
            } else {
                field.defaultValue ?: error("Config field '${field.name}' has no value in $fileName and no default")
            }
            instance.fields[field.name] = value
        }

        return instance
    }

    private fun convertYamlValue(value: Any?, type: String): Value {
        return when (type) {
            "int" -> Value.Int((value as Number).toInt())
            "float" -> Value.Float((value as Number).toFloat())
            "double" -> Value.Double((value as Number).toDouble())
            "string" -> Value.String(value.toString())
            "bool" -> if (value as kotlin.Boolean) Value.Boolean.TRUE else Value.Boolean.FALSE
            else -> Value.String(value.toString())
        }
    }
}

data class ConfigFieldDef(
    val name: String,
    val type: String,
    val defaultValue: Value?
)
