package sample.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/**
 * Sample REST controller with Spring MVC annotations for testing endpoint extraction.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @GetMapping("/users/{id}")
    public Object getUser(@PathVariable String id) {
        return new UserResponse(id, "John Doe");
    }

    @GetMapping("/users")
    public Object listUsers() {
        return new UserResponse[] { new UserResponse("1", "John") };
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody UserRequest request) {
        return ResponseEntity.ok(new UserResponse("new-id", request.name));
    }

    @PutMapping("/users/{id}")
    public Object updateUser(@PathVariable String id, @RequestBody UserRequest request) {
        return new UserResponse(id, request.name);
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/orders/{orderId}", produces = "application/json")
    public OrderResponse getOrder(@PathVariable String orderId) {
        return new OrderResponse(orderId, "100.00");
    }

    public static class UserRequest {
        public String name;
        public String email;
    }

    public static class UserResponse {
        public String id;
        public String name;

        public UserResponse(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static class OrderResponse {
        public String orderId;
        public String amount;

        public OrderResponse(String orderId, String amount) {
            this.orderId = orderId;
            this.amount = amount;
        }
    }
}
