package javastring
import("fmt";"math/rand";"strings";"testing";"unicode/utf16")
func originalCase(s string, upper bool, check func()) string {
	if check == nil {
		check = func() {}
	}
	points := CodePoints(s)
	var boundaries map[int]bool
	if !upper && strings.ContainsRune(s, 'Σ') {
		boundaries = wordBoundaries(points, check)
	}
	var result strings.Builder
	for i, r := range points {
		check()
		mapping := javaLower
		if upper {
			mapping = javaUpper
		}
		if !upper && r == 'Σ' {
			before, after := false, false
			for j := i; j > 0 && !boundaries[j]; {
				j--
				if javaCased[points[j]] {
					before = true
					break
				}
			}
			if before {
				for j := i + 1; j < len(points) && !boundaries[j]; j++ {
					if javaCased[points[j]] {
						after = true
						break
					}
				}
			}
			if before && !after {
				result.WriteRune('ς')
				continue
			}
		}
		if replacement, ok := mapping[r]; ok {
			result.WriteString(replacement)
		} else if utf16.IsSurrogate(r) {
			result.WriteString(FromUTF16([]uint16{uint16(r)}))
		} else {
			result.WriteRune(r)
		}
	}
	return result.String()
}


func TestIndependentASCIILowerParity(t *testing.T) {
 corpus:=[]string{"", "a", "Z", "\x00\x7f", "ALREADY_lower09", "ΟΔΥΣΣΕΎΣ", "AΣ_A", "Iİıi", "A\xed\xa0\x80Z", "A\xed\xb0\x80Z", "A\xffZ", "A𐐀Z", "AΣ́", "AΣ\x00A", strings.Repeat("A",1024)+"Σ", strings.Repeat("a",1024)+"İ"}
 for i:=0;i<256;i++ {corpus=append(corpus,string([]byte{'A',byte(i),'Z'}))}
 rng:=rand.New(rand.NewSource(81017));for n:=0;n<10000;n++ {b:=make([]byte,rng.Intn(49));for i:=range b {if n%2==0 {b[i]=byte(rng.Intn(128))}else{b[i]=byte(rng.Intn(256))}};corpus=append(corpus,string(b))}
 count:=0
 for _,s:=range corpus {for _,upper:=range []bool{false,true} {
  oldCalls,newCalls:=0,0;want:=originalCase(s,upper,func(){oldCalls++});got:=Case(s,upper,func(){newCalls++});if got!=want||newCalls!=oldCalls {t.Fatalf("input %x upper %v got %x/%d want %x/%d",s,upper,got,newCalls,want,oldCalls)}
  if Case(s,upper,nil)!=want {t.Fatalf("nil callback input %x",s)};count++
 }}
 t.Logf("%d exact old/new values and callback counts; nil callback also checked",count)
}
func TestIndependentASCIILowerCancellation(t *testing.T) {
 inputs:=[]string{"", "aZ09\x00\x7f", "already_lower", "ALL_UPPER", "AΣ_A", "AİZ", "A\xed\xa0\x80Z", "A\xffZ", "A𐐀Z"}
 observe:=func(fn func(string,bool,func())string,s string,upper bool,stop int)(value string,calls int,raised any){defer func(){raised=recover()}();value=fn(s,upper,func(){calls++;if calls==stop {panic(fmt.Sprintf("cancel-%d",stop))}});return}
 count:=0
 for _,s:=range inputs {for _,upper:=range []bool{false,true} {_,max,_:=observe(originalCase,s,upper,0);for stop:=1;stop<=max+1;stop++{a,b,c:=observe(originalCase,s,upper,stop);x,y,z:=observe(Case,s,upper,stop);if a!=x||b!=y||c!=z {t.Fatalf("input %x upper %v stop %d old %x/%d/%v new %x/%d/%v",s,upper,stop,a,b,c,x,y,z)};count++}}}
 t.Logf("%d exact return/panic/callback-count comparisons",count)
}
