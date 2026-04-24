package sample.resources;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

public class SpringResourceConfig {
    @Value("${feature.mode}")
    private String featureMode;

    private final Environment environment;

    public SpringResourceConfig(Environment environment) {
        this.environment = environment;
    }

    public String serverPort() {
        return environment.getProperty("server.port");
    }

    public String featureMode() {
        return featureMode;
    }
}
