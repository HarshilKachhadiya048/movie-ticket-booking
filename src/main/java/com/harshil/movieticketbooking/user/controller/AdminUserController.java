package com.harshil.movieticketbooking.user.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.user.dto.CreateUserRequest;
import com.harshil.movieticketbooking.user.dto.UserResponse;
import com.harshil.movieticketbooking.user.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration.
 * <p>
 * This is the only route to an administrator account: public registration
 * always creates a customer, so granting admin requires an existing admin.
 */
@RestController
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping(ApiEndpoints.Admin.Users.ROOT)
    public List<UserResponse> listUsers() {
        return userService.listUsers();
    }

    @PostMapping(ApiEndpoints.Admin.Users.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }
}
