package sample.enums;

/**
 * An enum with only name/ordinal (no custom constructor args).
 * Tests the edge case where enum args <= 2 in findEnumInitValues.
 */
public enum EmptyEnum {
    FIRST,
    SECOND,
    THIRD;
}
