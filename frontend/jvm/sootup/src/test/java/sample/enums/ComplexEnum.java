package sample.enums;

/**
 * A complex enum with multiple constructor parameters and boxing.
 * Tests extractBoxedValue coverage for various wrapper types.
 */
public enum ComplexEnum {
    ALPHA(100, "alpha-label", true, 1.5),
    BETA(200, "beta-label", false, 2.5),
    GAMMA(300, "gamma-label", true, 3.5);

    private final int code;
    private final String label;
    private final boolean active;
    private final double weight;

    ComplexEnum(int code, String label, boolean active, double weight) {
        this.code = code;
        this.label = label;
        this.active = active;
        this.weight = weight;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }
    public boolean isActive() { return active; }
    public double getWeight() { return weight; }
}
