import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Every inherited JVM checks its actual heap, including Gradle workers and forks. */
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
        long actual = Runtime.getRuntime().maxMemory();
        Files.writeString(receipt, "START\t" + actual + "\t" + limit + "\n", StandardOpenOption.CREATE_NEW);
        if (actual > limit) {
            Files.writeString(receipt, "HEAP_LIMIT_EXCEEDED\n", StandardOpenOption.APPEND);
            Runtime.getRuntime().halt(78);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(receipt, "SHUTDOWN\n", StandardOpenOption.APPEND); }
            catch (Exception failure) { Runtime.getRuntime().halt(79); }
        }, "graphite-heap-receipt"));
    }
}
