package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.bytecode.ClassDeclarations
import io.johnsonlee.graphite.bytecode.DeclaredTypesReader
import io.johnsonlee.graphite.bytecode.InheritedFieldTypes

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.Graph
import sootup.core.model.SootClass
import sootup.java.core.JavaSootClass
import sootup.java.bytecode.frontend.conversion.parsedDeclarationNode
import io.johnsonlee.graphite.sootup.SootUpAdapter.IndexedClass

/** Coordinates declaration, body, deferred-call and final graph-construction stages. */
internal class GraphConstruction(private val context: SootUpAdapter) {
    private val trackCrossMethodFunctionalDispatch = context.trackCrossMethodFunctionalDispatch
    private val interprocedural = context.interprocedural
    private val fieldNodes = context.fieldNodes
    private val classOriginsByName = context.classOriginsByName
    private val classOriginSourceCounts = context.classOriginSourceCounts
    private val classEvidence = context.classEvidence
    private val reachableExpansion = context.reachableExpansion
    private var reachableRead
        get() = context.reachableRead
        set(value) { context.reachableRead = value }
    private val seededClasses = context.seededClasses
    private val applicationClasses = context.applicationClasses
    private var activeClassResolutionFailed
        get() = context.activeClassResolutionFailed
        set(value) { context.activeClassResolutionFailed = value }
    private val artifactDependenciesByArtifact = context.artifactDependenciesByArtifact
    private var classesByNameCache
        get() = context.classesByNameCache
        set(value) { context.classesByNameCache = value }
    private val syntheticIdentities = context.syntheticIdentities
    private val view = context.view
    private val config = context.config
    private val extensions = context.extensions
    private val resourceAccessor = context.resourceAccessor
    private val declarationWarning = context.declarationWarning
    private val singleArtifactSource = context.singleArtifactSource
    private val graphBuilder = context.graphBuilder
    private val calls get() = context.calls
    private val completeness get() = context.completeness
    private val declarations get() = context.declarations
    private val descriptors get() = context.descriptors
    private val dispatchHierarchy get() = context.dispatchHierarchy
    private val dispatchResolution get() = context.dispatchResolution
    private val metadata get() = context.metadata
    private val methodSources get() = context.methodSources
    private val reachability get() = context.reachability
    private val resources get() = context.resources

    fun persistedClassOrigins(): Map<String, String> {
        if (classOriginSourceCounts.size <= 1) {
            return emptyMap()
        }
        return classOriginsByName.toMap()
    }

    /** Declaration snapshots are needed only while building the type table. */
    fun buildDeclaredTypes(classes: List<SootClass>): DeclaredTypeTable {
        val classNames = classes.mapTo(HashSet(classes.size)) { it.type.fullyQualifiedName }
        val dexDeclarations = DexClassDeclarations.read(resourceAccessor, classNames, declarationWarning)
        val declarations = classes.mapNotNull { sootClass ->
            val name = sootClass.type.fullyQualifiedName
            sootClass.classSource.parsedDeclarationNode()?.let(ClassDeclarations::from)
                ?: dexDeclarations[name]
                ?: descriptors.loadClassNodeFromResource(name)?.let(ClassDeclarations::from)
        }.associateBy { it.name }
        val table = DeclaredTypesReader(declarations).build()
        val interfaces = classes.filter { it.isInterface }.mapTo(HashSet()) { it.type.fullyQualifiedName }
        return InheritedFieldTypes(declarations, table, interfaces).bind(fieldNodes.values)
    }

    /**
     * Build the complete graph from the SootUp view
     */
    fun buildGraph(): Graph {
        val classes = view.classes.toList()
        classesByNameCache = classes.associateBy { it.type.fullyQualifiedName }
        indexInputs(classes)
        emitDeclarations(classes)
        val extensionContext = emitBodies(classes)
        resolveDeferredCalls(extensionContext)
        return finishGraph(classes)
    }

    private fun indexInputs(classes: List<SootClass>) {
        val indexedClasses: List<IndexedClass>
        val loadedClassSources: Set<String>
        if (singleArtifactSource == null) {
            indexedClasses = resources.indexSootClassOrigins(classes)
            loadedClassSources = indexedClasses.mapTo(HashSet()) { it.source }
        } else {
            indexedClasses = emptyList()
            loadedClassSources = setOf(singleArtifactSource)
        }
        val unloadedClassEntries = resources.indexResourceValues(loadedClassSources)
        if (singleArtifactSource == null && classOriginSourceCounts.size > 1) {
            indexedClasses.forEach { resources.indexArtifactDependency(it.sootClass, it.source) }
            unloadedClassEntries.forEach(resources::indexArtifactDependency)
        } else {
            declarations.log("Skipping artifact dependency extraction for single-artifact input")
        }
        resources.indexClassBundles(classes)
    }

    private fun emitDeclarations(classes: List<SootClass>) {
        declarations.log("Starting buildGraph pass 1")
        var pass1Count = 0
        // Pass 1: All classes — type hierarchy + enum values
        // Enum values must be fully collected before processing methods
        // (a method in class A may reference an enum from class B)
        classes.forEach { sootClass ->
            pass1Count++
            if (pass1Count % PASS1_PROGRESS_INTERVAL == 0) {
                declarations.log { "Pass 1 processed $pass1Count classes; current=${sootClass.type}" }
            }
            declarations.processTypeHierarchyForClass(sootClass)
            if (sootClass.isEnum) {
                declarations.extractEnumValues(sootClass)
            }
        }

    }

    private fun emitBodies(classes: List<SootClass>): GraphiteContext {
        declarations.log("Starting buildGraph pass 2")
        var pass2Count = 0
        // Pass 2: Filtered classes — methods + fields + extensions
        // Methods before fields (fields check fieldNodes map for duplicates)
        val extensionContext = GraphiteContext(
            methodDescriptorFactory = descriptors::toMethodDescriptor,
            logger = declarations::log,
            resources = resourceAccessor
        )
        classes.forEach { sootClass ->
            val className = sootClass.type.fullyQualifiedName
            val origin = evidenceOrigin(sootClass)
            val application = metadata.shouldIncludeClass(sootClass)
            if (!application && !(reachability.isLibraryOrigin(origin) && config.dependencyBodies.covers(className, origin))) {
                classEvidence[className] = ClassEvidence(origin, AnalysisStatus.EXCLUDED_BY_SCOPE)
                return@forEach
            }
            pass2Count++
            if (pass2Count % PASS2_PROGRESS_INTERVAL == 0) {
                declarations.log { "Pass 2 processed $pass2Count classes; current=${sootClass.type}" }
            }
            processDependencyOrApplicationClass(sootClass, extensionContext, application)
        }
        reachableExpansion?.let { reachability.expandReachableClasses(it, extensionContext) }

        return extensionContext
    }

    private fun resolveDeferredCalls(extensionContext: GraphiteContext) {
        // Pass 2B: Resolve cross-method functional interface dispatch
        if (trackCrossMethodFunctionalDispatch) {
            dispatchResolution.resolveFunctionalDispatch()
        }
        // A library body reached only through a resolved function value is read now; what it
        // passes on as a function value is not resolved further, and reads as a gap.
        val classesBeforeDispatchExpansion = reachableRead
        reachableExpansion?.let { reachability.expandReachableClasses(it, extensionContext) }
        // Finalize only after the last class expansion: callback-only dependencies can
        // contribute synthetic methods and classes after functional dispatch resolves.
        syntheticIdentities.resolve().forEach { (member, fingerprint) ->
            graphBuilder.addSyntheticIdentity(member, fingerprint)
        }
        if (syntheticIdentities.skipped > 0) {
            declarations.log { "Skipped ${syntheticIdentities.skipped} synthetic member(s) whose identity could not be rendered" }
        }

        val lateClassesRead = reachableRead > classesBeforeDispatchExpansion
        dispatchResolution.finalizeDispatchEvidence(lateClassesRead)
        completeness.collectDispatchGaps(lateClassesRead)

        interprocedural?.let { calls ->
            val edges = calls.link(dispatchHierarchy::overridesOf) { graphBuilder.addEdge(it) }
            declarations.log { "Linked $edges interprocedural dataflow edge(s)" }
        }
        dispatchResolution.releaseFunctionalDispatchState()

    }

    private fun finishGraph(classes: List<SootClass>): Graph {
        // Build call graph if configured
        if (config.buildCallGraph) {
            methodSources.processCallGraph()
        }

        persistedClassOrigins().forEach { (className, source) ->
            graphBuilder.addClassOrigin(className, source)
        }
        artifactDependenciesByArtifact.forEach { (fromArtifact, dependencies) ->
            dependencies.forEach { (toArtifact, weight) ->
                graphBuilder.addArtifactDependency(fromArtifact, toArtifact, weight)
            }
        }

        declarations.log("Starting graphBuilder.build()")
        graphBuilder.setDeclaredTypes(buildDeclaredTypes(classes))
        graphBuilder.setResources(resourceAccessor)
        graphBuilder.setAnalysisCompleteness(completeness.analysisCompleteness())
        return graphBuilder.build().also {
            declarations.log("Finished graphBuilder.build()")
        }
    }

    /** Read a pass-2 class and record what the build did with it. */
    fun processDependencyOrApplicationClass(sootClass: SootClass, extensionContext: GraphiteContext, application: Boolean) {
        val className = sootClass.type.fullyQualifiedName
        activeClassResolutionFailed = false
        if (application) applicationClasses += className
        if (reachableExpansion != null && (!application || reachableExpansion.seeds(className))) {
            seededClasses += className
            reachability.initializationPrerequisites(className).forEach(reachability::noteFollowedClass)
        }
        declarations.processClassWithMethodCache(sootClass, extensionContext, application)
        val status = if (activeClassResolutionFailed) AnalysisStatus.RESOLUTION_FAILED else AnalysisStatus.ANALYZED
        classEvidence[className] = ClassEvidence(evidenceOrigin(sootClass), status)
    }

    /**
     * The source the evidence names for [sootClass]: the indexed origin for a multi-artifact
     * input; for a single-artifact input, the input location the class was read from when the
     * view holds more than one (an APK beside its platform jar: the platform's classes are the
     * platform's, not the APK's, whatever the graph's own origin index says), else the artifact.
     */
    fun evidenceOrigin(sootClass: SootClass): String? =
        classOriginsByName[sootClass.type.fullyQualifiedName]
            ?: (sootClass as? JavaSootClass)?.let(resources::sourceForClass)
            ?: singleArtifactSource
}
