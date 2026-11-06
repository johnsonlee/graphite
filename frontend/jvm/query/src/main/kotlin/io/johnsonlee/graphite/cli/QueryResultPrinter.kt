package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import io.johnsonlee.graphite.cypher.CypherResult

private const val MIN_COLUMN_WIDTH = 4

/** Renders query results without coupling query execution to individual output formats. */
internal class QueryResultPrinter(private val gson: Gson) {
    fun print(result: CypherResult, format: String) {
        when (format.lowercase()) {
            "json" -> println(gson.toJson(mapOf("columns" to result.columns, "rows" to result.rows, "rowCount" to result.rows.size)))
            "csv" -> printCsv(result)
            else -> printText(result)
        }
    }

    private fun printCsv(result: CypherResult) {
        if (result.columns.isNotEmpty()) {
            println(result.columns.joinToString(","))
            result.rows.forEach { row -> println(result.columns.joinToString(",") { column -> csvValue(row[column]) }) }
        }
    }

    private fun csvValue(value: Any?): String = if (value is String) "\"${value.replace("\"", "\"\"")}\"" else value?.toString() ?: ""

    private fun printText(result: CypherResult) {
        if (result.columns.isEmpty()) {
            println("(no results)")
        } else {
            val widths =
                result.columns.map { column ->
                    val dataWidth = result.rows.maxOfOrNull { row -> (row[column]?.toString() ?: "null").length } ?: 0
                    maxOf(column.length, dataWidth, MIN_COLUMN_WIDTH)
                }
            println(result.columns.mapIndexed { index, column -> column.padEnd(widths[index]) }.joinToString(" | "))
            println(widths.joinToString("-+-") { "-".repeat(it) })
            result.rows.forEach { row ->
                println(
                    result.columns
                        .mapIndexed { index, column -> (row[column]?.toString() ?: "null").padEnd(widths[index]) }
                        .joinToString(" | "))
            }
            println("\n${result.rows.size} row(s)")
        }
    }
}
