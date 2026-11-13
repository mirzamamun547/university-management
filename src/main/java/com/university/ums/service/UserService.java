package com.university.ums.service;

import com.university.ums.dto.UserRequest;
import com.university.ums.dto.UserResponse;
import com.university.ums.exception.ConflictException;
import com.university.ums.exception.NotFoundException;
import com.university.ums.model.AppUser;
import com.university.ums.model.Role;
import com.university.ums.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll(Role role) {
        return repo.findByRole(role).stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findOne(Role role, Long id) {
        return UserResponse.from(load(role, id));
    }

    public UserResponse create(Role role, UserRequest req) {
        if (req.password() == null || req.password().isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        if (repo.existsByUsername(req.username())) {
            throw new ConflictException("Username already taken: " + req.username());
        }
        if (repo.existsByEmail(req.email())) {
            throw new ConflictException("Email already in use: " + req.email());
        }
        AppUser u = new AppUser();
        u.setRole(role);
        u.setPassword(encoder.encode(req.password()));
        apply(u, req);
        return UserResponse.from(repo.save(u));
    }

    /** True if the record with this id belongs to the logged-in user. */
    @Transactional(readOnly = true)
    public boolean isOwner(Long id, String currentUsername) {
        return repo.findByUsername(currentUsername)
                .map(u -> u.getId().equals(id))
                .orElse(false);
    }

    public UserResponse update(Role role, Long id, UserRequest req, String currentUsername) {
        AppUser u = load(role, id);
        // The login session is tied to the username, so nobody may rename their own account.
        if (u.getUsername().equals(currentUsername) && !u.getUsername().equals(req.username())) {
            throw new IllegalArgumentException("You cannot change your own username");
        }
        if (!u.getUsername().equals(req.username()) && repo.existsByUsername(req.username())) {
            throw new ConflictException("Username already taken: " + req.username());
        }
        if (!u.getEmail().equals(req.email()) && repo.existsByEmail(req.email())) {
            throw new ConflictException("Email already in use: " + req.email());
        }
        if (req.password() != null && !req.password().isBlank()) {
            u.setPassword(encoder.encode(req.password()));
        }
        apply(u, req);
        return UserResponse.from(repo.save(u));
    }

    public void delete(Role role, Long id, String currentUsername) {
        AppUser u = load(role, id);
        if (u.getUsername().equals(currentUsername)) {
            throw new ConflictException("You cannot delete your own account");
        }
        repo.delete(u);
    }

    private AppUser load(Role role, Long id) {
        return repo.findByIdAndRole(id, role)
                .orElseThrow(() -> new NotFoundException(role + " not found with id " + id));
    }

    /** Copies the editable fields; role-specific fields are only kept for the matching role. */
    private void apply(AppUser u, UserRequest req) {
        u.setUsername(req.username());
        u.setFullName(req.fullName());
        u.setEmail(req.email());
        u.setDepartment(u.getRole() == Role.TEACHER ? req.department() : null);
        u.setStudentNumber(u.getRole() == Role.STUDENT ? req.studentNumber() : null);
    }
}
