package sample.jackson;

import org.springframework.web.bind.annotation.*;

/**
 * Test controller for @JsonProperty annotation extraction.
 */
@RestController
@RequestMapping("/api/jackson")
public class JacksonController {

    @GetMapping("/user/{id}")
    public JacksonDTO getUser(@PathVariable String id) {
        JacksonDTO dto = new JacksonDTO();
        dto.setUserId(id);
        dto.setUserName("John Doe");
        dto.setEmail("john@example.com");
        dto.setAge(30);
        dto.setActive(true);
        return dto;
    }

    @GetMapping("/user-direct/{id}")
    public JacksonDTO getUserDirect(@PathVariable String id) {
        // Using constructor
        JacksonDTO dto = new JacksonDTO(id, "Jane Doe", "jane@example.com", 25);
        dto.active = true;
        return dto;
    }
}
