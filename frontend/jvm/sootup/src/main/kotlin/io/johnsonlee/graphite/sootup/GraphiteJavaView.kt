package io.johnsonlee.graphite.sootup

import sootup.core.cache.provider.FullCacheProvider
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.java.core.GraphiteIdentifierFactory
import sootup.java.core.views.JavaView
import sootup.java.core.views.LoadingStrategy

/** Use the same class cache and loading strategy as JavaView's default constructor. */
internal class GraphiteJavaView(inputLocations: List<AnalysisInputLocation>) : JavaView(
    inputLocations, FullCacheProvider(), LoadingStrategy.onDemand(), GraphiteIdentifierFactory()
)

/** Other frontends retain their original view and identifier factory. */
internal fun createJavaView(inputLocations: List<AnalysisInputLocation>): JavaView =
    if (inputLocations.all { it is ParsedClassLocation }) GraphiteJavaView(inputLocations) else JavaView(inputLocations)
