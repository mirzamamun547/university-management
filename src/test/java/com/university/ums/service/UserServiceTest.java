package com.university.ums.service;

import com.university.ums.dto.UserRequest;
import com.university.ums.dto.UserResponse;
import com.university.ums.exception.ConflictException;
import com.university.ums.model.AppUser;
import com.university.ums.model.Role;
import com.university.ums.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the business rules in {@link UserService}.
 * No Spring context and no database: the repository and the password encoder are mocks.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository repo;
    @Mock PasswordEncoder encoder;
    @InjectMocks UserService service;

    private static UserRequest request(String username, String password, String email,
                                       String department, String studentNumber) {
        return new UserRequest(username, password, "Test " + username, email, department, studentNumber);
    }

    private static AppUser user(long id, String username, String email, Role role) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName("Test " + username);
        u.setRole(role);
        return u;
    }

    @Test
    void create_hashesPasswordAndStoresRoleSpecificFields() {
        when(repo.existsByUsername("teach1")).thenReturn(false);
        when(repo.existsByEmail("teach1@uni.edu")).thenReturn(false);
        when(encoder.encode("secret123")).thenReturn("HASHED");
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse res = service.create(Role.TEACHER,
                request("teach1", "secret123", "teach1@uni.edu", "CSE", "should-be-ignored"));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(repo).save(saved.capture());
        assertEquals("HASHED", saved.getValue().getPassword());          // never the raw password
        assertEquals(Role.TEACHER, saved.getValue().getRole());
        assertEquals("CSE", saved.getValue().getDepartment());
        assertNull(saved.getValue().getStudentNumber());                  // not a student field
        assertEquals("teach1", res.username());
    }

    @Test
    void create_withTakenUsername_throwsConflictAndSavesNothing() {
        when(repo.existsByUsername("taken")).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.create(Role.STUDENT, request("taken", "secret123", "new@uni.edu", null, "2107001")));

        assertTrue(ex.getMessage().contains("taken"));
        verify(repo, never()).save(any());
    }

    @Test
    void update_withBlankPassword_keepsTheExistingHash() {
        AppUser existing = user(5L, "t1", "t1@uni.edu", Role.TEACHER);
        existing.setPassword("OLDHASH");
        when(repo.findByIdAndRole(5L, Role.TEACHER)).thenReturn(Optional.of(existing));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse res = service.update(Role.TEACHER, 5L,
                new UserRequest("t1", null, "New Name", "t1@uni.edu", "EEE", null), "admin");

        assertEquals("OLDHASH", existing.getPassword());
        verifyNoInteractions(encoder);                                    // nothing was re-hashed
        assertEquals("New Name", res.fullName());
        assertEquals("EEE", res.department());
    }

    @Test
    void update_ownUsernameChange_isRejected() {
        AppUser existing = user(5L, "t1", "t1@uni.edu", Role.TEACHER);
        when(repo.findByIdAndRole(5L, Role.TEACHER)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () ->
                service.update(Role.TEACHER, 5L,
                        new UserRequest("renamed", null, "X Y", "t1@uni.edu", null, null), "t1"));

        assertEquals("t1", existing.getUsername());
        verify(repo, never()).save(any());
    }

    @Test
    void delete_ownAccount_isRejected() {
        AppUser admin = user(1L, "admin", "admin@uni.edu", Role.ADMIN);
        when(repo.findByIdAndRole(1L, Role.ADMIN)).thenReturn(Optional.of(admin));

        assertThrows(ConflictException.class, () -> service.delete(Role.ADMIN, 1L, "admin"));

        verify(repo, never()).delete(any());
    }

    @Test
    void isOwner_isTrueOnlyForTheLoggedInUsersOwnId() {
        when(repo.findByUsername("t1")).thenReturn(Optional.of(user(5L, "t1", "t1@uni.edu", Role.TEACHER)));

        assertTrue(service.isOwner(5L, "t1"));
        assertFalse(service.isOwner(6L, "t1"));      // somebody else's id
        assertFalse(service.isOwner(5L, "ghost"));   // unknown user (mock returns Optional.empty)
    }
}
