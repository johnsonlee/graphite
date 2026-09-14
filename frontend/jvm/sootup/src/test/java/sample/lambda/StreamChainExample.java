package sample.lambda;

import java.util.List;
import java.util.stream.Collectors;

public class StreamChainExample {
    public static String transform(String s) { return s.toUpperCase(); }
    public static boolean isValid(String s) { return !s.isEmpty(); }

    public static List<String> processChain(List<String> items) {
        return items.stream()
            .filter(StreamChainExample::isValid)
            .map(StreamChainExample::transform)
            .collect(Collectors.toList());
    }
}
