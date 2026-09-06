import io.johnsonlee.graphite.cli.*;
public class GraphiteMetricsOracle {
 public static void main(String[] args) throws Exception {
  try (ServerPerformanceMetrics metrics = new ServerPerformanceMetrics()) {
   CypherPerformanceRecorder recorder = metrics.cypherRecorder(4);
   recorder.reject(); recorder.reject();
   for (CypherQueryOutcome outcome : CypherQueryOutcome.values()) { long start = recorder.start(); recorder.stop(start,outcome); }
   System.out.print(metrics.getRegistry().scrape());
  }
 }
}
