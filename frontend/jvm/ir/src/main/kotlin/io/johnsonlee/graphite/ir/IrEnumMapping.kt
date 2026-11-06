package io.johnsonlee.graphite.ir

import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeRelation

/** Maps explicit wire enums to core kinds, rejecting unspecified or unknown protocol values. */
internal fun dataFlowKind(k: io.johnsonlee.graphite.ir.v1.DataFlowKind): DataFlowKind =
    when (k) {
        io.johnsonlee.graphite.ir.v1.DataFlowKind.ASSIGN -> DataFlowKind.ASSIGN
        io.johnsonlee.graphite.ir.v1.DataFlowKind.PARAMETER_PASS -> DataFlowKind.PARAMETER_PASS
        io.johnsonlee.graphite.ir.v1.DataFlowKind.RETURN_VALUE -> DataFlowKind.RETURN_VALUE
        io.johnsonlee.graphite.ir.v1.DataFlowKind.FIELD_STORE -> DataFlowKind.FIELD_STORE
        io.johnsonlee.graphite.ir.v1.DataFlowKind.FIELD_LOAD -> DataFlowKind.FIELD_LOAD
        io.johnsonlee.graphite.ir.v1.DataFlowKind.ARRAY_STORE -> DataFlowKind.ARRAY_STORE
        io.johnsonlee.graphite.ir.v1.DataFlowKind.ARRAY_LOAD -> DataFlowKind.ARRAY_LOAD
        io.johnsonlee.graphite.ir.v1.DataFlowKind.CAST -> DataFlowKind.CAST
        io.johnsonlee.graphite.ir.v1.DataFlowKind.PHI -> DataFlowKind.PHI
        io.johnsonlee.graphite.ir.v1.DataFlowKind.DATA_FLOW_KIND_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.DataFlowKind.UNRECOGNIZED -> throw IrFormatException("IR data-flow edge has no kind")
    }

internal fun resourceRelation(k: io.johnsonlee.graphite.ir.v1.ResourceRelation): ResourceRelation =
    when (k) {
        io.johnsonlee.graphite.ir.v1.ResourceRelation.OPENS -> ResourceRelation.OPENS
        io.johnsonlee.graphite.ir.v1.ResourceRelation.LOADS -> ResourceRelation.LOADS
        io.johnsonlee.graphite.ir.v1.ResourceRelation.BUNDLE_CANDIDATE -> ResourceRelation.BUNDLE_CANDIDATE
        io.johnsonlee.graphite.ir.v1.ResourceRelation.LOOKUP -> ResourceRelation.LOOKUP
        io.johnsonlee.graphite.ir.v1.ResourceRelation.ENUMERATES -> ResourceRelation.ENUMERATES
        io.johnsonlee.graphite.ir.v1.ResourceRelation.RESOURCE_RELATION_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.ResourceRelation.UNRECOGNIZED -> throw IrFormatException("IR resource edge has no kind")
    }

internal fun typeRelation(k: io.johnsonlee.graphite.ir.v1.TypeRelation): TypeRelation =
    when (k) {
        io.johnsonlee.graphite.ir.v1.TypeRelation.EXTENDS -> TypeRelation.EXTENDS
        io.johnsonlee.graphite.ir.v1.TypeRelation.IMPLEMENTS -> TypeRelation.IMPLEMENTS
        io.johnsonlee.graphite.ir.v1.TypeRelation.TYPE_RELATION_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.TypeRelation.UNRECOGNIZED -> throw IrFormatException("IR type relation has no kind")
    }

internal fun controlFlowKind(k: io.johnsonlee.graphite.ir.v1.ControlFlowKind): ControlFlowKind =
    when (k) {
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.SEQUENTIAL -> ControlFlowKind.SEQUENTIAL
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_TRUE -> ControlFlowKind.BRANCH_TRUE
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_FALSE -> ControlFlowKind.BRANCH_FALSE
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.SWITCH_CASE -> ControlFlowKind.SWITCH_CASE
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.SWITCH_DEFAULT -> ControlFlowKind.SWITCH_DEFAULT
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.EXCEPTION -> ControlFlowKind.EXCEPTION
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.RETURN -> ControlFlowKind.RETURN
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.CONTROL_FLOW_KIND_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.UNRECOGNIZED -> throw IrFormatException("IR control-flow edge has no kind")
    }

internal fun comparisonOp(k: io.johnsonlee.graphite.ir.v1.ComparisonOp): ComparisonOp =
    when (k) {
        io.johnsonlee.graphite.ir.v1.ComparisonOp.EQ -> ComparisonOp.EQ
        io.johnsonlee.graphite.ir.v1.ComparisonOp.NE -> ComparisonOp.NE
        io.johnsonlee.graphite.ir.v1.ComparisonOp.LT -> ComparisonOp.LT
        io.johnsonlee.graphite.ir.v1.ComparisonOp.GE -> ComparisonOp.GE
        io.johnsonlee.graphite.ir.v1.ComparisonOp.GT -> ComparisonOp.GT
        io.johnsonlee.graphite.ir.v1.ComparisonOp.LE -> ComparisonOp.LE
        io.johnsonlee.graphite.ir.v1.ComparisonOp.COMPARISON_OP_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.ComparisonOp.UNRECOGNIZED -> throw IrFormatException("IR branch comparison has no operator")
    }
