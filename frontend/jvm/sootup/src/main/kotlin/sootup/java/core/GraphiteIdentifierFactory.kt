package sootup.java.core

import java.util.concurrent.ConcurrentHashMap
import sootup.core.signatures.PackageName
import sootup.java.core.types.JavaClassType

/**
 * Reuse the singleton's class types for repeated raw names within one view. Descriptor parsing,
 * arrays and normalization remain SootUp's responsibility. Strong entries live only with this
 * factory's view and grow with the number of distinct names requested by that view.
 */
internal class GraphiteIdentifierFactory(
    private val delegate: JavaIdentifierFactory = JavaIdentifierFactory.getInstance()
) : JavaIdentifierFactory() {
    private val classTypes = ConcurrentHashMap<String, JavaClassType>()

    override fun getClassType(fullyQualifiedClassName: String?): JavaClassType {
        // Preserve the delegate's handling of null rather than passing it to ConcurrentHashMap.
        if (fullyQualifiedClassName == null) return delegate.getClassType(fullyQualifiedClassName)
        return classTypes[fullyQualifiedClassName] ?: delegate.getClassType(fullyQualifiedClassName).let { type ->
            classTypes.putIfAbsent(fullyQualifiedClassName, type) ?: type
        }
    }

    override fun getClassType(className: String?, packageName: String?): JavaClassType =
        delegate.getClassType(className, packageName)

    override fun getPackageName(packageName: String): PackageName = delegate.getPackageName(packageName)
}
