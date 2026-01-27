package sample.generics;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test class for generic field type analysis.
 */
public class GenericFieldService {

    // Generic fields
    private List<User> users;
    private Map<String, User> userMap;
    private Optional<User> currentUser;
    private List<List<String>> nestedList;

    // Response wrapper with generic fields
    public static class Response<T> {
        private T data;
        private List<String> errors;
        private Map<String, Object> metadata;

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void setErrors(List<String> errors) {
            this.errors = errors;
        }
    }

    // User class
    public static class User {
        private String name;
        private int age;

        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }
    }

    // Method returning generic type
    public Response<User> getUser(String id) {
        Response<User> response = new Response<>();
        response.setData(new User("test", 25));
        return response;
    }

    // Method returning list with generic
    public List<User> getAllUsers() {
        return users;
    }

    // Method returning map with generics
    public Map<String, User> getUserMap() {
        return userMap;
    }

    // Method returning nested generic
    public Response<List<User>> getUserList() {
        Response<List<User>> response = new Response<>();
        response.setData(users);
        return response;
    }
}
