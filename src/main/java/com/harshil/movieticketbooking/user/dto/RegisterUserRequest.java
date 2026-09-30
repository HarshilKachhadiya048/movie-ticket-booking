package com.harshil.movieticketbooking.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Customer self-registration.
 * <p>
 * The only public endpoint in the API - everything else requires
 * authentication. The role is not a field: this endpoint always creates a
 * customer. Letting a caller name their own role on an unauthenticated
 * endpoint would be a privilege escalation, so administrators are created
 * through the admin API by an existing administrator.
 *
 * @param password never stored, never logged, never echoed back. Only its
 *                 BCrypt hash is persisted.
 */
public record RegisterUserRequest(
        @NotBlank(message = "username is required")
        @Size(min = 3, max = 100, message = "username must be between 3 and 100 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "username may only contain letters, digits, dot, underscore and hyphen")
        String username,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must not exceed 255 characters") String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must not exceed 150 characters") String fullName,

        @Size(max = 20, message = "phoneNumber must not exceed 20 characters") String phoneNumber) {
}
