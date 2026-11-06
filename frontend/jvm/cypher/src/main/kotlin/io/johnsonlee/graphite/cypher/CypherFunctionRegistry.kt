package io.johnsonlee.graphite.cypher

import kotlin.math.E
import kotlin.math.PI
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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal typealias CypherScalarFunction = (List<Any?>, (() -> Unit)?) -> Any?

internal val scalarFunctions: Map<String, CypherScalarFunction> =
    mapOf<String, CypherScalarFunction>(
        "id" to { args, _ -> entityId(args[0]) },
        "elementid" to { args, _ -> elementId(args[0]) },
        "qualifiedid" to { args, _ -> elementId(args[0]) },
        "graphid" to { args, _ -> graphId(args[0]) },
        "coalesce" to { args, _ -> coalesce(args) },
        "timestamp" to { _, _ -> System.currentTimeMillis() },
        "tointeger" to { args, _ -> integerValue(args[0]) },
        "toint" to { args, _ -> integerValue(args[0]) },
        "tofloat" to { args, _ -> floatValue(args[0]) },
        "toboolean" to { args, _ -> booleanValue(args[0]) },
        "tostring" to { args, _ -> args[0]?.toString() },
        "properties" to { args, _ -> entityProperties(args[0]) },
        "keys" to { args, _ -> entityKeys(args[0]) },
        "labels" to { args, _ -> entityLabels(args[0]) },
        "type" to { args, _ -> relationshipType(args[0]) },
        "tolower" to { args, _ -> (args[0] as? String)?.lowercase() },
        "tolowercase" to { args, _ -> (args[0] as? String)?.lowercase() },
        "toupper" to { args, _ -> (args[0] as? String)?.uppercase() },
        "touppercase" to { args, _ -> (args[0] as? String)?.uppercase() },
        "trim" to { args, _ -> (args[0] as? String)?.trim() },
        "ltrim" to { args, _ -> (args[0] as? String)?.trimStart() },
        "rtrim" to { args, _ -> (args[0] as? String)?.trimEnd() },
        "replace" to { args, _ -> (args[0] as? String)?.replace(args[1] as String, args[2] as String) },
        "substring" to { args, _ -> substringValue(args) },
        "split" to { args, _ -> (args[0] as? String)?.split(args[1] as String) },
        "size" to { args, _ -> collectionSize(args[0]) },
        "length" to { args, _ -> collectionSize(args[0]) },
        "left" to { args, _ -> (args[0] as? String)?.take((args[1] as Number).toInt()) },
        "right" to { args, _ -> (args[0] as? String)?.takeLast((args[1] as Number).toInt()) },
        "reverse" to { args, checkCancelled -> reverseValue(args[0], checkCancelled) },
        "head" to { args, _ -> (args[0] as? List<*>)?.firstOrNull() },
        "tail" to { args, checkCancelled -> tailValues(args[0], checkCancelled) },
        "last" to { args, _ -> (args[0] as? List<*>)?.lastOrNull() },
        "range" to { args, checkCancelled -> rangeValues(args, checkCancelled) },
        "nodes" to { args, checkCancelled -> pathNodes(args[0], checkCancelled) },
        "relationships" to { args, checkCancelled -> pathRelationships(args[0], checkCancelled) },
        "abs" to { args, _ -> absoluteValue(args[0]) },
        "ceil" to { args, _ -> ceil(numericValue(args[0])) },
        "floor" to { args, _ -> floor(numericValue(args[0])) },
        "round" to { args, _ -> roundHalfUp(numericValue(args[0])) },
        "sign" to { args, _ -> signValue(args[0]) },
        "rand" to { _, _ -> Math.random() },
        "sqrt" to { args, _ -> sqrt(numericValue(args[0])) },
        "exp" to { args, _ -> exp(numericValue(args[0])) },
        "log" to { args, _ -> ln(numericValue(args[0])) },
        "log10" to { args, _ -> log10(numericValue(args[0])) },
        "e" to { _, _ -> Math.E },
        "sin" to { args, _ -> sin(numericValue(args[0])) },
        "cos" to { args, _ -> cos(numericValue(args[0])) },
        "tan" to { args, _ -> tan(numericValue(args[0])) },
        "asin" to { args, _ -> asin(numericValue(args[0])) },
        "acos" to { args, _ -> acos(numericValue(args[0])) },
        "atan" to { args, _ -> atan(numericValue(args[0])) },
        "atan2" to { args, _ -> atan2(numericValue(args[0]), numericValue(args[1])) },
        "cot" to { args, _ -> 1.0 / tan(numericValue(args[0])) },
        "pi" to { _, _ -> Math.PI },
        "degrees" to { args, _ -> Math.toDegrees(numericValue(args[0])) },
        "radians" to { args, _ -> Math.toRadians(numericValue(args[0])) },
        "exists" to { args, _ -> args[0] != null },
    )
