package sample.booleans;

/**
 * A service that uses boolean constants in method bodies.
 * This ensures the SootBooleanConstant branch in getOrCreateConstant is hit.
 */
public class BooleanConstantService {
    private boolean enabled;
    private boolean verbose;

    public BooleanConstantService() {
        this.enabled = true;
        this.verbose = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public String getStatus() {
        if (enabled) {
            return "active";
        }
        return "inactive";
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
