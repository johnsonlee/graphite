package io.johnsonlee.graphite.cli

import io.javalin.Javalin
import io.javalin.http.Context
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService

internal fun ExploreRoutes.registerAllC4Response(ctx: Context, acquire: (Context) -> List<GraphLease>) {
    val level = ctx.queryParam(API_PARAM_LEVEL) ?: "all"
    val format = resolveC4ResponseFormat(ctx.header("Accept"), ctx.queryParam(API_PARAM_FORMAT))
    if (level !in C4ArchitectureService.LEVELS) {
        ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to "Invalid 'level' parameter", "allowed" to C4ArchitectureService.LEVELS))
        return
    }
    if (format !in C4ArchitectureService.FORMATS) {
        ctx.status(HTTP_BAD_REQUEST)
            .json(mapOf(API_FIELD_ERROR to "Invalid 'format' parameter", "allowed" to C4ArchitectureService.FORMATS))
        return
    }
    withAllGraphs(ctx, acquire) { leases ->
        val models = leases.map { lease -> lease.id to c4.buildModel(lease.graph, level) }
        when (format) {
            "json" ->
                ctx.contentType("application/json; charset=utf-8")
                    .json(groupedEnvelope(models.size, models.map { (id, model) -> grouped(id, model) }))
            "mermaid" ->
                ctx.contentType("text/vnd.mermaid; charset=utf-8")
                    .result(models.joinToString("\n\n") { (id, model) -> "%% graphId: $id\n${c4.renderMermaid(model)}" })
            "plantuml" ->
                ctx.contentType("text/vnd.plantuml; charset=utf-8")
                    .result(models.joinToString("\n\n") { (id, model) -> "' graphId: $id\n${c4.renderPlantUml(model)}" })
            "dsl" ->
                ctx.contentType("text/vnd.structurizr.dsl; charset=utf-8")
                    .result(models.joinToString("\n\n") { (id, model) -> "// graphId: $id\n${c4.renderStructurizrDsl(model)}" })
        }
    }
}

internal fun ExploreRoutes.registerSpecRoutes(app: Javalin) {
    app.get("/openapi.json") { ctx -> ctx.json(openApiSpecBuilder.build()) }

    app.get("/swagger.json") { ctx -> ctx.json(openApiSpecBuilder.build()) }
}

internal fun ExploreRoutes.resolveC4ResponseFormat(accept: String?, queryFormat: String?): String {
    val accepted = accept.orEmpty().split(',').map { it.substringBefore(';').trim().lowercase() }.filter { it.isNotBlank() && it != "*/*" }
    return when {
        accepted.any { it == "text/vnd.plantuml" || it == "text/x-plantuml" || it == "application/vnd.plantuml" } -> "plantuml"
        accepted.any { it == "text/vnd.mermaid" || it == "text/x-mermaid" } -> "mermaid"
        accepted.any { it == "text/vnd.structurizr.dsl" || it == "text/x-structurizr" || it == "application/vnd.structurizr.dsl" } -> "dsl"
        accepted.any { it == "application/vnd.structurizr+json" || it == "application/json" } -> "json"
        !queryFormat.isNullOrBlank() -> queryFormat.lowercase()
        else -> "json"
    }
}
