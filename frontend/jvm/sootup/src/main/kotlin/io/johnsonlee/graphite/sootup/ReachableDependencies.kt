package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.sootup.SootUpAdapter.ReachableFrontier
import io.johnsonlee.graphite.sootup.SootUpAdapter.HeldNotes

/** Follows dependency bodies and initialization prerequisites within the declared class budget. */
internal class ReachableDependencies(private val context: SootUpAdapter) {
    private val classEvidence = context.classEvidence
    private val reachableExpansion = context.reachableExpansion
    private val reachedCallees = context.reachedCallees
    private val reachedVirtually = context.reachedVirtually
    private val reachedClasses = context.reachedClasses
    private val notedClasses = context.notedClasses
    private val notedCallees = context.notedCallees
    private val reachableFrontier = context.reachableFrontier
    private var reachableBudget
        get() = context.reachableBudget
        set(value) { context.reachableBudget = value }
    private var reachableRead
        get() = context.reachableRead
        set(value) { context.reachableRead = value }
    private var reachablePastBudget
        get() = context.reachablePastBudget
        set(value) { context.reachablePastBudget = value }
    private val seededClasses = context.seededClasses
    private val seededMethods = context.seededMethods
    private val heldNotes = context.heldNotes
    private val applicationClasses = context.applicationClasses
    private var classesByNameCache
        get() = context.classesByNameCache
        set(value) { context.classesByNameCache = value }
    private val view = context.view
    private val artifactRoles = context.artifactRoles
    private val construction get() = context.construction
    private val declarations get() = context.declarations
    private val descriptors get() = context.descriptors
    private val dispatchHierarchy get() = context.dispatchHierarchy
    private val resources get() = context.resources

    /**
     * Read the library classes the application reaches, see [DependencyBodyScope.Reachable]:
     * the class of each call's declared callee, of each override the view knows for a virtual
     * callee, and so on through the calls those bodies make, until nothing new is reached or
     * [scope]'s budget is spent; the classes reached past it are [AnalysisStatus.NOT_EXPANDED].
     * Only library classes are reached: an application class the configuration excluded stays
     * excluded. Each class is read once, so the expansion terminates on any call graph; the
     * budget and the frontier persist across calls, so the pass after the dispatch fixpoint
     * continues where this one stopped.
     */
    fun expandReachableClasses(scope: DependencyBodyScope.Reachable, extensionContext: GraphiteContext) {
        val frontier = reachableFrontier
        while (true) {
            drainReachedCallees(frontier)
            val className = frontier.queue.removeFirstOrNull() ?: break
            val sootClass = classesByNameCache?.get(className)
            if (sootClass != null) {
                if (reachableBudget == 0) {
                classEvidence[className] = ClassEvidence(classEvidence[className]?.origin, AnalysisStatus.NOT_EXPANDED)
                reachablePastBudget++
            } else {
                reachableBudget--
                reachableRead++
                construction.processDependencyOrApplicationClass(sootClass, extensionContext, application = false)
                }
            }
        }
        declarations.log { "Reachable expansion read $reachableRead dependency class(es), $reachablePastBudget " +
            "past the budget of ${scope.maxClasses}" }
    }

    /**
     * Follow the callees noted since the last drain (the declared callee and, for a virtual
     * callee, its overrides) and the classes followed static field accesses initialise, whose
     * initialisers run on first access without any call. Following may seed an application
     * method, which releases its held notes onto the same lists, so the loop runs until both
     * are drained.
     */
    fun drainReachedCallees(frontier: ReachableFrontier) {
        while (frontier.drained < reachedCallees.size || frontier.drainedClasses < reachedClasses.size) {
            while (frontier.drained < reachedCallees.size) {
                val index = frontier.drained++
                follow(frontier, reachedCallees[index], reachedVirtually[index])
            }
            while (frontier.drainedClasses < reachedClasses.size) {
                reach(frontier, reachedClasses[frontier.drainedClasses++])
            }
        }
    }

    /**
     * Follow a call: the class [callee] initialises, the method itself when it is the
     * application's, and the overrides a [virtual] call reaches.
     */
    fun follow(frontier: ReachableFrontier, callee: MethodDescriptor, virtual: Boolean) {
        reach(frontier, callee.declaringClass.className)
        seedMethod(callee)
        if (virtual) {
            dispatchHierarchy.overridesOf(callee).forEach { override ->
                reach(frontier, override.declaringClass.className)
                seedMethod(override)
            }
        }
    }

    /**
     * Reach [className], once: the classes the JVM initialises before it first (JVMS 5.5),
     * whatever their status, then the class itself. A library class the scope left out is
     * queued to be read; an application class is already read, so its initialiser is seeded
     * ([seedMethod]) and the walk goes on to its own prerequisites, which may be a library's
     * again. An application class excluded by configuration is neither.
     */
    fun reach(frontier: ReachableFrontier, className: String) {
        if (!frontier.reached.add(className)) return
        initializationPrerequisites(className).forEach { reach(frontier, it) }
        if (className in applicationClasses) {
            seedMethod(classInitializerOf(className))
        } else {
            val evidence = classEvidence[className] ?: return
            if (evidence.status == AnalysisStatus.EXCLUDED_BY_SCOPE && isLibraryOrigin(evidence.origin)) frontier.queue += className
        }
    }

    /**
     * Seed an application method a followed call reached: its own calls are followed from now
     * on, and the notes it held while nothing reached it are released. A root's methods and a
     * library class's are seeded with their class.
     */
    fun seedMethod(method: MethodDescriptor) {
        val className = method.declaringClass.className
        if (className !in applicationClasses || className in seededClasses || !seededMethods.add(method)) return
        heldNotes.remove(method)?.let { held ->
            held.callees.forEach { (callee, virtual) -> noteFollowedCallee(callee, virtual) }
            held.classes.forEach(::noteFollowedClass)
        }
    }

    /** The descriptor of [className]'s initialiser, canonical with the one its body was read under. */
    fun classInitializerOf(className: String): MethodDescriptor {
        val factory = view.identifierFactory
        return descriptors.toMethodDescriptor(factory.getMethodSignature(factory.getClassType(className), "<clinit>", "void", emptyList()))
    }

    /** Whether the calls [caller] makes are followed by the expansion: its class is seeded, or it was reached itself. */
    fun isSeeded(caller: MethodDescriptor): Boolean =
        caller.declaringClass.className in seededClasses || caller in seededMethods

    /**
     * Under a reachable scope, note that [caller] initialises [className]: the class that
     * declares the field a static field access resolves to (`getstatic Child.value` on a field
     * `Base` declares initialises `Base`, not `Child`). Followed at once when [caller]'s calls
     * are followed, held with the caller until something reaches it otherwise.
     */
    fun noteClassInitialized(className: String, caller: MethodDescriptor) {
        if (reachableExpansion == null) return
        if (isSeeded(caller)) noteFollowedClass(className) else heldNotes.getOrPut(caller) { HeldNotes() }.classes += className
    }

    /** Queue [className] for the expansion to reach, once. */
    fun noteFollowedClass(className: String) {
        if (notedClasses.add(className)) reachedClasses += className
    }

    /**
     * The classes the JVM initialises before [className] (JVMS 5.5): its superclass, and every
     * superinterface, direct or indirect, that declares a non-abstract, non-static method (a
     * default method); an interface's superinterfaces are not initialised with it. Only classes
     * the view holds; a superinterface outside it initialises nothing the graph could read.
     */
    fun initializationPrerequisites(className: String): List<String> {
        val sootClass = resources.resolveClassByName(className)?.takeIf { !it.isInterface }
        val result = LinkedHashSet<String>()
        if (sootClass != null) {
            sootClass.superclass.orElse(null)?.let { result += it.fullyQualifiedName }
            sootClass.interfaces.forEach { collectDefaultBearingInterfaces(it.fullyQualifiedName, HashSet(), result) }
        }
        return result.toList()
    }

    /** Add [type] to [result] when it declares a default method, and visit its superinterfaces, each once. */
    fun collectDefaultBearingInterfaces(type: String, visited: MutableSet<String>, result: MutableSet<String>) {
        val iface = if (visited.add(type)) resources.resolveClassByName(type) else null
        if (iface != null) {
            if (iface.methods.any { !it.isAbstract && !it.isStatic }) result += type
            iface.interfaces.forEach { collectDefaultBearingInterfaces(it.fullyQualifiedName, visited, result) }
        }
    }

    /** Whether a class read from [origin] is a library's (or the platform's), not the application's. */
    fun isLibraryOrigin(origin: String?): Boolean =
        origin != null && (artifactRoles[origin] ?: ArtifactRole.APPLICATION) != ArtifactRole.APPLICATION

    /**
     * Under a reachable scope, note a call [caller] makes on [callee] for the expansion to
     * follow. Whether it is followed is the caller's: a root's method, a library method the
     * expansion read, or an application method something followed has reached (by the
     * classification pass 2 gave the class, whatever artifact holds it; a call resolved after
     * class processing is attributed to its own caller, never to the class that happened to
     * be processed last); any other application method holds the note until it is reached.
     */
    fun noteReachedCallee(callee: MethodDescriptor, virtual: Boolean, caller: MethodDescriptor) {
        if (reachableExpansion == null) return
        if (isSeeded(caller)) {
            noteFollowedCallee(callee, virtual)
        } else {
            heldNotes.getOrPut(caller) { HeldNotes() }.callees.merge(callee, virtual, Boolean::or)
        }
    }

    /**
     * Queue [callee] for the expansion to follow: once per callee, and once more when a virtual
     * call follows a non-virtual one on the same callee (`super.value()` before
     * `receiver.value()`), since only the virtual call reaches the overrides and the first note
     * may already have been drained.
     */
    fun noteFollowedCallee(callee: MethodDescriptor, virtual: Boolean) {
        val noted = notedCallees[callee]
        if (noted == null || (virtual && !noted)) {
            notedCallees[callee] = virtual || noted == true
            reachedCallees += callee
            reachedVirtually += virtual
        }
    }
}
