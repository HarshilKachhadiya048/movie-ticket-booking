package com.harshil.movieticketbooking.user.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.security.AppUserDetails;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import com.harshil.movieticketbooking.user.dto.RegisterUserRequest;
import com.harshil.movieticketbooking.user.dto.UserResponse;
import com.harshil.movieticketbooking.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and "who am I".
 * <p>
 * No class-level {@code @RequestMapping}: every handler carries its full path
 * as a constant from {@link ApiEndpoints}, so each URL appears exactly once in
 * the codebase.
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** The only endpoint reachable without credentials. Always creates a customer. */
    @PostMapping(ApiEndpoints.Auth.REGISTER)
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterUserRequest request) {
        return userService.register(request);
    }

    /**
     * Echoes the authenticated principal. Useful for verifying Basic
     * credentials and for a client to discover its own role.
     */
    @GetMapping(ApiEndpoints.Auth.ME)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public UserResponse currentUser(@AuthenticationPrincipal AppUserDetails principal) {
        return userService.getUser(principal.userId());
    }
}
