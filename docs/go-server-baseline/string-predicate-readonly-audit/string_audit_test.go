package query

import (
 "context"
 "errors"
 "strings"
 "testing"
 "unicode/utf8"
 "github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

// The independent expected result scans the original UTF16 units directly;
// it does not decode with the implementation's javaUTF16 helper.
func auditEncodeUnits(units []uint16)string{
 bytes:=[]byte{}
 for i:=0;i<len(units);i++ {u:=units[i];if u>=0xD800&&u<=0xDBFF&&i+1<len(units)&&units[i+1]>=0xDC00&&units[i+1]<=0xDFFF {r:=rune(0x10000)+rune(u-0xD800)*1024+rune(units[i+1]-0xDC00);bytes=utf8.AppendRune(bytes,r);i++}else if u>=0xD800&&u<=0xDFFF {bytes=append(bytes,byte(0xe0|u>>12),byte(0x80|(u>>6)&63),byte(0x80|u&63))}else{bytes=utf8.AppendRune(bytes,rune(u))}}
 return string(bytes)
}
func auditUnitsPredicate(a,b []uint16,op string)bool{
 at:=func(index int)bool{if index<0||index+len(b)>len(a){return false};for j,u:=range b{if a[index+j]!=u{return false}};return true}
 base:=strings.TrimPrefix(op,"NOT ");result:=false
 switch base{case "STARTS WITH":result=at(0);case "ENDS WITH":result=at(len(a)-len(b));case "CONTAINS":for i:=0;i<=len(a);i++{if at(i){result=true;break}}}
 if base!=op{return !result};return result
}
func TestExternalPredicateUTF16Boundary(t *testing.T){
 values:=[][]uint16{{},{0},{0x7f},{0x80},{0x7ff},{0x800},{0xd7ff},{0xd800},{0xdbff},{0xdc00},{0xdfff},{0xe000},{0xfffd},{0xffff},{'a'},{'a','b'}}
 for _,h:=range []uint16{0xd800,0xd83d,0xdbfe,0xdbff}{for _,l:=range []uint16{0xdc00,0xde00,0xdffe,0xdfff}{values=append(values,[]uint16{h,l},[]uint16{l,h},[]uint16{'a',h,l,'b'})}}
 ops:=[]string{"STARTS WITH","ENDS WITH","CONTAINS","NOT STARTS WITH","NOT ENDS WITH","NOT CONTAINS"};e:=evaluator{ctx:context.Background()};count:=0
 for _,left:=range values{for _,right:=range values{for _,op:=range ops{
  got:=e.binary(cypher.Binary{Left:cypher.Literal{Value:auditEncodeUnits(left)},Op:op,Right:cypher.Literal{Value:auditEncodeUnits(right)}},nil);want:=auditUnitsPredicate(left,right,op)
  if got!=want{t.Fatalf("%x %s %x got%v want%v",left,op,right,got,want)};count++
 }}}
 t.Logf("unitVectors=%d operators=6 comparisons=%d",len(values),count)
}
func TestExternalPredicateOperandOrder(t *testing.T){
 count:=0
 for _,op:=range []string{"STARTS WITH","ENDS WITH","CONTAINS","NOT STARTS WITH","NOT ENDS WITH","NOT CONTAINS"}{
  for _,left:=range []string{"null","1","[]","{}"}{r,err:=Execute(context.Background(),nil,"RETURN ("+left+") "+op+" (1/0) AS x",nil,-1);if err!=nil||len(r.Rows)!=1||r.Rows[0]["x"]!=nil{t.Fatalf("nonstring left evaluated RHS: %#v %v",r,err)};count++}
  for _,right:=range []string{"null","1","[]","{}"}{r,err:=Execute(context.Background(),nil,"RETURN 'abc' "+op+" ("+right+") AS x",nil,-1);if err!=nil||len(r.Rows)!=1||r.Rows[0]["x"]!=nil{t.Fatalf("nonstring right negated null: %#v %v",r,err)};count++}
  _,err:=Execute(context.Background(),nil,"RETURN (1/0) "+op+" substring('a','wrong') AS x",nil,-1);if err==nil||err.Error()!="Division by zero"{t.Fatalf("left error ordering %v",err)};count++
  _,err=Execute(context.Background(),nil,"RETURN 'abc' "+op+" (1/0) AS x",nil,-1);if err==nil||err.Error()!="Division by zero"{t.Fatalf("right error missing %v",err)};count++
 }
 t.Logf("operandOrderCases=%d",count)
}
type auditCancellation struct{context.Context;checks,at int}
func(c *auditCancellation)Err()error{c.checks++;if c.checks>=c.at{return context.Canceled};return nil}
func TestExternalPredicateCancelBoundaries(t *testing.T){
 for _,op:=range []string{"STARTS WITH","ENDS WITH","CONTAINS","NOT STARTS WITH","NOT ENDS WITH","NOT CONTAINS"}{for _,at:=range []int{3,4}{func(){ctx:=&auditCancellation{Context:context.Background(),at:at};defer func(){err,ok:=recover().(error);if !ok||!errors.Is(err,context.Canceled)||ctx.checks!=at{t.Fatalf("%s at%d canceled%v checks%d",op,at,err,ctx.checks)}}();e:=evaluator{ctx:ctx};e.binary(cypher.Binary{Left:cypher.Literal{Value:"a😀z"},Op:op,Right:cypher.Literal{Value:"😀"}},nil);t.Fatal("canceled operation returned") }()}}
}
