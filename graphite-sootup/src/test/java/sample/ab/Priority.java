package sample.ab;

/**
 * Simple enum representing priority levels.
 * Used as a constructor argument for other enums to test cross-enum references.
 */
public enum Priority {
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    private final int level;

    Priority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
