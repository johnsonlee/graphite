package io.johnsonlee.graphite.cypher

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal typealias CypherScalarFunction = (List<Any?>, (() -> Unit)?) -> Any?

internal object CypherFunctionRegistry {
    val functions: Map<String, CypherScalarFunction> = mapOf<String, CypherScalarFunction>(
        "id" to { args, _ -> CypherEntityFunctions.id(args[0]) },
        "elementid" to { args, _ -> CypherEntityFunctions.elementId(args[0]) },
        "qualifiedid" to { args, _ -> CypherEntityFunctions.elementId(args[0]) },
        "graphid" to { args, _ -> CypherEntityFunctions.graphId(args[0]) },
        "coalesce" to { args, _ -> CypherScalarValues.coalesce(args) },
        "timestamp" to { _, _ -> System.currentTimeMillis() },
        "tointeger" to { args, _ -> CypherScalarValues.toInteger(args[0]) },
        "toint" to { args, _ -> CypherScalarValues.toInteger(args[0]) },
        "tofloat" to { args, _ -> CypherScalarValues.toFloat(args[0]) },
        "toboolean" to { args, _ -> CypherScalarValues.toBoolean(args[0]) },
        "tostring" to { args, _ -> args[0]?.toString() },
        "properties" to { args, _ -> CypherEntityFunctions.properties(args[0]) },
        "keys" to { args, _ -> CypherEntityFunctions.keys(args[0]) },
        "labels" to { args, _ -> CypherEntityFunctions.labels(args[0]) },
        "type" to { args, _ -> CypherEntityFunctions.type(args[0]) },
        "tolower" to { args, _ -> (args[0] as? String)?.lowercase() },
        "tolowercase" to { args, _ -> (args[0] as? String)?.lowercase() },
        "toupper" to { args, _ -> (args[0] as? String)?.uppercase() },
        "touppercase" to { args, _ -> (args[0] as? String)?.uppercase() },
        "trim" to { args, _ -> (args[0] as? String)?.trim() },
        "ltrim" to { args, _ -> (args[0] as? String)?.trimStart() },
        "rtrim" to { args, _ -> (args[0] as? String)?.trimEnd() },
        "replace" to { args, _ -> (args[0] as? String)?.replace(args[1] as String, args[2] as String) },
        "substring" to { args, _ -> CypherCollectionFunctions.substring(args) },
        "split" to { args, _ -> (args[0] as? String)?.split(args[1] as String) },
        "size" to { args, _ -> CypherCollectionFunctions.size(args[0]) },
        "length" to { args, _ -> CypherCollectionFunctions.size(args[0]) },
        "left" to { args, _ -> (args[0] as? String)?.take((args[1] as Number).toInt()) },
        "right" to { args, _ -> (args[0] as? String)?.takeLast((args[1] as Number).toInt()) },
        "reverse" to { args, checkCancelled -> CypherCollectionFunctions.reverse(args[0], checkCancelled) },
        "head" to { args, _ -> (args[0] as? List<*>)?.firstOrNull() },
        "tail" to { args, checkCancelled -> CypherCollectionFunctions.tail(args[0], checkCancelled) },
        "last" to { args, _ -> (args[0] as? List<*>)?.lastOrNull() },
        "range" to { args, checkCancelled -> CypherCollectionFunctions.range(args, checkCancelled) },
        "nodes" to { args, checkCancelled -> CypherCollectionFunctions.nodes(args[0], checkCancelled) },
        "relationships" to { args, checkCancelled -> CypherCollectionFunctions.relationships(args[0], checkCancelled) },
        "abs" to { args, _ -> CypherScalarValues.abs(args[0]) },
        "ceil" to { args, _ -> ceil(CypherScalarValues.toDouble(args[0])) },
        "floor" to { args, _ -> floor(CypherScalarValues.toDouble(args[0])) },
        "round" to { args, _ -> CypherScalarValues.roundHalfUp(CypherScalarValues.toDouble(args[0])) },
        "sign" to { args, _ -> CypherScalarValues.sign(args[0]) },
        "rand" to { _, _ -> Math.random() },
        "sqrt" to { args, _ -> sqrt(CypherScalarValues.toDouble(args[0])) },
        "exp" to { args, _ -> exp(CypherScalarValues.toDouble(args[0])) },
        "log" to { args, _ -> ln(CypherScalarValues.toDouble(args[0])) },
        "log10" to { args, _ -> log10(CypherScalarValues.toDouble(args[0])) },
        "e" to { _, _ -> Math.E },
        "sin" to { args, _ -> sin(CypherScalarValues.toDouble(args[0])) },
        "cos" to { args, _ -> cos(CypherScalarValues.toDouble(args[0])) },
        "tan" to { args, _ -> tan(CypherScalarValues.toDouble(args[0])) },
        "asin" to { args, _ -> asin(CypherScalarValues.toDouble(args[0])) },
        "acos" to { args, _ -> acos(CypherScalarValues.toDouble(args[0])) },
        "atan" to { args, _ -> atan(CypherScalarValues.toDouble(args[0])) },
        "atan2" to { args, _ -> atan2(CypherScalarValues.toDouble(args[0]), CypherScalarValues.toDouble(args[1])) },
        "cot" to { args, _ -> 1.0 / tan(CypherScalarValues.toDouble(args[0])) },
        "pi" to { _, _ -> Math.PI },
        "degrees" to { args, _ -> Math.toDegrees(CypherScalarValues.toDouble(args[0])) },
        "radians" to { args, _ -> Math.toRadians(CypherScalarValues.toDouble(args[0])) },
        "exists" to { args, _ -> args[0] != null },
    )
}
