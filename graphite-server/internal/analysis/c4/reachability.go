package c4

import (
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func methodIdentity(method store.MethodDescriptor) string {
	parts := append([]string{method.DeclaringClass, method.Name, method.ReturnType}, method.ParameterTypes...)
	var key strings.Builder
	for _, part := range parts {
		key.WriteString(strconv.Itoa(len(part)))
		key.WriteByte(':')
		key.WriteString(part)
	}
	return key.String()
}
func IsMainMethod(method store.MethodDescriptor) bool {
	return method.Name == "main" && method.ReturnType == "void" && len(method.ParameterTypes) == 1 && strings.Contains(method.ParameterTypes[0], "java.lang.String")
}

// AnalyzeMainReachability follows method-descriptor identity (including return
// type), limits outgoing calls to declared internal methods, and counts each
// external target class once. This matches SubjectDetector's evidence inputs.
func AnalyzeMainReachability(methods []store.MethodDescriptor, callSites []store.Node, boundary string, preferredStartClass *string) MainReachability {
	mains := []store.MethodDescriptor{}
	for _, method := range methods {
		if IsMainMethod(method) {
			mains = append(mains, method)
		}
	}
	if preferredStartClass != nil {
		preferred := []store.MethodDescriptor{}
		for _, method := range mains {
			if method.DeclaringClass == *preferredStartClass {
				preferred = append(preferred, method)
			}
		}
		if len(preferred) > 0 {
			mains = preferred
		}
	}
	if len(mains) == 0 {
		return MainReachability{}
	}
	internal := map[string]bool{}
	for _, method := range methods {
		if IsInternalClass(method.DeclaringClass, boundary) {
			internal[methodIdentity(method)] = true
		}
	}
	outgoing := map[string][]store.MethodDescriptor{}
	for _, node := range callSites {
		caller := methodIdentity(node.Caller)
		if internal[caller] {
			outgoing[caller] = append(outgoing[caller], node.Callee)
		}
	}
	queue := append([]store.MethodDescriptor{}, mains...)
	visited := map[string]bool{}
	classes := map[string]bool{}
	external := map[string]bool{}
	for head := 0; head < len(queue); head++ {
		method := queue[head]
		key := methodIdentity(method)
		if visited[key] {
			continue
		}
		visited[key] = true
		if !IsInternalClass(method.DeclaringClass, boundary) {
			continue
		}
		classes[method.DeclaringClass] = true
		for _, callee := range outgoing[key] {
			if IsInternalClass(callee.DeclaringClass, boundary) && internal[methodIdentity(callee)] {
				queue = append(queue, callee)
			} else if !IsSyntheticClass(callee.DeclaringClass) {
				external[callee.DeclaringClass] = true
			}
		}
	}
	return MainReachability{len(mains), len(visited), len(classes), len(external)}
}
