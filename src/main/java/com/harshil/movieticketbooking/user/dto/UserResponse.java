package com.harshil.movieticketbooking.user.dto;

import com.harshil.movieticketbooking.user.domain.Role;
import com.harshil.movieticketbooking.user.domain.User;
import java.util.UUID;

/** A user account. The password hash is never part of this record. */
public record UserResponse(
        UUID userId,
        String username,
        String email,
        String fullName,
        String phoneNumber,
        Role role,
        boolean enabled) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getRole(),
                user.isEnabled());
    }
}
