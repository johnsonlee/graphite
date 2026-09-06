// Correctness fixture only. Run with the frozen main 4e328b0 explore jar:
// java -Xmx128m -cp <main-jar>:<webgraph-3.6.12.jar> GenerateCompression.java <output-directory>
// Main jar comes first; the unminimized WebGraph jar supplies only the mutable
// fixture builder omitted by main's shaded jar.
import it.unimi.dsi.webgraph.*;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;

class GenerateCompression {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        ArrayListMutableGraph mutable = new ArrayListMutableGraph(512);
        for (int row = 0; row < 7; row++) {
            for (int to = 100; to < 300; to++) {
                if (row == 0 || to % 2 == 0 || to % 7 == row) mutable.addArc(row, to);
            }
            mutable.addArc(row, 511);
        }
        // A reference distance beyond 62; Golomb residual quotients can also
        // exceed 62. These are valid unary values, not gamma bit lengths.
        for (int row : new int[]{20, 100}) {
            for (int to = 310; to < 510; to += 2) mutable.addArc(row, to);
        }
        mutable.addArc(511, 0);
        mutable.addArc(511, 2);
        mutable.addArc(511, 300);
        ImmutableGraph input = mutable.immutableView();
        String[] selections = {
            "", "OUTDEGREES_GAMMA", "OUTDEGREES_DELTA", "BLOCKS_GAMMA", "BLOCKS_DELTA",
            "RESIDUALS_GAMMA", "RESIDUALS_DELTA", "RESIDUALS_ZETA", "RESIDUALS_GOLOMB", "RESIDUALS_NIBBLE",
            "REFERENCES_GAMMA", "REFERENCES_DELTA", "REFERENCES_UNARY",
            "BLOCK_COUNT_GAMMA", "BLOCK_COUNT_DELTA", "BLOCK_COUNT_UNARY", "OFFSETS_GAMMA", "OFFSETS_DELTA",
            "OUTDEGREES_DELTA | BLOCKS_DELTA | RESIDUALS_NIBBLE | REFERENCES_DELTA | BLOCK_COUNT_UNARY | OFFSETS_DELTA",
            "OUTDEGREES_DELTA | BLOCKS_DELTA | RESIDUALS_GOLOMB | REFERENCES_GAMMA | BLOCK_COUNT_DELTA | OFFSETS_DELTA"
        };
        List<Map<String,Object>> cases = new ArrayList<>();
        for (int index = 0; index < selections.length + 8; index++) {
            String selection = index < selections.length ? selections[index] : "";
            if (index>=25) selection="RESIDUALS_GOLOMB";
            int window = index == 20 ? 0 : index == 21 ? 128 : 7;
            int interval = index == 22 ? 0 : 4;
            int parameter = index == 23 ? 1 : index == 24 ? 5 : 3;
            if (index>=25) parameter=index==25 ? 0 : index==26 ? 1 : 257;
            ImmutableGraph variantInput=input;
            if(index==25){ArrayListMutableGraph singleton=new ArrayListMutableGraph(1);singleton.addArc(0,0);variantInput=singleton.immutableView();}
            int flags = 0;
            if (!selection.isEmpty()) for (String name : selection.split("\\|")) {
                flags |= BVGraph.class.getField(name.trim()).getInt(null);
            }
            String name = String.format("case-%02d", index);
            Path dir = root.resolve(name);
            Files.createDirectories(dir);
            BVGraph.store(variantInput, dir.resolve("forward").toString(), window, 3, interval, parameter, flags, 1);
            // BVGraph.store writes zetak only for ZETA, but its Golomb reader
            // also uses that property. Declare nondefault Golomb moduli for
            // the independently written bitstream before loading it back.
            if(index>=25) Files.writeString(dir.resolve("forward.properties"),"zetak="+parameter+"\n",StandardOpenOption.APPEND);
            BVGraph loaded = BVGraph.load(dir.resolve("forward").toString());
            List<List<Integer>> rows = new ArrayList<>();
            for (int from = 0; from < loaded.numNodes(); from++) {
                int[] actual = loaded.successorArray(from), expected = variantInput.successorArray(from);
                int degree = loaded.outdegree(from);
                if (degree != variantInput.outdegree(from)) throw new AssertionError("degree " + from);
                List<Integer> row = new ArrayList<>();
                row.add(from);
                for (int i = 0; i < degree; i++) {
                    if (actual[i] != expected[i]) throw new AssertionError("arc " + from + " " + i);
                    row.add(actual[i]);
                }
                if (degree != 0) rows.add(row);
            }
            Map<String,Object> record = new LinkedHashMap<>();
            record.put("name", name); record.put("flags", selection);
            record.put("window", window); record.put("minInterval", interval); record.put("parameter", parameter);
            record.put("nodes", loaded.numNodes()); record.put("arcs", loaded.numArcs()); record.put("rows", rows);
            cases.add(record);
        }
        Files.writeString(root.resolve("expected.json"), new GsonBuilder().setPrettyPrinting().create().toJson(cases) + "\n");
    }
}
