package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.JavaArchiveLayout

internal object SubjectDetector {
    fun infer(
        graph: Graph,
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        endpointCount: Int,
        systemBoundary: String
    ): SubjectDescriptor {
        val manifest = readManifestMetadata(graph)
        val hasMainClass = !manifest.mainClass.isNullOrBlank()
        val hasBootLayout = graph.resources.list(JavaArchiveLayout.BOOT_INF_GLOB).any() ||
            graph.resources.list(JavaArchiveLayout.WEB_INF_GLOB).any()
        val hasMainMethod = methods.any(::isMainMethod)
        val startClass = manifest.startClass?.takeIf { it.isNotBlank() }
        val startClassOrigin = startClass?.let(graph::classOrigin)
        val hasBootLauncherMain = manifest.mainClass?.let { mainClass ->
            mainClass in JavaArchiveLayout.SPRING_BOOT_LAUNCHERS
        } == true
        val bootAppOrigin = startClassOrigin?.let { origin ->
            origin == JavaArchiveLayout.BOOT_INF_CLASSES ||
                origin == JavaArchiveLayout.WEB_INF_CLASSES ||
                origin.startsWith(JavaArchiveLayout.BOOT_INF_LIB) ||
                origin.startsWith(JavaArchiveLayout.WEB_INF_LIB)
        } == true
        val mainReachability = analyzeMainReachability(methods, callSites, systemBoundary, startClass)
        val bootReachabilityLooksComplete =
            mainReachability.mainMethodCount > 0 &&
                mainReachability.reachableInternalMethodCount > 1 &&
                mainReachability.reachableInternalClassCount > 1
        val role = when {
            hasBootLayout && hasBootLauncherMain && bootAppOrigin && bootReachabilityLooksComplete -> "application"
            hasMainClass && !startClass.isNullOrBlank() && mainReachability.mainMethodCount > 0 -> "application"
            hasMainMethod &&
                endpointCount > 0 &&
                mainReachability.reachableInternalMethodCount > 1 -> "application"
            hasMainMethod &&
                mainReachability.reachableInternalMethodCount >= mainReachability.reachableExternalTargetCount &&
                mainReachability.reachableInternalClassCount > 1 -> "application"
            else -> "library"
        }
        val displayName = inferName(systemBoundary, startClassOrigin, startClass)
        return when (role) {
            "application" -> SubjectDescriptor(
                id = "system:application",
                name = displayName.ifBlank { "Application" },
                role = "application",
                description = "Executable software system inferred from the Graphite code graph",
                responsibility = "Owns the internal runtime containers and orchestrates the primary execution flows",
                actorId = if (endpointCount > 0) "person:http-clients" else "person:operators",
                actorName = if (endpointCount > 0) "HTTP Clients" else "Operators",
                actorDescription = if (endpointCount > 0) {
                    "External clients invoking detected HTTP endpoints"
                } else {
                    "Operators or launchers starting the executable artifact"
                },
                actorResponsibility = if (endpointCount > 0) {
                    "Initiates synchronous request flows into the application boundary"
                } else {
                    "Starts and operates the executable artifact"
                }
            )
            else -> SubjectDescriptor(
                id = "system:library",
                name = "$displayName Library",
                role = "library",
                description = "A reusable library artifact inferred from the analyzed code graph",
                responsibility = "Provides reusable capabilities that are linked and invoked by host applications",
                actorId = "person:host-applications",
                actorName = "Host Applications",
                actorDescription = "Applications or services that embed and invoke the library",
                actorResponsibility = "Calls into the library and composes it into a larger runnable system"
            )
        }
    }

    fun analyzeMainReachability(
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        systemBoundary: String,
        preferredStartClass: String? = null
    ): MainReachability {
        val mainMethods = methods.filter(::isMainMethod).let { mains ->
            preferredStartClass?.let { startClass ->
                mains.filter { it.declaringClass.className == startClass }.ifEmpty { mains }
            } ?: mains
        }
        if (mainMethods.isEmpty()) {
            return MainReachability(0, 0, 0, 0)
        }
        val internalMethods = methods
            .filter { isInternalArchitectureClass(it.declaringClass.className, systemBoundary) }
            .toSet()
        val outgoing = callSites
            .filter { it.caller in internalMethods }
            .groupBy { it.caller }
        val queue = ArrayDeque<MethodDescriptor>().apply { addAll(mainMethods) }
        val visitedMethods = linkedSetOf<MethodDescriptor>()
        val visitedClasses = linkedSetOf<String>()
        val externalTargets = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val method = queue.removeFirst()
            if (!visitedMethods.add(method)) continue
            if (!isInternalArchitectureClass(method.declaringClass.className, systemBoundary)) continue
            visitedClasses += method.declaringClass.className
            outgoing[method].orEmpty().forEach { callSite ->
                val callee = callSite.callee
                if (isInternalArchitectureClass(callee.declaringClass.className, systemBoundary) && callee in internalMethods) {
                    queue += callee
                } else if (!isSyntheticArchitectureClass(callee.declaringClass.className)) {
                    externalTargets += callee.declaringClass.className
                }
            }
        }
        return MainReachability(
            mainMethodCount = mainMethods.size,
            reachableInternalMethodCount = visitedMethods.size,
            reachableInternalClassCount = visitedClasses.size,
            reachableExternalTargetCount = externalTargets.size
        )
    }

    fun inferName(systemBoundary: String, startClassOrigin: String? = null, startClass: String? = null): String {
        artifactKey(startClassOrigin ?: "")?.let { return humanizeSubjectArtifactLabel(it) }
        startClass?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { simpleName ->
            return simpleName.removeSuffix("Application").removeSuffix("App").ifBlank { simpleName }
        }
        val normalized = systemBoundary.removePrefix("(").removeSuffix(")")
        val leaf = normalized.substringAfterLast('.').takeIf { it.isNotBlank() } ?: normalized
        return leaf.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun readManifestMetadata(graph: Graph): ManifestMetadata {
        val manifest = graph.resources.list(JavaArchiveLayout.META_INF_MANIFEST)
            .firstOrNull()
            ?.let { entry -> runCatching { graph.resources.open(entry.path).bufferedReader().use { it.readText() } }.getOrNull() }
            ?: return ManifestMetadata()
        val attributes = linkedMapOf<String, String>()
        var currentKey: String? = null
        manifest.lineSequence().forEach { line ->
            when {
                line.isBlank() -> currentKey = null
                line.startsWith(" ") && currentKey != null -> {
                    attributes[currentKey!!] = attributes[currentKey!!].orEmpty() + line.removePrefix(" ")
                }
                ":" in line -> {
                    val key = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    attributes[key] = value
                    currentKey = key
                }
            }
        }
        return ManifestMetadata(
            mainClass = attributes[JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE],
            startClass = attributes[JavaArchiveLayout.START_CLASS_ATTRIBUTE]
        )
    }

    fun describeInvocation(subject: SubjectDescriptor, endpointCount: Int): String =
        when (subject.role) {
            "library" -> "Uses ${subject.name} from a host application context"
            else -> if (endpointCount > 0) {
                "Invokes ${subject.name} through its HTTP interface"
            } else {
                "Starts and operates ${subject.name}"
            }
        }

    fun invocationEvidence(subject: SubjectDescriptor, endpointCount: Int): Map<String, Any?> =
        when (subject.role) {
            "library" -> mapOf("role" to "library-consumer")
            else -> mapOf("endpoints" to endpointCount)
        }

    private fun isMainMethod(method: MethodDescriptor): Boolean =
        method.name == "main" &&
            method.returnType.className == "void" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes.first().className.contains("java.lang.String")
}

internal fun inferSubjectDescriptor(
    graph: Graph,
    methods: List<MethodDescriptor>,
    callSites: List<CallSiteNode>,
    endpointCount: Int,
    systemBoundary: String
): SubjectDescriptor =
    SubjectDetector.infer(graph, methods, callSites, endpointCount, systemBoundary)

internal fun analyzeMainReachability(
    methods: List<MethodDescriptor>,
    callSites: List<CallSiteNode>,
    systemBoundary: String,
    preferredStartClass: String? = null
): MainReachability =
    SubjectDetector.analyzeMainReachability(methods, callSites, systemBoundary, preferredStartClass)

internal fun inferSubjectName(systemBoundary: String, startClassOrigin: String? = null, startClass: String? = null): String =
    SubjectDetector.inferName(systemBoundary, startClassOrigin, startClass)

internal fun readManifestMetadata(graph: Graph): ManifestMetadata =
    SubjectDetector.readManifestMetadata(graph)

internal fun describeSubjectInvocation(subject: SubjectDescriptor, endpointCount: Int): String =
    SubjectDetector.describeInvocation(subject, endpointCount)

internal fun subjectInvocationEvidence(subject: SubjectDescriptor, endpointCount: Int): Map<String, Any?> =
    SubjectDetector.invocationEvidence(subject, endpointCount)
