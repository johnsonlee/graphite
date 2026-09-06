package server
import (
 "context"
 "testing"
 "github.com/johnsonlee/graphite/graphite-server/internal/query"
 "github.com/johnsonlee/graphite/graphite-server/internal/store"
)
func TestReadonlyAuditPropertyOrder(t *testing.T) {
 graph, err := OpenNativeGraph("../store/testdata/jvm-v3", "MAPPED"); if err != nil {t.Fatal(err)}; defer graph.Close()
 g:=graph.(*NativeGraph); g.Store.Metadata.MethodList=[]store.MethodDescriptor{{Name:"actual"}}
 source := "MATCH (m:Method {name:'absent', class:1/0}) RETURN m.name"
 success, failed := 0,0
 for i:=0;i<200;i++ { _,err:=query.Execute(context.Background(),g.Store,source,nil,1000); if err!=nil {failed++} else {success++} }
 t.Logf("identical uncached execution: success=%d DivisionByZero=%d",success,failed)
 if success==0 || failed==0 {t.Fatal("did not reproduce property evaluation nondeterminism")}
 s:=&Server{}; buildCalls:=0
 for i:=0;i<200;i++ { _,_=s.memoizedCypher(context.Background(),"audit",source,func()(any,error){buildCalls++;_,err:=query.Execute(context.Background(),g.Store,source,nil,1000); return []byte(`{"rows":[]}`),err}) }
 t.Logf("same cacheable expression after first success: builds=%d cacheEntries=%d",buildCalls,s.queryCache.lru.Len())
}
