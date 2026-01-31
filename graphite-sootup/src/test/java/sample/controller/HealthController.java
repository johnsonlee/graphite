package sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller WITHOUT class-level {@code @RequestMapping}.
 * This ensures the extractMappingPath "return empty" branch is exercised
 * when no annotation ends with "Mapping" at the class level.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @GetMapping("/ready")
    public String ready() {
        return "ready";
    }
}
