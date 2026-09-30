package com.harshil.movieticketbooking.security;

import com.harshil.movieticketbooking.user.domain.Role;
import com.harshil.movieticketbooking.user.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal.
 * <p>
 * Carries the user's id, email and display name alongside the credentials so
 * that request handling - ownership checks, addressing a notification - never
 * has to reload the user row just to answer "who is this". It is an immutable
 * snapshot taken at authentication time.
 */
public record AppUserDetails(
        UUID userId,
        String username,
        String passwordHash,
        String email,
        String fullName,
        Role role,
        boolean active) implements UserDetails {

    public static AppUserDetails from(User user) {
        return new AppUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isEnabled());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    /** Never let the password hash leak through logging or debugging output. */
    @Override
    public String toString() {
        return "AppUserDetails(userId=%s, username=%s, role=%s)".formatted(userId, username, role);
    }
}
