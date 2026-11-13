package com.university.ums.controller;

import com.university.ums.dto.UserRequest;
import com.university.ums.dto.UserResponse;
import com.university.ums.model.Role;
import com.university.ums.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Shared CRUD endpoints. Each subclass fixes the role and the base path:
 *   GET    /{base}        list
 *   GET    /{base}/{id}   read one
 *   POST   /{base}        create  -> 201 + Location
 *   PUT    /{base}/{id}   update
 *   DELETE /{base}/{id}   delete  -> 204
 * Who may call them is decided in SecurityConfig (admin: all; teacher/student: GET and PUT of
 * their own record only, enforced by requireAdminOrSelf below).
 */
public abstract class AbstractUserController {

    private final UserService service;
    private final Role role;

    protected AbstractUserController(UserService service, Role role) {
        this.service = service;
        this.role = role;
    }

    @GetMapping
    public List<UserResponse> list() {
        return service.findAll(role);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id, Authentication auth) {
        requireAdminOrSelf(id, auth);
        return service.findOne(role, id);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserResponse created = service.create(role, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserRequest request,
                               Authentication auth) {
        requireAdminOrSelf(id, auth);
        return service.update(role, id, request, auth.getName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        service.delete(role, id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Admins may touch any record; everyone else only their own.
     * AccessDeniedException is turned into a JSON 403 by the security filter chain.
     */
    private void requireAdminOrSelf(Long id, Authentication auth) {
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !service.isOwner(id, auth.getName())) {
            throw new AccessDeniedException("You can only access your own profile");
        }
    }
}
