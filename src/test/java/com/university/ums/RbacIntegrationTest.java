package com.university.ums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RbacIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private RequestPostProcessor admin() { return httpBasic("admin", "admin123"); }

    private String body(String username, String email) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", "secret123",
                "fullName", "Test " + username, "email", email));
    }

    private void create(String path, String username) throws Exception {
        mvc.perform(post(path).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(username, username + "@u.edu"))).andExpect(status().isCreated());
    }

    @Test
    void webUiIsPublicButApiIsNot() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/teachers")).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get("/api/students")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanCrudAllThreeResources() throws Exception {
        for (String r : new String[]{"admins", "teachers", "students"}) {
            String path = "/api/" + r;
            String res = mvc.perform(post(path).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body("crud_" + r, "crud_" + r + "@u.edu")))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            long id = json.readTree(res).get("id").asLong();
            mvc.perform(get(path + "/" + id).with(admin())).andExpect(status().isOk());
            mvc.perform(put(path + "/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body("crud_" + r, "crud_" + r + "@u.edu").replace("Test", "Renamed")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Renamed crud_" + r));
            mvc.perform(delete(path + "/" + id).with(admin())).andExpect(status().isNoContent());
            mvc.perform(get(path + "/" + id).with(admin())).andExpect(status().isNotFound());
        }
    }

    private long createAndGetId(String path, String username) throws Exception {
        String res = mvc.perform(post(path).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, username + "@u.edu")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    /** Teachers and students: own profile only (GET/PUT), nothing else. */
    private void assertOnlyOwnProfile(String path, String me, String other, String forbiddenA, String forbiddenB)
            throws Exception {
        long myId = createAndGetId(path, me);
        long otherId = createAndGetId(path, other);
        RequestPostProcessor u = httpBasic(me, "secret123");

        // own profile: read + update works
        mvc.perform(get(path + "/" + myId).with(u)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(me));
        mvc.perform(put(path + "/" + myId).with(u).contentType(MediaType.APPLICATION_JSON)
                        .content(body(me, me + "@u.edu").replace("Test", "Mine")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Mine " + me));
        // cannot rename own account
        mvc.perform(put(path + "/" + myId).with(u).contentType(MediaType.APPLICATION_JSON)
                .content(body(me + "x", me + "@u.edu"))).andExpect(status().isBadRequest());

        // nobody else's profile, no listing, no create, no delete (not even their own)
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(u)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(u).contentType(MediaType.APPLICATION_JSON)
                .content(body(me + "2", me + "2@u.edu"))).andExpect(status().isForbidden());
        mvc.perform(get(path + "/" + otherId).with(u)).andExpect(status().isForbidden());
        mvc.perform(put(path + "/" + otherId).with(u).contentType(MediaType.APPLICATION_JSON)
                .content(body(other, other + "@u.edu"))).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + otherId).with(u)).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + myId).with(u)).andExpect(status().isForbidden());

        // other resources are off limits too
        mvc.perform(get(forbiddenA).with(u)).andExpect(status().isForbidden());
        mvc.perform(get(forbiddenB).with(u)).andExpect(status().isForbidden());
    }

    @Test
    void teacherCanOnlyManageOwnProfile() throws Exception {
        assertOnlyOwnProfile("/api/teachers", "teach1", "teach2", "/api/students", "/api/admins");
    }

    @Test
    void studentCanOnlyManageOwnProfile() throws Exception {
        assertOnlyOwnProfile("/api/students", "stud1", "stud2", "/api/teachers", "/api/admins");
    }

    @Test
    void validationAndConflicts() throws Exception {
        mvc.perform(post("/api/students").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\"}")).andExpect(status().isBadRequest());
        create("/api/students", "dup");
        mvc.perform(post("/api/students").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("dup", "other@u.edu"))).andExpect(status().isConflict());
        mvc.perform(delete("/api/admins/1").with(admin())).andExpect(status().isConflict()); // self-delete
    }
}
