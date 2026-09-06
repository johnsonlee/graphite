package query

import (
 "context"
 "fmt"
 "reflect"
 "testing"
 "github.com/johnsonlee/graphite/graphite-server/internal/cypher"
 "github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func auditEval(e evaluator,expr cypher.Expr,n any)(value any,problem string){
 defer func(){if p:=recover();p!=nil {if q,ok:=p.(*Error);ok{problem=q.Class+":"+q.Message}else{problem=fmt.Sprintf("%T:%v",p,p)}}}()
 value=freezeCandidate(e.eval(expr,map[string]any{"n":n,"old":n}));return
}
func TestExternalSlotTransparency(t *testing.T){
 graph,err:=store.Open("../store/testdata/jvm-v3");if err!=nil{t.Fatal(err)};defer graph.Close()
 slots:=[]candidateSlot{};for _,id:=range graph.NodeIDs(){n,err:=graph.Node(id);if err!=nil{t.Fatal(err)};slots=append(slots,candidateSlot{node:n,graph:graph})}
 for _,m:=range graph.Metadata.MethodList{slots=append(slots,candidateSlot{isMethod:true,method:m,graph:graph})}
 expressions:=[]string{"n","n IS NULL","n IS NOT NULL","NOT n","+n","-n","DISTINCT n","n=n","n=old","n<>old","n<old","n<=old","n>old","n>=old","n IN [n]","n IN [null,n]","n IN []","n+1","1+n","n-1","1/n","n*2","n%2","n+'x'","'x'+n","[n]+n","n+[n]","[n,null]","{a:n,b:[n]}","CASE WHEN true THEN n ELSE null END","CASE n WHEN old THEN [n] ELSE [] END","coalesce(null,n)","coalesce(n,1)","[x IN [1,2] | n]","[x IN [n,n] | x]","[n IN [1,2] | n]","any(x IN [n] WHERE x=n)","all(x IN [n] WHERE x=old)","single(x IN [1,n] WHERE x=n)","none(x IN [null,n] WHERE x=n)","n[0]","n[0..1]","n AND true","n OR false","n XOR true","n =~ '['","n CONTAINS 'a'","'a' CONTAINS n","n.id","n.value","n.values","n.name","n.signature","n.graphId","n.elementId","n.qualifiedId","properties(n).id","properties(n).name","head([n])","last([n])","head([1]+n)","head(n+[1])","nodes([n])","relationships([n])","toString([n])","toString({n:n})","toString([x IN [1,2] | n])","keys({n:n})","split('a',n)","substring('a',n)","substring('a',0,n)","replace('a',n,'b')","replace('a','b',n)","range(n,1)","range(1,n)","range(1,2,n)","atan2(1,n)"}
 for _,name:=range []string{"id","elementId","qualifiedId","graphId","exists","labels","properties","keys","type","toString","toInteger","toFloat","toBoolean","toLower","toUpper","trim","ltrim","rtrim","replace","split","substring","left","right","reverse","head","last","tail","nodes","relationships","size","length","abs","ceil","floor","round","sign","sqrt","exp","log","log10","sin","cos","tan","asin","acos","atan","atan2","cot","degrees","radians"}{expressions=append(expressions,name+"(n)")}
 checks:=0
 for _,source:=range expressions {
  ast,err:=cypher.Parse("RETURN "+source+" AS x");if err!=nil{t.Fatalf("parse %s: %v",source,err)};expr:=ast.Branches[0].Clauses[0].(cypher.ProjectionClause).Items[0].Expression
  for _,template:=range slots {for _,qualified:=range []bool{false,true}{
   slot:=template;slot.qualified=qualified;slot.graphID="a"
   boxed:=freezeCandidate(&slot)
   e:=evaluator{ctx:context.Background(),rowOrders:map[string]rowOrder{},regexes:&regexLRU{entries:map[string]compiledRegex{}}}
   want,we:=auditEval(e,expr,boxed);got,ge:=auditEval(e,expr,&slot)
   if we!=ge||!reflect.DeepEqual(want,got){t.Errorf("%s kind=%s method=%t qualified=%t\nboxed=%#v error=%s\nslot=%#v error=%s",source,slot.node.Kind,slot.isMethod,qualified,want,we,got,ge)}
   // Mutating the borrow after evaluation must not alter any published container.
   slot.node=store.Node{ID:999,Kind:"IntConstant",Value:int32(999)};slot.method=store.MethodDescriptor{Name:"changed"}
   if we==""&&!reflect.DeepEqual(want,got){t.Errorf("container retained borrowed slot: %s",source)}
   for _,order:=range e.rowOrders{for k,v:=range order.row{if _,ok:=v.(*candidateSlot);ok{t.Errorf("rowOrder retained slot at %s in %s",k,source)}}}
   checks++
  }}
 }
 t.Logf("expressions=%d entities=%d qualifiedModes=2 checks=%d",len(expressions),len(slots),checks)
}
