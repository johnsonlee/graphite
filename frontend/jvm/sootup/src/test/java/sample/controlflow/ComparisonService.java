package sample.controlflow;

/**
 * Test class exercising all JVM comparison operators in if-statements.
 * Used to ensure processControlFlow covers LT, GT, LE, GE branches.
 */
public class ComparisonService {

    /**
     * Uses less-than comparison.
     */
    public String checkLessThan(int value) {
        if (value < 10) {
            return "small";
        }
        return "big";
    }

    /**
     * Uses greater-than comparison.
     */
    public String checkGreaterThan(int value) {
        if (value > 100) {
            return "large";
        }
        return "normal";
    }

    /**
     * Uses less-than-or-equal comparison.
     */
    public String checkLessOrEqual(int value) {
        if (value <= 0) {
            return "non-positive";
        }
        return "positive";
    }

    /**
     * Uses greater-than-or-equal comparison.
     */
    public String checkGreaterOrEqual(int value) {
        if (value >= 50) {
            return "high";
        }
        return "low";
    }

    /**
     * Uses equality check with a constant.
     */
    public String checkEqual(int value) {
        if (value == 42) {
            return "answer";
        }
        return "other";
    }

    /**
     * Uses not-equal check.
     */
    public String checkNotEqual(int value) {
        if (value != 0) {
            return "non-zero";
        }
        return "zero";
    }

    /**
     * Multiple comparisons in a single method.
     */
    public String classify(int value) {
        if (value < 0) {
            return "negative";
        }
        if (value > 100) {
            return "over-100";
        }
        if (value == 50) {
            return "exactly-50";
        }
        return "between-0-and-100";
    }
}
