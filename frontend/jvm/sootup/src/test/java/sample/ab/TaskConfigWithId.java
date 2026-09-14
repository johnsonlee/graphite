package sample.ab;

/**
 * Enum that has both a primitive constructor argument and an enum reference.
 * Tests mixed argument types: int + enum reference.
 */
public enum TaskConfigWithId {
    URGENT_TASK(100, Priority.HIGH),
    NORMAL_TASK(200, Priority.MEDIUM),
    DEFERRED_TASK(300, Priority.LOW);

    private final int id;
    private final Priority priority;

    TaskConfigWithId(int id, Priority priority) {
        this.id = id;
        this.priority = priority;
    }

    public int getId() {
        return id;
    }

    public Priority getPriority() {
        return priority;
    }
}
