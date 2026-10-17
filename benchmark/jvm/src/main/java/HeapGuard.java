import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Checks actual heap for the launcher and every inherited JVM, including JMH probes/forks.
 * The controller fixes G1GC as well as -Xmx, so the effective heap maximum is comparable across arms.
 * Hosted -XX:+ExitOnOutOfMemoryError is intentionally stricter than production paths that
 * catch an OOM: any OOM attempt fails this gate, even when production could recover.
 */
public final class HeapGuard {
    public static void premain(String args, Instrumentation ignored) throws Exception {
        String[] fields = args.split(",", 2);
        long limit = switch (fields[0]) {
            case "jar" -> 4L * 1024 * 1024 * 1024;
            case "apk" -> 8L * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException("unknown workload heap policy");
        };
        Path directory = Path.of(fields[1]);
        Files.createDirectories(directory);
        Path receipt = directory.resolve(ProcessHandle.current().pid() + "-" + System.nanoTime() + ".receipt");
        long actual = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        Files.writeString(receipt, "START\t" + actual + "\t" + limit + "\n", StandardOpenOption.CREATE_NEW);
        if (actual <= 0 || actual > limit) {
            Files.writeString(receipt, actual <= 0 ? "HEAP_LIMIT_INVALID\n" : "HEAP_LIMIT_EXCEEDED\n", StandardOpenOption.APPEND);
            Runtime.getRuntime().halt(78);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(receipt, "SHUTDOWN\n", StandardOpenOption.APPEND); }
            catch (Exception failure) { Runtime.getRuntime().halt(79); }
        }, "graphite-heap-receipt"));
    }
}
