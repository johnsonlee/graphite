package sample.ab;

/**
 * Mock AB testing client that simulates real AB SDK APIs.
 *
 * Real-world AB SDKs typically have methods like:
 * - getOption(int experimentId)
 * - getOption(Enum experimentId)
 * - isEnabled(String featureKey)
 */
public class AbClient {

    /**
     * Get experiment variant by integer ID.
     */
    public String getOption(Integer experimentId) {
        // Mock implementation
        return "control";
    }

    /**
     * Get experiment variant by enum ID.
     */
    public String getOption(ExperimentId experimentId) {
        // Mock implementation
        return "control";
    }

    /**
     * Check if experiment is enabled.
     */
    public boolean isEnabled(int experimentId) {
        // Mock implementation
        return false;
    }

    /**
     * Get variants for multiple experiment IDs (List<Integer>).
     * Used to test collection parameter constant tracking.
     */
    public java.util.Map<Integer, String> getOptions(java.util.List<Integer> experimentIds) {
        // Mock implementation
        return java.util.Collections.emptyMap();
    }

    /**
     * Get variants for multiple experiment enums (List<ExperimentId>).
     * Used to test collection parameter constant tracking with enums.
     */
    public java.util.Map<ExperimentId, String> getOptionsByEnum(java.util.List<ExperimentId> experimentIds) {
        // Mock implementation
        return java.util.Collections.emptyMap();
    }
}
