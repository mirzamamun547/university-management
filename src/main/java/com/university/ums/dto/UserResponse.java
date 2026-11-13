package com.university.ums.dto;

import com.university.ums.model.AppUser;
import com.university.ums.model.Role;

/** Response body: deliberately has no password field. */
public record UserResponse(
        Long id,
        String username,
        String fullName,
        String email,
        Role role,
        String department,
        String studentNumber) {

    public static UserResponse from(AppUser u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(),
                u.getRole(), u.getDepartment(), u.getStudentNumber());
    }
}
