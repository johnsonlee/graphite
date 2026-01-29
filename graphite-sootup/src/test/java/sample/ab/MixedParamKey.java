package sample.ab;

/**
 * Enum with various constant types for constructor parameters.
 * Tests that Float, Double, Boolean, and Long constants are correctly extracted.
 */
public enum MixedParamKey {
    FLOAT_KEY(1.5f),
    DOUBLE_KEY(2.718),
    BOOL_KEY(true),
    LONG_KEY(9999999999L);

    private final Object value;

    MixedParamKey(float value) {
        this.value = value;
    }

    MixedParamKey(double value) {
        this.value = value;
    }

    MixedParamKey(boolean value) {
        this.value = value;
    }

    MixedParamKey(long value) {
        this.value = value;
    }

    public Object getValue() {
        return value;
    }
}
