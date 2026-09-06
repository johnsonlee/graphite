package query
import("context";"encoding/json";"os";"testing";"github.com/johnsonlee/graphite/graphite-server/internal/store")
func TestExternalCandidateContract(t *testing.T){
 raw,err:=os.ReadFile(os.Getenv("CANDIDATE_CONTRACT_CASES"));if err!=nil{t.Fatal(err)}
 var cases []struct{Name,Query string;Empty bool;Params map[string]any};if err=json.Unmarshal(raw,&cases);err!=nil{t.Fatal(err)}
 mapped,err:=store.OpenMode(os.Getenv("EARLY_MATCH_FIXTURE"), os.Getenv("EARLY_MATCH_MODE"));if err!=nil{t.Fatal(err)};defer mapped.Close();empty:=&store.Store{}
 outputs:=[]map[string]any{}
 for _,c:=range cases{for _,cross:=range []bool{false,true}{graph:=mapped;if c.Empty{graph=empty};var result Result;var err error
  if cross{result,err=ExecuteCross(context.Background(),[]Graph{{"a",graph},{"b",graph}},c.Query,c.Params,-1)}else{result,err=Execute(context.Background(),graph,c.Query,c.Params,-1)}
  output:=map[string]any{"name":c.Name,"query":c.Query,"cross":cross,"empty":c.Empty}
  if err!=nil{output["message"]=err.Error();if e,ok:=err.(*Error);ok{output["error"]=e.Class}}else{output["columns"]=result.Columns;output["rows"]=result.Rows}
  outputs=append(outputs,output)
 }}
 raw,err=json.MarshalIndent(outputs,"","  ");if err!=nil{t.Fatal(err)};if err=os.WriteFile(os.Getenv("CANDIDATE_CONTRACT_OUTPUT"),append(raw,'\n'),0644);err!=nil{t.Fatal(err)}
}
