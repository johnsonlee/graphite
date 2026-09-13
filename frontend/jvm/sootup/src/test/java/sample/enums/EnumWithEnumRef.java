package sample.enums;

/**
 * An enum whose constructor takes another enum as an argument.
 * Tests extractValueFromArg with JFieldRef (enum constant reference pattern).
 */
public enum EnumWithEnumRef {
    LOW_ALPHA(Priority.LOW, "alpha-config"),
    HIGH_BETA(Priority.HIGH, "beta-config"),
    MED_GAMMA(Priority.MEDIUM, "gamma-config");

    private final Priority priority;
    private final String config;

    EnumWithEnumRef(Priority priority, String config) {
        this.priority = priority;
        this.config = config;
    }

    public Priority getPriority() { return priority; }
    public String getConfig() { return config; }

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH;
    }
}
