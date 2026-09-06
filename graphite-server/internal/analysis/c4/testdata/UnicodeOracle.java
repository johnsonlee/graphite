// Small correctness oracle invoking unchanged main C4 helpers directly.
// Inputs and outputs are UTF-16 unit arrays so JSON cannot replace surrogates.
import io.johnsonlee.graphite.cli.c4.*;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

class UnicodeOracle {
    static List<Integer> units(String value) {
        if(value==null)return null;
        List<Integer> result=new ArrayList<>();for(int i=0;i<value.length();i++)result.add((int)value.charAt(i));return result;
    }
    public static void main(String[] args)throws Exception {
        List<String> values=new ArrayList<>(List.of("ordinary-name","fooBar","HTTP_client","\u00df","\u00dfeta","\ufb03","\ufb03le","\u01f3elta","\u01c5elta","\u0130","\u0130D","\u0130foo","Istanbul","\u039f\u03a3","\u039f\u03a3_A","\u039f\u03a31A","\u03a3","A\u03a3","A\u03a3\u0301","A\u03a3:2","\u00aabcd","\u00babcd","\u0131test","\u212aelvin","\u017fample","\u1c90test","\ua7d0test","\ud801\udc28abc","\ud801\udc28a","\ud801\udc28","\ud83d\ude00ab","\ud800name","\udc00name","A\ud800B","  -__-  ","\u0085","\u001c","\u00a0","\u2007","\u202f","\u200b","lib-1.0\n","lib-1.0\r","lib-1.0\r\n","lib-1.0\u0085","lib-1.0\u2028","lib-1.0\u2029","lib-1.0\n\n","lib-\u0661.0","foo\nbar","foo\rbar","com.\u0085.Example","com.\u001c.Example","com.\u00a0.Example","com.\ua7d0type.Example","com.\ud801\udc00type.Example"));
        Map<String,Function<String,String>> functions=new LinkedHashMap<>();
        functions.put("slug",C4SupportKt::slugify);
        functions.put("artifact",C4SupportKt::humanizeArtifactLabel);
        functions.put("subjectArtifact",C4SupportKt::humanizeSubjectArtifactLabel);
        functions.put("identifier",SystemBoundaryDetector.INSTANCE::humanizeIdentifier);
        functions.put("subjectBoundary",s->SubjectDetector.INSTANCE.inferName(s,null,null));
        functions.put("artifactBase",ExternalSystemClassifier.INSTANCE::artifactBaseName);
        functions.put("artifactKey",ExternalSystemClassifier.INSTANCE::artifactKey);
        functions.put("namespace",ExternalSystemClassifier.INSTANCE::namespaceGroup);
        functions.put("dominant",SystemBoundaryDetector.INSTANCE::dominantNamespace);
        functions.put("diagramId",C4RenderingPlanKt::diagramId);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var entry:functions.entrySet())for(String value:values) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("function",entry.getKey());row.put("input",units(value));row.put("output",units(entry.getValue().apply(value)));rows.add(row);
        }
        Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(rows)+"\n");
    }
}
