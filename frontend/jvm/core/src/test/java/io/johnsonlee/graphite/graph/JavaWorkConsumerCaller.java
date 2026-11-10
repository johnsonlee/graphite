package io.johnsonlee.graphite.graph;

/** Exercises the JVM interface boundary used by Java storage consumers. */
public final class JavaWorkConsumerCaller {
    private JavaWorkConsumerCaller() {}

    public static void charge(GraphWorkBatchConsumer consumer) {
        GraphWorkConsumer single = consumer;
        single.consume();
        consumer.consume(2L);
    }
}
