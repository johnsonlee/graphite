package sample.enums;

/**
 * An enum whose constructor takes boxed wrapper types (Integer, Long).
 * Tests extractBoxedValue for Integer.valueOf and Long.valueOf in enum context.
 */
public enum BoxedArgEnum {
    ITEM_A(Integer.valueOf(1001), Long.valueOf(5000L)),
    ITEM_B(Integer.valueOf(2002), Long.valueOf(6000L));

    private final Integer intCode;
    private final Long longCode;

    BoxedArgEnum(Integer intCode, Long longCode) {
        this.intCode = intCode;
        this.longCode = longCode;
    }

    public Integer getIntCode() { return intCode; }
    public Long getLongCode() { return longCode; }
}
