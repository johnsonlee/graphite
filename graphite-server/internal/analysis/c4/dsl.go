package c4

import (
	"fmt"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

func RenderStructurizrDSL(raw map[string]any) (string, error) {
	w, err := DecodeWorkspace(raw)
	if err != nil {
		return "", err
	}
	return renderDSL(w), nil
}
func renderDSL(w Workspace) string {
	lines := []string{}
	depth := 0
	line := func(s string) { lines = append(lines, strings.Repeat("    ", depth)+s) }
	open := func(s string) { line(s + " {"); depth++ }
	closeBlock := func() { depth--; line("}") }
	quote := func(s string) string {
		return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\r", " ", "\n", " ").Replace(s) + `"`
	}
	ids := map[string]string{}
	used := map[string]bool{}
	remember := func(id string) string {
		if s := ids[id]; s != "" {
			return s
		}
		base := strings.ReplaceAll(Slugify(id), "-", "_")
		if javastring.Trim(base) == "" {
			base = "element"
		}
		candidate := "g_" + base
		for used[candidate] {
			candidate += fmt.Sprintf("_%d", len(used)+1)
		}
		used[candidate] = true
		ids[id] = candidate
		return candidate
	}
	declaration := func(e *WorkspaceElement, kind string) string {
		s := remember(e.ID) + " = " + kind + " " + quote(e.Name) + " " + quote(e.Description)
		if kind == "container" || kind == "component" {
			s += " " + quote(value(e.Technology))
		}
		return s
	}
	open("workspace " + quote(w.Name))
	open("model")
	for _, p := range w.Model.People {
		line(declaration(p, "person"))
	}
	for _, s := range w.Model.SoftwareSystems {
		decl := declaration(s, "softwareSystem")
		if len(s.Containers) == 0 {
			line(decl)
			continue
		}
		open(decl)
		for _, c := range s.Containers {
			decl = declaration(c, "container")
			if len(c.Components) == 0 {
				line(decl)
				continue
			}
			open(decl)
			for _, component := range c.Components {
				line(declaration(component, "component"))
			}
			closeBlock()
		}
		closeBlock()
	}
	seen := map[string]bool{}
	visit := func(e *WorkspaceElement) {
		for _, r := range e.Relationships {
			k := compactJSON([]string{e.ID, r.DestinationID, r.Description})
			if seen[k] {
				continue
			}
			seen[k] = true
			if ids[e.ID] != "" && ids[r.DestinationID] != "" {
				line(ids[e.ID] + " -> " + ids[r.DestinationID] + " " + quote(r.Description))
			}
		}
	}
	for _, p := range w.Model.People {
		visit(p)
	}
	for _, s := range w.Model.SoftwareSystems {
		visit(s)
		for _, c := range s.Containers {
			visit(c)
			for _, component := range c.Components {
				visit(component)
			}
		}
	}
	closeBlock()
	open("views")
	for _, group := range []struct {
		kind  string
		views []WorkspaceView
	}{{"systemContext", w.Views.SystemContextViews}, {"container", w.Views.ContainerViews}, {"component", w.Views.ComponentViews}} {
		for _, v := range group.views {
			scope := value(v.SoftwareSystemID)
			if group.kind == "component" {
				scope = value(v.ContainerID)
			}
			if ids[scope] == "" {
				continue
			}
			key := v.Key
			if javastring.Trim(key) == "" {
				key = "graphite-" + group.kind
				if group.kind == "systemContext" {
					key = "graphite-context"
				}
			}
			open(group.kind + " " + ids[scope] + " " + quote(key))
			line("include *")
			line("autolayout tb")
			closeBlock()
		}
	}
	line("theme default")
	closeBlock()
	closeBlock()
	return strings.Join(lines, "\n")
}
