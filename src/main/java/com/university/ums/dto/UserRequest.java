package com.university.ums.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for create (POST) and update (PUT).
 * password is required on create and optional on update (omit to keep the current one).
 */
public record UserRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @Size(min = 6, max = 100) String password,
        @NotBlank String fullName,
        @NotBlank @Email String email,
        String department,
        String studentNumber) {
}
