package com.harshil.movieticketbooking.user.service;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.user.domain.Role;
import com.harshil.movieticketbooking.user.domain.User;
import com.harshil.movieticketbooking.user.dto.CreateUserRequest;
import com.harshil.movieticketbooking.user.dto.RegisterUserRequest;
import com.harshil.movieticketbooking.user.dto.UserResponse;
import com.harshil.movieticketbooking.user.repository.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User accounts.
 * <p>
 * Passwords arrive as plaintext on the request and are hashed with BCrypt
 * before anything else happens to them. The plaintext is never assigned to a
 * field, never logged and never returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** Public self-registration. Always creates a customer, never an admin. */
    @Transactional
    public UserResponse register(RegisterUserRequest request) {
        return UserResponse.from(create(
                request.username(),
                request.email(),
                request.password(),
                request.fullName(),
                request.phoneNumber(),
                Role.CUSTOMER));
    }

    /** Admin-only creation, which may grant either role. */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        return UserResponse.from(create(
                request.username(),
                request.email(),
                request.password(),
                request.fullName(),
                request.phoneNumber(),
                request.role()));
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        return UserResponse.from(userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.USER_NOT_FOUND, "User %s does not exist".formatted(userId))));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(User::getUsername))
                .map(UserResponse::from)
                .toList();
    }

    private User create(
            String username,
            String email,
            String rawPassword,
            String fullName,
            String phoneNumber,
            Role role) {
        String normalizedUsername = username.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        // Checked here for a clear 409; the unique constraints on users are
        // the actual guarantee if two registrations race.
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new DomainException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DomainException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = userRepository.save(User.builder()
                .username(normalizedUsername)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .fullName(fullName.trim())
                .phoneNumber(phoneNumber)
                .role(role)
                .enabled(true)
                .build());

        log.info("Created {} account for username '{}'", role, normalizedUsername);
        return user;
    }
}
