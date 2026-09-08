package main
import("context";"encoding/json";"encoding/base64";"fmt";"os";"quotecheck/javaregex")
func main(){var cases []struct{Pattern,Text string}; b,_:=os.ReadFile(os.Args[1]);json.Unmarshal(b,&cases);for _,c:=range cases {p,e:=javaregex.Compile(c.Pattern);if e!=nil{fmt.Println("ERR "+base64.StdEncoding.EncodeToString([]byte(e.Error())));continue};v,e:=p.MatchesContext(context.Background(),c.Text);if e!=nil{panic(e)};fmt.Println("OK",v)}}
