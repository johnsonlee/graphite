package sample.boxing;

/**
 * Test class exercising boxing/unboxing of various primitive types.
 * Used to test isBoxingMethod and isUnboxingMethod coverage.
 */
public class BoxingService {

    /**
     * Uses Integer boxing and unboxing.
     */
    public int roundTripInt(int value) {
        Integer boxed = Integer.valueOf(value);
        return boxed.intValue();
    }

    /**
     * Uses Long boxing and unboxing.
     */
    public long roundTripLong(long value) {
        Long boxed = Long.valueOf(value);
        return boxed.longValue();
    }

    /**
     * Uses Short boxing and unboxing.
     */
    public short roundTripShort(short value) {
        Short boxed = Short.valueOf(value);
        return boxed.shortValue();
    }

    /**
     * Uses Byte boxing and unboxing.
     */
    public byte roundTripByte(byte value) {
        Byte boxed = Byte.valueOf(value);
        return boxed.byteValue();
    }

    /**
     * Uses Float boxing and unboxing.
     */
    public float roundTripFloat(float value) {
        Float boxed = Float.valueOf(value);
        return boxed.floatValue();
    }

    /**
     * Uses Double boxing and unboxing.
     */
    public double roundTripDouble(double value) {
        Double boxed = Double.valueOf(value);
        return boxed.doubleValue();
    }

    /**
     * Uses Boolean boxing and unboxing.
     */
    public boolean roundTripBoolean(boolean value) {
        Boolean boxed = Boolean.valueOf(value);
        return boxed.booleanValue();
    }

    /**
     * Uses Character boxing and unboxing.
     */
    public char roundTripChar(char value) {
        Character boxed = Character.valueOf(value);
        return boxed.charValue();
    }

    /**
     * Method that passes a boxed value as argument.
     */
    public void passBoxedInteger() {
        Integer boxed = Integer.valueOf(42);
        consumeInteger(boxed);
    }

    private void consumeInteger(Integer value) {
        // no-op
    }
}
