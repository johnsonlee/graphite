package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.ClassDependency
import io.johnsonlee.graphite.graph.ClassOverview
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal class PersistedClassOverviewProvider(
    private val dir: Path,
    private val strings: StringTable
) {
    @Volatile
    private var cached: CachedClassOverview? = null

    fun load(limit: Int): ClassOverview? {
        val boundedLimit = ClassOverviewStore.boundLimit(limit)
        cached?.let { current ->
            if (current.limit >= boundedLimit) {
                return current.overview.truncate(boundedLimit)
            }
        }

        return synchronized(this) {
            cached?.let { current ->
                if (current.limit >= boundedLimit) {
                    return@synchronized current.overview.truncate(boundedLimit)
                }
            }

            ClassOverviewStore.load(dir, strings, boundedLimit)?.also { loaded ->
                cached = CachedClassOverview(boundedLimit, loaded)
            }
        }
    }
}

internal class ClassOverviewBuilder {
    private val classCounts = HashMap<String, Int>()
    private var callSiteCount = 0

    fun add(callSite: CallSiteNode) {
        callSiteCount++
        val callerClass = callSite.caller.declaringClass.className
        val calleeClass = callSite.callee.declaringClass.className
        increment(classCounts, callerClass)
        increment(classCounts, calleeClass)
    }

    fun topClassCounts(limit: Int): LinkedHashMap<String, Int> {
        val boundedLimit = limit.coerceAtLeast(0)
        val topCounts = LinkedHashMap<String, Int>(boundedLimit)
        classCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(boundedLimit)
            .forEach { (className, count) -> topCounts[className] = count }
        return topCounts
    }

    fun callSiteCount(): Int = callSiteCount

    private fun <K> increment(map: MutableMap<K, Int>, key: K) {
        map[key] = (map[key] ?: 0) + 1
    }
}

internal class ClassOverviewEdgeBuilder(
    private val topClasses: Set<String>
) {
    private val classEdges = HashMap<ClassDependency, Int>()

    fun add(callSite: CallSiteNode) {
        val callerClass = callSite.caller.declaringClass.className
        val calleeClass = callSite.callee.declaringClass.className
        if (callerClass == calleeClass || callerClass !in topClasses || calleeClass !in topClasses) {
            return
        }
        val dependency = ClassDependency(callerClass, calleeClass)
        classEdges[dependency] = (classEdges[dependency] ?: 0) + 1
    }

    fun build(): Map<ClassDependency, Int> = classEdges.toMap()
}

internal object ClassOverviewStore {
    const val FILE_NAME = "graph.classoverview"
    const val MAX_PERSISTED_CLASSES = 1_000

    private const val MAGIC_CLASS_OVERVIEW = 0x47524F00 // "GRO"

    fun boundLimit(limit: Int): Int = limit.coerceIn(0, MAX_PERSISTED_CLASSES)

    fun collectStrings(overview: ClassOverview, dest: MutableSet<String>) {
        dest.addAll(overview.classCounts.keys)
        for (edge in overview.classEdges.keys) {
            dest.add(edge.callerClass)
            dest.add(edge.calleeClass)
        }
    }

    fun save(overview: ClassOverview, dir: Path, strings: StringTable) {
        DataOutputStream(BufferedOutputStream(dir.resolve(FILE_NAME).toFile().outputStream())).use { dos ->
            NodeSerializer.writeHeader(dos, MAGIC_CLASS_OVERVIEW)
            dos.writeInt(overview.callSiteCount)
            dos.writeInt(overview.classCounts.size)
            val sortedCounts = overview.classCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            for ((className, count) in sortedCounts) {
                dos.writeInt(strings.requireIndexOf(className))
                dos.writeInt(count)
            }
            dos.writeInt(overview.classEdges.size)
            val sortedEdges = overview.classEdges.entries.sortedWith(compareBy({ it.key.callerClass }, { it.key.calleeClass }))
            for ((edge, count) in sortedEdges) {
                dos.writeInt(strings.requireIndexOf(edge.callerClass))
                dos.writeInt(strings.requireIndexOf(edge.calleeClass))
                dos.writeInt(count)
            }
        }
    }

    fun load(dir: Path, strings: StringTable, limit: Int): ClassOverview? {
        val file = dir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return null
        return DataInputStream(BufferedInputStream(file.toFile().inputStream())).use { dis ->
            NodeSerializer.readHeader(dis, MAGIC_CLASS_OVERVIEW)
            val callSiteCount = dis.readInt()
            val boundedLimit = boundLimit(limit)
            val classCounts = LinkedHashMap<String, Int>()
            val topClassIds = HashSet<Int>(boundedLimit)
            repeat(dis.readInt()) { index ->
                val classId = dis.readInt()
                val count = dis.readInt()
                if (index < boundedLimit) {
                    topClassIds.add(classId)
                    classCounts[strings.get(classId)] = count
                }
            }
            val classEdges = LinkedHashMap<ClassDependency, Int>()
            repeat(dis.readInt()) {
                val callerClassId = dis.readInt()
                val calleeClassId = dis.readInt()
                val count = dis.readInt()
                if (callerClassId in topClassIds && calleeClassId in topClassIds) {
                    val callerClass = strings.get(callerClassId)
                    val calleeClass = strings.get(calleeClassId)
                    classEdges[ClassDependency(callerClass, calleeClass)] = count
                }
            }
            ClassOverview(
                classCounts = classCounts,
                classEdges = classEdges,
                callSiteCount = callSiteCount
            )
        }
    }
}

private data class CachedClassOverview(
    val limit: Int,
    val overview: ClassOverview
)

private fun ClassOverview.truncate(limit: Int): ClassOverview {
    val boundedLimit = limit.coerceAtLeast(0)
    if (classCounts.size <= boundedLimit) {
        return this
    }

    val topClassCounts = LinkedHashMap<String, Int>(boundedLimit)
    classCounts.entries.asSequence().take(boundedLimit).forEach { (className, count) ->
        topClassCounts[className] = count
    }
    val topClasses = topClassCounts.keys
    val topClassEdges = LinkedHashMap<ClassDependency, Int>()
    for ((dependency, count) in classEdges) {
        if (dependency.callerClass in topClasses && dependency.calleeClass in topClasses) {
            topClassEdges[dependency] = count
        }
    }

    return ClassOverview(
        classCounts = topClassCounts,
        classEdges = topClassEdges,
        callSiteCount = callSiteCount
    )
}

private fun StringTable.requireIndexOf(value: String): Int {
    val index = indexOf(value)
    require(index >= 0) { "Class overview string is missing from the string table: $value" }
    return index
}
