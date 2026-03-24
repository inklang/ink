package org.inklang

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.inklang.lang.*
import org.inklang.grammar.CstNode
import org.inklang.grammar.GrammarPackage

// --- Serializable value types (compile-time constants only) ---

@Serializable
@JsonClassDiscriminator("t")
sealed class SerialValue {
    @Serializable @SerialName("null")   data object SNull   : SerialValue()
    @Serializable @SerialName("bool")   data class SBool(val v: Boolean) : SerialValue()
    @Serializable @SerialName("int")    data class SInt(val v: Int)     : SerialValue()
    @Serializable @SerialName("float")  data class SFloat(val v: Float)  : SerialValue()
    @Serializable @SerialName("double") data class SDouble(val v: Double): SerialValue()
    @Serializable @SerialName("string") data class SStr(val v: String)   : SerialValue()
    @Serializable @SerialName("event")  data class SEvent(
        val name: String,
        val params: List<List<String>>   // each inner list is [paramName, paramType]
    ) : SerialValue()
}

@Serializable
data class SerialUpvalue(val count: Int, val regs: List<Int>)

@Serializable
data class SerialClassInfo(
    val name: String,
    val superClass: String?,
    val methods: Map<String, Int>
)

@Serializable
data class SerialChunk(
    val code: List<Int>,
    val constants: List<SerialValue>,
    val strings: List<String>,
    val functions: List<SerialChunk>,
    val classes: List<SerialClassInfo>,
    val functionDefaults: List<List<Int?>>,
    val functionUpvalues: Map<String, SerialUpvalue>,  // key = funcIdx as String
    val spillSlotCount: Int,
    val cstTable: List<CstNode> = emptyList()
)

@Serializable
data class SerialConfigField(
    val name: String,
    val type: String,
    val defaultValue: SerialValue?
)

@Serializable
data class SerialScript(
    val name: String,
    val chunk: SerialChunk,
    val configDefinitions: Map<String, List<SerialConfigField>>
)

// --- Conversion: Value → SerialValue ---

fun Value.toSerial(): SerialValue = when (this) {
    Value.Null          -> SerialValue.SNull
    is Value.Boolean    -> SerialValue.SBool(value)
    is Value.Int        -> SerialValue.SInt(value)
    is Value.Float      -> SerialValue.SFloat(value)
    is Value.Double     -> SerialValue.SDouble(value)
    is Value.String     -> SerialValue.SStr(value)
    is Value.EventInfo  -> SerialValue.SEvent(name, params.map { listOf(it.first, it.second) })
    else -> throw IllegalArgumentException("Cannot serialize runtime value type: ${this::class.simpleName}")
}

fun SerialValue.toValue(): Value = when (this) {
    is SerialValue.SNull   -> Value.Null
    is SerialValue.SBool   -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
    is SerialValue.SInt    -> Value.Int(v)
    is SerialValue.SFloat  -> Value.Float(v)
    is SerialValue.SDouble -> Value.Double(v)
    is SerialValue.SStr    -> Value.String(v)
    is SerialValue.SEvent  -> Value.EventInfo(name, params.map { it[0] to it[1] })
}

// --- Conversion: Chunk ↔ SerialChunk ---

fun Chunk.toSerial(): SerialChunk = SerialChunk(
    code = code.toList(),
    constants = constants.map { it.toSerial() },
    strings = strings.toList(),
    functions = functions.map { it.toSerial() },
    classes = classes.map { SerialClassInfo(it.name, it.superClass, it.methods) },
    functionDefaults = functionDefaults.map { it.defaultChunks },
    functionUpvalues = functionUpvalues.entries.associate { (k, v) ->
        k.toString() to SerialUpvalue(v.first, v.second)
    },
    spillSlotCount = spillSlotCount,
    cstTable = cstTable.toList()
)

fun SerialChunk.toChunk(): Chunk {
    val chunk = Chunk()
    chunk.code.addAll(code)
    chunk.constants.addAll(constants.map { it.toValue() })
    chunk.strings.addAll(strings)
    chunk.functions.addAll(functions.map { it.toChunk() })
    chunk.classes.addAll(classes.map { ClassInfo(it.name, it.superClass, it.methods) })
    chunk.functionDefaults.addAll(functionDefaults.map { FunctionDefaults(it) })
    functionUpvalues.forEach { (k, v) ->
        chunk.functionUpvalues[k.toInt()] = v.count to v.regs
    }
    chunk.spillSlotCount = spillSlotCount
    chunk.cstTable.addAll(cstTable)
    return chunk
}

// --- InkScript serialization ---

private val json = Json { prettyPrint = false }

fun InkScript.serialize(): String {
    val serial = SerialScript(
        name = name,
        chunk = getChunk().toSerial(),
        configDefinitions = getConfigDefinitions().mapValues { (_, fields) ->
            fields.map { field ->
                SerialConfigField(
                    name = field.name,
                    type = field.type,
                    defaultValue = field.defaultValue?.toSerial()
                )
            }
        }
    )
    return json.encodeToString(serial)
}

fun inkScriptFromJson(jsonStr: String): InkScript {
    val serial = json.decodeFromString<SerialScript>(jsonStr)
    val chunk = serial.chunk.toChunk()
    val configDefinitions = serial.configDefinitions.entries.associate { (configName, fields) ->
        configName to fields.map { field ->
            ConfigFieldDef(
                name = field.name,
                type = field.type,
                defaultValue = field.defaultValue?.toValue()
            )
        }
    }
    return InkScript(serial.name, chunk, configDefinitions)
}
