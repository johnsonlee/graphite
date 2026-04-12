package io.johnsonlee.graphite.sootup

import sootup.core.model.SourceType
import sootup.core.types.ClassType
import sootup.core.views.View
import sootup.java.bytecode.frontend.conversion.AsmJavaClassProvider
import sootup.java.bytecode.frontend.inputlocation.ArchiveBasedAnalysisInputLocation
import sootup.java.core.JavaSootClassSource
import sootup.java.core.types.JavaClassType
import java.nio.file.Path
import java.util.Optional

/**
 * Drop-in replacement for [ArchiveBasedAnalysisInputLocation] that skips
 * the `Files.exists()` check on ZipFileSystem paths.
 *
 * The parent class calls `Files.exists(pathToClass)` for every class lookup.
 * On ZipFileSystem this triggers `ZipFileSystemProvider.checkAccess()` which
 * throws and catches `NoSuchFileException` internally — the exception
 * stack trace initialization is pure CPU waste (~3% of total build time).
 *
 * This class directly attempts to create the class source without any
 * existence check. If the class doesn't exist, `createClassSource` returns
 * empty.
 */
class FastArchiveAnalysisInputLocation(
    path: Path,
    srcType: SourceType
) : ArchiveBasedAnalysisInputLocation(path, srcType) {

    override fun getClassSource(type: ClassType, view: View): Optional<JavaSootClassSource> {
        return try {
            val fs = fileSystemCache.get(path)
            val archiveRoot = fs.getPath("/")
            val classType = type as JavaClassType
            val provider = AsmJavaClassProvider(view)
            val pathToClass = archiveRoot.resolve(
                archiveRoot.fileSystem.getPath(
                    classType.fullyQualifiedName.replace('.', '/') + ".class"
                )
            )
            provider.createClassSource(this, pathToClass, classType)
                .map { it as JavaSootClassSource }
        } catch (_: Exception) {
            Optional.empty()
        }
    }
}
