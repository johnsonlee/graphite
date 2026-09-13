package sample.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Test DTO for @JsonProperty annotation extraction.
 */
public class JacksonDTO {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("email_address")
    private String email;

    @JsonIgnore
    private String password;

    // Field without @JsonProperty - should use field name
    private int age;

    // Public field with @JsonProperty
    @JsonProperty("is_active")
    public boolean active;

    public JacksonDTO() {}

    public JacksonDTO(String userId, String userName, String email, int age) {
        this.userId = userId;
        this.userName = userName;
        this.email = email;
        this.age = age;
    }

    @JsonProperty("user_id")
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @JsonProperty("user_name")
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    @JsonProperty("email_address")
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    @JsonIgnore
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
