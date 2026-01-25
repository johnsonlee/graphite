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
}
