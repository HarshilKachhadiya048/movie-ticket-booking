package com.harshil.movieticketbooking.security;

/**
 * Authorization expressions used by {@code @PreAuthorize}.
 * <p>
 * The expressions are strings evaluated at runtime, so a typo in one would not
 * fail to compile - it would fail to <em>authorize</em>, at runtime, possibly
 * in the permissive direction. Naming them once here means every handler
 * refers to the same constant and a typo is impossible.
 */
public final class SecurityExpressions {

    public static final String IS_CUSTOMER = "hasRole('CUSTOMER')";
    public static final String IS_ADMIN = "hasRole('ADMIN')";
    public static final String IS_AUTHENTICATED = "isAuthenticated()";

    private SecurityExpressions() {
    }
}
