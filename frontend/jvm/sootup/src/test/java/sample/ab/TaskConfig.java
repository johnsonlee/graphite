package sample.ab;

/**
 * Enum that references another enum (Priority) in its constructor.
 * Tests the cross-enum reference pattern where one enum's value
 * is another enum constant.
 *
 * Bytecode pattern in {@code <clinit>}:
 * <pre>
 *   $stack0 = new TaskConfig
 *   $stack1 = getstatic Priority.HIGH [Priority]
 *   invokespecial TaskConfig.&lt;init&gt;($stack0, "URGENT", 0, $stack1)
 *   putstatic TaskConfig.URGENT [TaskConfig]
 * </pre>
 */
public enum TaskConfig {
    URGENT(Priority.HIGH),
    NORMAL(Priority.MEDIUM),
    DEFERRED(Priority.LOW);

    private final Priority priority;

    TaskConfig(Priority priority) {
        this.priority = priority;
    }

    public Priority getPriority() {
        return priority;
    }
}
