package sample.booleans;

/**
 * An enum whose constructor takes boolean arguments.
 * When the JVM compiles this, the boolean constants in {@code <clinit>} may be represented
 * as SootBooleanConstant (depending on SootUp's IR representation).
 */
public enum BooleanFieldEnum {
    ENABLED(true, "enabled-feature"),
    DISABLED(false, "disabled-feature");

    private final boolean active;
    private final String label;

    BooleanFieldEnum(boolean active, String label) {
        this.active = active;
        this.label = label;
    }

    public boolean isActive() { return active; }
    public String getLabel() { return label; }
}
