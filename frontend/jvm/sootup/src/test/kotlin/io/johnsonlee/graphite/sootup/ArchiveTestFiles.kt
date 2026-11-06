package io.johnsonlee.graphite.sootup

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun appendArchiveFile(output: ZipOutputStream, file: Path, entryName: String) {
    output.putNextEntry(ZipEntry(entryName))
    Files.newInputStream(file).use { it.copyTo(output) }
    output.closeEntry()
}

internal fun appendClassDirectory(output: ZipOutputStream, classesDir: Path) {
    Files.walk(classesDir).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.forEach { classFile ->
            val entryName = classesDir.relativize(classFile).toString().replace(java.io.File.separatorChar, '/')
            appendArchiveFile(output, classFile, entryName)
        }
    }
}
