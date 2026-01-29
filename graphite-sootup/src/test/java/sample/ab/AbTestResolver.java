package sample.ab;

import java.util.Arrays;
import java.util.List;

/**
 * Demonstrates the static field indirect reference pattern for AB testing.
 *
 * This is a common pattern in real-world code where:
 * 1. An enum defines AB test IDs with integer values: AbKey.SIMPLE_TEST_ID(1234)
 * 2. A static field holds a list of these enums: static final List<AbKey> KEYS = ...
 * 3. The list is passed to the AB SDK: abClient.getOption(KEYS)
 *
 * The challenge is to trace from getOption(KEYS) back through the static field
 * to find the enum constants and their constructor argument values.
 */
public class AbTestResolver {

    private final AbClient abClient = new AbClient();

    // Static field holding list of AbKey enums - this is the indirect reference pattern
    private static final List<AbKey> SIMPLE_TEST_KEYS = Arrays.asList(AbKey.SIMPLE_TEST_ID);

    // Multiple keys in the list
    private static final List<AbKey> CHECKOUT_KEYS = Arrays.asList(
        AbKey.CHECKOUT_FLOW,
        AbKey.NEW_ONBOARDING
    );

    // Mixed pattern: some keys from static field, combined at call site
    private static final AbKey SINGLE_KEY = AbKey.SIMPLE_TEST_ID;

    /**
     * Pattern 1: Static field list passed directly to SDK method.
     * Expected to find: 1234 (from AbKey.SIMPLE_TEST_ID)
     */
    public boolean isSimpleTestEnabled() {
        return abClient.getOption(SIMPLE_TEST_KEYS).isTreatment();
    }

    /**
     * Pattern 2: Static field list with multiple enum constants.
     * Expected to find: 5678 (CHECKOUT_FLOW), 9999 (NEW_ONBOARDING)
     */
    public boolean isCheckoutEnabled() {
        return abClient.getOption(CHECKOUT_KEYS).isTreatment();
    }

    /**
     * Pattern 3: List created inline with static enum field reference.
     * Expected to find: 1234 (from SINGLE_KEY -> AbKey.SIMPLE_TEST_ID)
     */
    public boolean isSingleKeyEnabled() {
        return abClient.getOption(Arrays.asList(SINGLE_KEY)).isTreatment();
    }

    /**
     * Pattern 4: Direct enum reference in inline list (already supported).
     * Expected to find: 1234 (from AbKey.SIMPLE_TEST_ID)
     */
    public boolean isDirectEnumEnabled() {
        return abClient.getOption(Arrays.asList(AbKey.SIMPLE_TEST_ID)).isTreatment();
    }
}
