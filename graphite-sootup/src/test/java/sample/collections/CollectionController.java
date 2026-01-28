package sample.collections;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller that returns DTOs with multiple List fields.
 * Used to test that find-endpoints discovers all List fields.
 */
@RestController
public class CollectionController {

    @GetMapping("/api/multiple-lists")
    public MultipleListFieldsDTO getMultipleLists() {
        MultipleListFieldsDTO dto = new MultipleListFieldsDTO();
        // All fields should be discovered even without explicit assignment here
        return dto;
    }
}
