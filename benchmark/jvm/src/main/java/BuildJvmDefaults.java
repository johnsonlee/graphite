import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Checks the effective configuration of the JVM that executes the frontend main. */
public final class BuildJvmDefaults {
    /** Independent capacity observation for the CLI's low-memory refusal check. */
    public static void main(String[] args) {
        if (args.length != 1 || !"memory-capacity".equals(args[0])) {
            throw new IllegalArgumentException("Expected memory-capacity");
        }
        com.sun.management.OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(
                com.sun.management.OperatingSystemMXBean.class);
        long bytes = os.getTotalMemorySize();
        if (bytes <= 0) throw new IllegalStateException("JVM could not determine memory capacity");
        System.out.println("{\"totalMemoryBytes\":" + bytes + "}");
    }

    public static void agentmain(String ignored, Instrumentation instrumentation) throws Exception {
        String mode = System.getenv("BUILD_JVM_DEFAULTS_CASE");
        long expectedHeap;
        if ("default".equals(mode)) expectedHeap = 8L << 30;
        else if ("explicit-small-heap".equals(mode)) expectedHeap = 2L << 30;
        else throw new IllegalStateException("Unknown build JVM check case: " + mode);

        HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        require(vm, "MaxHeapSize", Long.toString(expectedHeap));
        if ("default".equals(mode)) {
            require(vm, "UseG1GC", "true");
            require(vm, "UnlockExperimentalVMOptions", "true");
            require(vm, "G1MaxNewSizePercent", "30");
            require(vm, "MinHeapFreeRatio", "20");
            require(vm, "GCTimeRatio", "4");
        } else if (!ManagementFactory.getRuntimeMXBean().getInputArguments().equals(List.of("-Xmx2g"))) {
            throw new IllegalStateException("Explicit override must contain only -Xmx2g");
        }
        long heapMax = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        if (heapMax != expectedHeap) {
            throw new IllegalStateException("Effective heap max: expected " + expectedHeap + ", got " + heapMax);
        }
        String receipt = "{\"case\":\"" + mode + "\",\"maxHeapBytes\":" + heapMax;
        if ("default".equals(mode)) {
            receipt += ",\"useG1GC\":" + vm.getVMOption("UseG1GC").getValue()
                    + ",\"g1MaxNewSizePercent\":" + vm.getVMOption("G1MaxNewSizePercent").getValue()
                    + ",\"minHeapFreeRatio\":" + vm.getVMOption("MinHeapFreeRatio").getValue()
                    + ",\"gcTimeRatio\":" + vm.getVMOption("GCTimeRatio").getValue();
        }
        receipt += "}\n";
        Files.writeString(Path.of(System.getenv("BUILD_JVM_DEFAULTS_RECEIPT")), receipt,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void require(HotSpotDiagnosticMXBean vm, String name, String expected) {
        String actual = vm.getVMOption(name).getValue();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Effective JVM " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
