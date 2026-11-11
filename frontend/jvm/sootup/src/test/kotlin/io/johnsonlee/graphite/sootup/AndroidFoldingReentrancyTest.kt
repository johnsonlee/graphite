package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.sootup.testfixture.minimalAndroidSdk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import sootup.apk.frontend.ApkAnalysisInputLocation
import sootup.apk.frontend.DexBodyInterceptors
import sootup.apk.frontend.main.AndroidVersionInfo
import sootup.core.interceptor.BodyInterceptor
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.stmt.InvokableStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.signatures.MethodSignature
import sootup.java.core.views.JavaView

class AndroidFoldingReentrancyTest {
    @Test
    fun `folding a DEX call reads declaration metadata without reentering eager bodies`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val sdk = minimalAndroidSdk("fold-dex-declarations", apiLevel = 21)
        try {
            val owner = "fixture.frontend.android.AndroidCases"
            val fold = ConstantFolding(listOf(ConstantFold.parse(mapOf(
                "match" to mapOf("CallSite" to mapOf("caller_class" to owner, "callee_name" to "trueBranch")),
                "value" to "folded-true"
            ))))
            val active = mutableSetOf<MethodSignature>()
            val reentered = mutableSetOf<MethodSignature>()
            val begin = BodyInterceptor { body, _ ->
                if (!active.add(body.methodSignature)) {
                    reentered.add(body.methodSignature)
                    error("Declaration lookup recursively requested the active DEX body ${body.methodSignature}")
                }
            }
            val end = BodyInterceptor { body, _ -> active.remove(body.methodSignature) }
            val chain = listOf(begin) + fold.bodyInterceptors(DexBodyInterceptors.Default.bodyInterceptors()) + end
            val view = JavaView(ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()), chain))
            val type = view.getClass(view.identifierFactory.getClassType(owner)).get()
            val methods = type.methods
            assertTrue(reentered.isEmpty(), "Folding must not recursively request DEX bodies: $reentered")
            assertTrue(active.isEmpty(), "Every eager body interception must finish")
            val branch = methods.single { it.name == "branch" }.body
            val assignments = branch.stmts.filterIsInstance<JAssignStmt>()
            val folded = assignments.single { (it.rightOp as? StringConstant)?.value == "folded-true" }
            val copy = assertIs<JAssignStmt>(branch.controlFlowGraph.successors(folded).single())
            assertEquals<Value>(folded.leftOp, copy.rightOp)
            val returned = assertIs<JReturnStmt>(branch.controlFlowGraph.successors(copy).single())
            assertEquals<Value>(copy.leftOp, returned.op)
            val callees = branch.stmts.filterIsInstance<InvokableStmt>().mapNotNull { it.invokeExpr.orElse(null)?.methodSignature?.name }
            assertFalse("trueBranch" in callees)
            assertTrue("falseBranch" in callees, "The unselected branch must remain")
            assertEquals(1, fold.report().outcomes.single().matched)
        } finally {
            sdk.toFile().deleteRecursively()
        }
    }
}
