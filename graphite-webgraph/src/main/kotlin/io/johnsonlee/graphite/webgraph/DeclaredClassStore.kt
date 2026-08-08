package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal object DeclaredClassStore {
    const val FILE_NAME = "graph.declaredclasses"
    private const val MAGIC_DECLARED_CLASSES = 0x47524400 // "GRD"

    fun save(methods: Collection<MethodDescriptor>, dir: Path, strings: StringTable) {
        val classes = methods.asSequence()
            .map { it.declaringClass.className }
            .distinct()
            .sorted()
            .toList()
        DataOutputStream(BufferedOutputStream(dir.resolve(FILE_NAME).toFile().outputStream())).use { dos ->
            NodeSerializer.writeHeader(dos, MAGIC_DECLARED_CLASSES)
            dos.writeInt(classes.size)
            classes.forEach { className ->
                val index = strings.indexOf(className)
                require(index >= 0) { "String not found in persisted table: $className" }
                dos.writeInt(index)
            }
        }
    }

    fun load(dir: Path, metadataFile: Path, strings: StringTable): Set<String> {
        val file = dir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) {
            return DataInputStream(BufferedInputStream(metadataFile.toFile().inputStream())).use { dis ->
                NodeSerializer.readMetadataDeclaredClasses(dis, strings)
            }
        }
        return DataInputStream(BufferedInputStream(file.toFile().inputStream())).use { dis ->
            NodeSerializer.readHeader(dis, MAGIC_DECLARED_CLASSES)
            val count = dis.readInt()
            require(count >= 0) { "Invalid declared class count: $count" }
            LinkedHashSet<String>(count).apply {
                repeat(count) { add(strings.get(dis.readInt())) }
            }
        }
    }
}
