package com.harshil.movieticketbooking.user.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An account, either an administrator or a customer.
 * <p>
 * Only the BCrypt hash is ever stored; the plaintext password never reaches a
 * field, a log line or a DTO.
 */
@Entity
@Getter
@Builder
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class User extends BaseEntity {

    @Column(columnDefinition = "VARCHAR(100)", name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(columnDefinition = "VARCHAR(255)", name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(columnDefinition = "VARCHAR(100)", name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(columnDefinition = "VARCHAR(150)", name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(columnDefinition = "VARCHAR(20)", name = "phone_number", length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "role", nullable = false, length = 20)
    private Role role;

    @Column(columnDefinition = "BOOLEAN", name = "enabled", nullable = false)
    private boolean enabled;

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public void updateProfile(String fullName, String phoneNumber) {
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
