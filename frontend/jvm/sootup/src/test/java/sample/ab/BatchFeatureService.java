package sample.ab;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Service that demonstrates batch feature flag lookups using List parameters.
 * Used to test collection parameter constant tracking.
 */
public class BatchFeatureService {

    private final AbClient abClient = new AbClient();

    /**
     * Test case: Arrays.asList with integer constants
     */
    public Map<Integer, String> getBatchOptionsWithArraysAsList() {
        return abClient.getOptions(Arrays.asList(5001, 5002, 5003));
    }

    /**
     * Test case: List.of with integer constants (Java 9+)
     */
    public Map<Integer, String> getBatchOptionsWithListOf() {
        return abClient.getOptions(List.of(6001, 6002));
    }

    /**
     * Test case: Arrays.asList with enum constants
     */
    public Map<ExperimentId, String> getBatchEnumOptionsWithArraysAsList() {
        return abClient.getOptionsByEnum(Arrays.asList(
            ExperimentId.NEW_HOMEPAGE,
            ExperimentId.CHECKOUT_V2
        ));
    }

    /**
     * Test case: List.of with enum constants
     */
    public Map<ExperimentId, String> getBatchEnumOptionsWithListOf() {
        return abClient.getOptionsByEnum(List.of(
            ExperimentId.PREMIUM_FEATURES,
            ExperimentId.DARK_MODE
        ));
    }

    /**
     * Test case: mixed - some constants from variables
     */
    public Map<Integer, String> getBatchOptionsWithVariables() {
        int localId1 = 7001;
        int localId2 = 7002;
        return abClient.getOptions(Arrays.asList(localId1, localId2, 7003));
    }
}
