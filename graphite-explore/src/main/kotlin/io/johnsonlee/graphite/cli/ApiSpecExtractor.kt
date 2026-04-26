package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern

internal class ApiSpecExtractor {
    internal fun extract(graph: Graph): List<Map<String, Any?>> {
        val mappingAnnotations = setOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
        )

        return graph.methods(MethodPattern())
            .flatMap { method ->
                val className = method.declaringClass.className
                val classAnnotations = graph.memberAnnotations(className, "<class>")
                val memberAnnotations = graph.memberAnnotations(className, method.name)
                val classBasePaths = extractPaths(classAnnotations["org.springframework.web.bind.annotation.RequestMapping"])
                val mappingEntries = memberAnnotations
                    .filterKeys { it in mappingAnnotations }
                    .entries

                mappingEntries.asSequence().flatMap { (annotationName, values) ->
                    val methodPaths = extractPaths(values)
                    val httpMethods = extractHttpMethods(annotationName, values)
                    combinePaths(classBasePaths, methodPaths).asSequence().flatMap { path ->
                        httpMethods.asSequence().map { httpMethod ->
                            mapOf(
                                "class" to className,
                                "member" to method.name,
                                "signature" to method.signature,
                                "httpMethod" to httpMethod,
                                "path" to path,
                                "annotation" to annotationName,
                                "returns" to method.returnType.className,
                                "parameters" to method.parameterTypes.map { it.className },
                                "annotations" to memberAnnotations.keys.sorted()
                            )
                        }
                    }
                }
            }
            .sortedWith(
                compareBy<Map<String, Any?>>(
                    { it["path"] as String },
                    { it["httpMethod"] as String },
                    { it["signature"] as String }
                )
            )
            .toList()
    }

    private fun extractPaths(annotationValues: Map<String, Any?>?): List<String> {
        val paths = extractStringValues(annotationValues?.get("path")) + extractStringValues(annotationValues?.get("value"))
        return if (paths.isEmpty()) listOf("/") else paths
    }

    private fun extractHttpMethods(annotationName: String, annotationValues: Map<String, Any?>): List<String> {
        return when (annotationName) {
            "org.springframework.web.bind.annotation.GetMapping" -> listOf("GET")
            "org.springframework.web.bind.annotation.PostMapping" -> listOf("POST")
            "org.springframework.web.bind.annotation.PutMapping" -> listOf("PUT")
            "org.springframework.web.bind.annotation.DeleteMapping" -> listOf("DELETE")
            "org.springframework.web.bind.annotation.PatchMapping" -> listOf("PATCH")
            else -> extractStringValues(annotationValues["method"]).ifEmpty { listOf("REQUEST") }
        }
    }

    private fun extractStringValues(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Iterable<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }

    private fun combinePaths(basePaths: List<String>, methodPaths: List<String>): List<String> {
        val bases = if (basePaths.isEmpty()) listOf("/") else basePaths
        val methods = if (methodPaths.isEmpty()) listOf("/") else methodPaths
        return bases.asSequence()
            .flatMap { base -> methods.asSequence().map { method -> normalizePath(base, method) } }
            .distinct()
            .toList()
    }

    private fun normalizePath(base: String, method: String): String {
        val basePart = base.trim().trim('/')
        val methodPart = method.trim().trim('/')
        return when {
            basePart.isEmpty() && methodPart.isEmpty() -> "/"
            basePart.isEmpty() -> "/$methodPart"
            methodPart.isEmpty() -> "/$basePart"
            else -> "/$basePart/$methodPart"
        }
    }

}
