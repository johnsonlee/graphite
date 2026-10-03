package fixture.frontend.java;

public enum FixtureMode {
    PRIMARY("primary", 17),
    SECONDARY("secondary", 23);

    private final String label;
    private final int code;

    FixtureMode(String label, int code) {
        this.label = label;
        this.code = code;
    }
}
