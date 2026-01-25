package io.johnsonlee.graphite.core

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, ANY;

    companion object {
        fun fromAnnotation(annotationName: String): HttpMethod? {
            return when {
                annotationName.contains("GetMapping") -> GET
                annotationName.contains("PostMapping") -> POST
                annotationName.contains("PutMapping") -> PUT
                annotationName.contains("DeleteMapping") -> DELETE
                annotationName.contains("PatchMapping") -> PATCH
                annotationName.contains("RequestMapping") -> ANY
                else -> null
            }
        }
    }
}

data class EndpointInfo(
    val method: MethodDescriptor,
    val httpMethod: HttpMethod,
    val path: String,
    val produces: List<String> = emptyList(),
    val consumes: List<String> = emptyList()
) {
    val fullPath: String = path

    fun matchesPattern(pattern: String): Boolean {
        val normalizedPath = normalizePath(path)
        val normalizedPattern = normalizePath(pattern)
        return matchPath(normalizedPath.split("/"), normalizedPattern.split("/"))
    }

    private fun normalizePath(p: String): String {
        return p.trim('/').replace(Regex("\\{[^}]+}"), "*")
    }

    private fun matchPath(pathParts: List<String>, patternParts: List<String>): Boolean {
        var pi = 0
        var pati = 0

        while (pi < pathParts.size && pati < patternParts.size) {
            val patternPart = patternParts[pati]

            when {
                patternPart == "**" -> {
                    if (pati == patternParts.size - 1) {
                        return true
                    }
                    for (i in pi..pathParts.size) {
                        if (matchPath(pathParts.drop(i), patternParts.drop(pati + 1))) {
                            return true
                        }
                    }
                    return false
                }
                patternPart == "*" -> {
                    pi++
                    pati++
                }
                patternPart == pathParts[pi] -> {
                    pi++
                    pati++
                }
                else -> return false
            }
        }

        while (pati < patternParts.size && patternParts[pati] == "**") {
            pati++
        }

        return pi == pathParts.size && pati == patternParts.size
    }
}
