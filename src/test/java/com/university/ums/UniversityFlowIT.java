package com.university.ums;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.ums.model.AppUser;
import com.university.ums.model.Role;
import com.university.ums.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests: the whole application (security filters, controllers, service, JPA)
 * talking to a real database.
 *
 * They run under Maven Failsafe ({@code mvn verify}), because the class name ends in "IT".
 * By default the database is the in-memory H2 from src/test/resources/application.properties.
 * In CI (and whenever you want) point them at PostgreSQL with environment variables:
 *
 *   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ums_test
 *   SPRING_DATASOURCE_USERNAME=ums
 *   SPRING_DATASOURCE_PASSWORD=ums_password
 *
 * Every test uses its own random usernames, so the tests do not depend on running order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UniversityFlowIT {

    private static final String PASSWORD = "secret123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;

    private static RequestPostProcessor admin() {
        return httpBasic("admin", "admin123");
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> payload(String username, String email, String fullName, String password) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("email", email);
        m.put("fullName", fullName);
        if (password != null) m.put("password", password);
        return m;
    }

    private JsonNode createAsAdmin(String path, Map<String, Object> fields) throws Exception {
        String res = mvc.perform(post(path).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fields)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    // ------------------------------------------------------------------------------------

    @Test
    void sessionLogin_me_logout_andWrongPassword() throws Exception {
        // wrong password -> 401, no session
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        // correct login -> 200, user JSON without a password, session created
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session, "login should create an HTTP session");

        // the session alone (no password) identifies the user
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));

        // logout ends the session; without it the API is closed again
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isOk());
        assertTrue(session.isInvalid(), "logout should invalidate the session");
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCreatesTeacher_teacherEditsOwnProfile_andIsKeptWithinHisOwnRecord() throws Exception {
        String username = unique("teach");
        String email = username + "@uni.edu";
        Map<String, Object> fields = payload(username, email, "Dr. First", PASSWORD);
        fields.put("department", "CSE");
        long id = createAsAdmin("/api/teachers", fields).get("id").asLong();

        // what really landed in the database: BCrypt hash, right role
        AppUser stored = users.findById(id).orElseThrow();
        assertNotEquals(PASSWORD, stored.getPassword());
        assertTrue(stored.getPassword().startsWith("$2"), "password must be a BCrypt hash");
        assertEquals(Role.TEACHER, stored.getRole());

        // the teacher edits their own profile (no password in the body = keep the old one)
        RequestPostProcessor teacher = httpBasic(username, PASSWORD);
        Map<String, Object> update = payload(username, email, "Dr. Updated", null);
        update.put("department", "EEE");
        mvc.perform(put("/api/teachers/" + id).with(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Dr. Updated"));

        AppUser after = users.findById(id).orElseThrow();
        assertEquals("Dr. Updated", after.getFullName());
        assertEquals("EEE", after.getDepartment());
        assertEquals(stored.getPassword(), after.getPassword());

        // ...but cannot reach anybody else's record, the lists, or other resources
        long otherId = createAsAdmin("/api/teachers",
                payload(unique("teach"), unique("mail") + "@uni.edu", "Other Teacher", PASSWORD)).get("id").asLong();
        mvc.perform(get("/api/teachers/" + otherId).with(teacher)).andExpect(status().isForbidden());
        mvc.perform(get("/api/teachers").with(teacher)).andExpect(status().isForbidden());
        mvc.perform(get("/api/students").with(teacher)).andExpect(status().isForbidden());
    }

    @Test
    void databaseEnforcesUniquenessAndDeleteRemovesTheRow() throws Exception {
        String username = unique("stud");
        String email = username + "@uni.edu";
        Map<String, Object> fields = payload(username, email, "Some Student", PASSWORD);
        fields.put("studentNumber", "2107001");
        long id = createAsAdmin("/api/students", fields).get("id").asLong();
        assertTrue(users.existsByUsername(username));

        // same username, other email -> 409
        mvc.perform(post("/api/students").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                payload(username, unique("mail") + "@uni.edu", "Copy", PASSWORD))))
                .andExpect(status().isConflict());
        // same email, other username -> 409
        mvc.perform(post("/api/students").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                payload(unique("stud"), email, "Copy", PASSWORD))))
                .andExpect(status().isConflict());

        // delete -> 204, then it is gone from the API and from the table
        mvc.perform(delete("/api/students/" + id).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/students/" + id).with(admin())).andExpect(status().isNotFound());
        assertFalse(users.existsByUsername(username));

        // an admin cannot delete their own account
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        mvc.perform(delete("/api/admins/" + adminId).with(admin())).andExpect(status().isConflict());
        assertTrue(users.existsByUsername("admin"));
    }
}
