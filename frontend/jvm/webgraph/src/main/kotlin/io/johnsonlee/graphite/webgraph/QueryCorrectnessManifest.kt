package io.johnsonlee.graphite.webgraph

import java.nio.file.Files
import java.nio.file.Path

/** One complete, latency-independent query result signature. */
data class QueryCorrectnessRecord(
    val id: String,
    val family: String,
    val shape: String,
    val selectivity: String,
    val operator: String,
    val boundary: String,
    val projection: String,
    val targetGraphId: String,
    val workloadIdentity: String,
    val limit: Long,
    val outcome: String,
    val rowCount: Long,
    val responseBytes: Long,
    val digest: String
) {
    fun encode(): String = listOf(
        id,
        family,
        shape,
        selectivity,
        operator,
        boundary,
        projection,
        targetGraphId,
        workloadIdentity,
        limit,
        outcome,
        rowCount,
        responseBytes,
        digest
    ).joinToString("|")

    companion object {
        fun decode(line: String, source: String, lineNumber: Int): QueryCorrectnessRecord {
            val columns = line.split('|')
            require(columns.size == COLUMN_COUNT) {
                "$source:$lineNumber must contain exactly $COLUMN_COUNT pipe-separated columns"
            }
            require(columns.take(TEXT_COLUMN_COUNT).all(String::isNotBlank)) {
                "$source:$lineNumber contains a blank identity column"
            }
            val outcome = columns[OUTCOME_COLUMN]
            require(outcome in VALID_OUTCOMES) {
                "$source:$lineNumber has unsupported outcome '$outcome'"
            }
            return QueryCorrectnessRecord(
                id = columns[0],
                family = columns[1],
                shape = columns[2],
                selectivity = columns[3],
                operator = columns[4],
                boundary = columns[5],
                projection = columns[6],
                targetGraphId = columns[TARGET_GRAPH_ID_COLUMN],
                workloadIdentity = columns[WORKLOAD_IDENTITY_COLUMN],
                limit = parseLong(columns[LIMIT_COLUMN], source, lineNumber, "limit"),
                outcome = outcome,
                rowCount = parseLong(columns[ROW_COUNT_COLUMN], source, lineNumber, "row count"),
                responseBytes = parseLong(columns[RESPONSE_BYTES_COLUMN], source, lineNumber, "response size"),
                digest = columns[DIGEST_COLUMN]
            ).also { record ->
                require(record.limit >= 0L && record.rowCount >= 0L && record.responseBytes >= 0L) {
                    "$source:$lineNumber contains a negative numeric value"
                }
                if (record.outcome == SUCCESS_OUTCOME) {
                    require(SHA_256.matches(record.digest)) {
                        "$source:$lineNumber success digest must be a lowercase SHA-256 value"
                    }
                }
            }
        }
    }
}

/**
 * Fail-closed correctness gate for performance benchmarks.
 *
 * An oracle is valid only when every requested query completed successfully. A measured run must
 * contain the same query identities and byte-for-byte-equivalent result signatures.
 */
object QueryCorrectnessManifest {
    fun read(path: Path): List<QueryCorrectnessRecord> {
        require(Files.isRegularFile(path)) { "Correctness manifest not found at $path" }
        return Files.readAllLines(path).mapIndexedNotNull { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) null
            else QueryCorrectnessRecord.decode(line, path.toString(), index + 1)
        }.also { records -> require(records.isNotEmpty()) { "Correctness manifest $path is empty" } }
    }

    fun write(path: Path, records: List<QueryCorrectnessRecord>) {
        requireUnique(records, path.toString())
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(path, records.joinToString("\n", postfix = "\n", transform = QueryCorrectnessRecord::encode))
    }

    fun selectCompleteOracle(
        records: List<QueryCorrectnessRecord>,
        expectedIds: Set<String>,
        source: String
    ): List<QueryCorrectnessRecord> {
        require(expectedIds.isNotEmpty()) { "Correctness workload is empty" }
        requireUnique(records, source)
        val selected = records.filter { it.id in expectedIds }
        val selectedIds = selected.mapTo(mutableSetOf(), QueryCorrectnessRecord::id)
        val missing = expectedIds - selectedIds
        require(missing.isEmpty()) {
            "$source is missing ${missing.size} correctness records: ${missing.sorted().joinToString()}"
        }
        requireSuccessful(selected, source)
        return selected
    }

    fun requireRecordable(records: List<QueryCorrectnessRecord>, expectedIds: Set<String>) {
        requireUnique(records, "recorded correctness manifest")
        val actualIds = records.mapTo(mutableSetOf(), QueryCorrectnessRecord::id)
        check(actualIds == expectedIds) {
            coverageDifference(expectedIds, actualIds, "Recorded correctness manifest")
        }
        check(records.all { it.outcome == SUCCESS_OUTCOME }) {
            val unsuccessful = records.filter { it.outcome != SUCCESS_OUTCOME }
                .joinToString { "${it.id}=${it.outcome}" }
            "A correctness oracle requires every query to succeed; incomplete results: $unsuccessful"
        }
    }

    fun verify(oracle: List<QueryCorrectnessRecord>, actual: List<QueryCorrectnessRecord>) {
        requireUnique(oracle, "correctness oracle")
        requireUnique(actual, "measured correctness manifest")
        requireSuccessful(oracle, "correctness oracle")

        val expectedById = oracle.associateBy(QueryCorrectnessRecord::id)
        val actualById = actual.associateBy(QueryCorrectnessRecord::id)
        check(expectedById.keys == actualById.keys) {
            coverageDifference(expectedById.keys, actualById.keys, "Measured correctness manifest")
        }
        val mismatches = expectedById.keys.sorted().mapNotNull { id ->
            val expected = expectedById.getValue(id)
            val observed = actualById.getValue(id)
            if (expected == observed) null else "$id expected=${expected.encode()} actual=${observed.encode()}"
        }
        check(mismatches.isEmpty()) {
            "Correctness hard gate failed for ${mismatches.size} queries:\n${mismatches.joinToString("\n")}"
        }
    }

    private fun requireUnique(records: List<QueryCorrectnessRecord>, source: String) {
        val duplicates = records.groupingBy(QueryCorrectnessRecord::id).eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "$source contains duplicate query ids: ${duplicates.sorted().joinToString()}"
        }
    }

    private fun requireSuccessful(records: List<QueryCorrectnessRecord>, source: String) {
        val incomplete = records.filter { it.outcome != SUCCESS_OUTCOME }
        require(incomplete.isEmpty()) {
            "$source is not a correctness oracle because ${incomplete.size} queries did not succeed: " +
                incomplete.joinToString { "${it.id}=${it.outcome}" }
        }
    }

    private fun coverageDifference(expected: Set<String>, actual: Set<String>, prefix: String): String {
        val missing = (expected - actual).sorted()
        val unexpected = (actual - expected).sorted()
        return "$prefix coverage differs; missing=${missing.joinToString()} unexpected=${unexpected.joinToString()}"
    }
}

private const val COLUMN_COUNT = 14
private const val TEXT_COLUMN_COUNT = 7
private const val TARGET_GRAPH_ID_COLUMN = 7
private const val WORKLOAD_IDENTITY_COLUMN = 8
private const val LIMIT_COLUMN = 9
private const val OUTCOME_COLUMN = 10
private const val ROW_COUNT_COLUMN = 11
private const val RESPONSE_BYTES_COLUMN = 12
private const val DIGEST_COLUMN = 13
private const val SUCCESS_OUTCOME = "success"
private val VALID_OUTCOMES = setOf(SUCCESS_OUTCOME, "timeout", "failed")
private val SHA_256 = Regex("[0-9a-f]{64}")

private fun parseLong(value: String, source: String, lineNumber: Int, field: String): Long =
    value.toLongOrNull() ?: throw IllegalArgumentException("$source:$lineNumber has an invalid $field")
