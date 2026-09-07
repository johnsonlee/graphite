package query
import("context";"encoding/json";"os";"testing";"github.com/johnsonlee/graphite/graphite-server/internal/cypher")
func TestCDEReal64WorkloadAdmission(t *testing.T){
 var cfg struct { Queries []struct {Name,Query string} };raw,err:=os.ReadFile("/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-lazy-filtered-integration/config.pending-main.json");if err!=nil{t.Fatal(err)};if err=json.Unmarshal(raw,&cfg);err!=nil{t.Fatal(err)}
 results:=[]map[string]any{}
 for _,q:=range cfg.Queries[5:] {ast,err:=cypher.Parse(q.Query);if err!=nil{t.Fatal(err)};e:=evaluator{ctx:context.Background(),cross:true};branch:=ast.Branches[0];p:=e.compileLazyFiltered(branch);if p==nil{t.Fatal(q.Name,"missingCDE")};if e.compileIndexedDistinct(branch)!=nil||e.compileOrdinaryProjection(branch)!=nil||e.compileGenericDistinct(branch)!=nil{t.Fatal(q.Name,"earlier consumer intercepts")};results=append(results,map[string]any{"name":q.Name,"query":q.Query,"bounded":p.bounded,"distinct":p.projection.Distinct,"candidateAtoms":len(p.atoms),"noEarlierConsumer":true})}
 raw,err=json.MarshalIndent(results,"","  ");if err!=nil{t.Fatal(err)};if err=os.WriteFile("/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-lazy-filtered-integration/admission.json",raw,0600);err!=nil{t.Fatal(err)}
}
