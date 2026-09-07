package query

func mainSharedMatcher(plan *mainStringSourceSpec) bool {
	first := plan.atoms[0]
	for _, atom := range plan.atoms[1:] {
		if atom.op != first.op || atom.term != first.term || atom.lower != first.lower {
			return false
		}
	}
	return true
}
func ordinaryRawLeading(plan *ordinaryProjectionPlan) bool {
	if plan.scoped || plan.limit > 200 {
		return false
	}
	for _, atom := range plan.atoms {
		size := len(javaUTF16(atom.term))
		if atom.op != "CONTAINS" || size < 1 || size > 4 {
			return false
		}
	}
	return true
}
func (e evaluator) ordinaryLeadingRows(source Graph, plan *ordinaryProjectionPlan, raw bool) ([]map[string]any, bool) {
	if !plan.leading || plan.generic && len(source.Store.NodesOfKind("AnnotationNode")) != 0 {
		return nil, false
	}
	if raw {
		ids := source.Store.NodesOfKind("CallSiteNode")
		maximum := min(1024, max(64, plan.limit*4))
		rows := []map[string]any{}
		inspected := 0
		for inspected < len(ids) && inspected < maximum {
			e.check()
			id := ids[inspected]
			inspected++
			sids, err := source.Store.ProjectionStringIDs(e.ctx, id)
			failProjectionRead(err)
			matched := false
			for _, atom := range plan.atoms {
				text, err := source.Store.ProjectionString(e.ctx, sids[distinctCallSiteProperties[atom.property]])
				failProjectionRead(err)
				if e.distinctAtomMatches(atom, text) {
					matched = true
					break
				}
			}
			if !matched {
				continue
			}
			rows = append(rows, e.ordinaryRawRow(source, plan, sids))
			if len(rows) >= plan.limit {
				return rows, true
			}
		}
		if inspected < len(ids) {
			return nil, false
		}
		return rows, true
	}
	if ordinarySharedMatcher(plan) {
		view, ok, err := source.Store.InitializedProjectionView(e.ctx)
		failProjectionRead(err)
		var exact []map[int32]bool
		if ok {
			exact, ok = e.ordinaryExactMatches(source, view, plan)
		}
		if ok {
			rows := []map[string]any{}
			for _, id := range e.ordinaryMappedIDs(source, view, plan, exact) {
				e.check()
				sids, err := source.Store.ProjectionStringIDs(e.ctx, id)
				failProjectionRead(err)
				rows = append(rows, e.ordinaryRawRow(source, plan, sids))
				if len(rows) >= plan.limit {
					break
				}
			}
			return rows, true
		}
	}
	index, ok, err := source.Store.RetainedProjectionIndex(e.ctx)
	failProjectionRead(err)
	if !ok {
		return nil, false
	}
	return e.ordinaryIndexedRows(source, index, plan, plan.limit), true
}
func (e evaluator) ordinaryRawRow(source Graph, plan *ordinaryProjectionPlan, sids [4]int32) map[string]any {
	row := map[string]any{}
	for i, property := range plan.properties {
		var value any
		if property == "graphId" {
			value = source.ID
		} else {
			var err error
			value, err = source.Store.ProjectionString(e.ctx, sids[distinctCallSiteProperties[property]])
			failProjectionRead(err)
		}
		row[plan.columns[i]] = value
	}
	addProvenance(row, source.ID)
	return row
}

func ordinarySharedMatcher(plan *ordinaryProjectionPlan) bool {
	return mainSharedMatcher(plan.mainSourceSpec())
}
