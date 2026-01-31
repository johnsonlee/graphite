package sample.enums;

/**
 * An enum whose constructor takes Short, Byte, Float, Double, Boolean, Character wrapper types.
 * Tests extractBoxedValue for additional boxing methods.
 */
public enum MoreBoxedEnum {
    ITEM_X(Short.valueOf((short) 10), Byte.valueOf((byte) 1), Float.valueOf(1.5f),
           Double.valueOf(2.5), Boolean.valueOf(true), Character.valueOf('A')),
    ITEM_Y(Short.valueOf((short) 20), Byte.valueOf((byte) 2), Float.valueOf(3.5f),
           Double.valueOf(4.5), Boolean.valueOf(false), Character.valueOf('B'));

    private final Short shortVal;
    private final Byte byteVal;
    private final Float floatVal;
    private final Double doubleVal;
    private final Boolean boolVal;
    private final Character charVal;

    MoreBoxedEnum(Short shortVal, Byte byteVal, Float floatVal,
                  Double doubleVal, Boolean boolVal, Character charVal) {
        this.shortVal = shortVal;
        this.byteVal = byteVal;
        this.floatVal = floatVal;
        this.doubleVal = doubleVal;
        this.boolVal = boolVal;
        this.charVal = charVal;
    }

    public Short getShortVal() { return shortVal; }
    public Byte getByteVal() { return byteVal; }
    public Float getFloatVal() { return floatVal; }
    public Double getDoubleVal() { return doubleVal; }
    public Boolean getBoolVal() { return boolVal; }
    public Character getCharVal() { return charVal; }
}
