package query

import (
 "context"
 "encoding/json"
 "os"
 "testing"
 "github.com/johnsonlee/graphite/graphite-server/internal/store"
)
func TestExternalFullQueryAudit(t *testing.T){
 graph,err:=store.Open("../store/testdata/jvm-v3");if err!=nil{t.Fatal(err)};defer graph.Close()
 raw,err:=os.ReadFile(os.Getenv("SLOT_AUDIT_CASES"));if err!=nil{t.Fatal(err)};var queries []string;if err=json.Unmarshal(raw,&queries);err!=nil{t.Fatal(err)}
 for _,source:=range queries{for _,cross:=range []bool{false,true}{
  var result Result;var err error
  if cross{result,err=ExecuteCross(context.Background(),[]Graph{{"a",graph},{"b",graph}},source,nil,-1)}else{result,err=Execute(context.Background(),graph,source,nil,-1)}
  output:=map[string]any{"query":source,"cross":cross,"result":result}
  if err!=nil {output["error"]=err.Error();if e,ok:=err.(*Error);ok{output["class"]=e.Class}}
  raw,e:=json.Marshal(output);if e!=nil{t.Fatal(e)};t.Log("RECORD "+string(raw))
 }}
}
