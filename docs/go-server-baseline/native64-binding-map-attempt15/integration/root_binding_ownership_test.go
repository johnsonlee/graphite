package query
import("context";"errors";"testing";"time")
func TestRootBindingTwoTaskCancellationAndNextWave(t *testing.T) {
 scanners:=[]*genericDistinctScanner{bindingScanner(t,"n.id AS x"),bindingScanner(t,"n.id AS x")}
 ctx,cancel:=context.WithCancel(context.Background());defer cancel()
 entered:=[]chan struct{}{make(chan struct{}),make(chan struct{})};exited:=[]chan struct{}{make(chan struct{}),make(chan struct{})};result:=make(chan any,1)
 go func(){result<-bindingCaught(func(){runDistinctTasks(ctx,2,2,false,func(worker context.Context,i int)[]any{defer close(exited[i]);probe:=&bindingProjectionContext{Context:worker,scanner:scanners[i],entered:entered[i]};values,_:=scanners[i].next(probe,false);return values},func(int,[]any)bool{return false})})}()
 for i:=range entered {select{case <-entered[i]:case <-time.After(5*time.Second):t.Fatal("task never reached projection",i)}}
 cancel()
 select{case caught:=<-result:err,ok:=caught.(error);if !ok||!errors.Is(err,context.Canceled){t.Fatal("wrong cancellation",caught)};case <-time.After(5*time.Second):t.Fatal("tasks failed to join")}
 for i,s:=range scanners {select{case <-exited[i]:default:t.Fatal("owner returned with task alive",i)};assertBindingCleared(t,s)}
 rows:=make([][]any,2)
 runDistinctTasks(context.Background(),2,2,false,func(worker context.Context,i int)[]any{values,ok:=scanners[i].next(worker,true);if !ok{panic("missing next wave row")};return values},func(i int,values []any)bool{rows[i]=values;return false})
 for i,row:=range rows {if len(row)!=1||row[0]!=int32(2){t.Fatal("later wave observed stale candidate",i,row)};assertBindingCleared(t,scanners[i])}
}
