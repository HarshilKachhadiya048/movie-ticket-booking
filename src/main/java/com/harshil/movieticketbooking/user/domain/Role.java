package com.harshil.movieticketbooking.user.domain;

/**
 * The two roles in the system.
 * <p>
 * Stored as the bare name ({@code ADMIN}) and exposed to Spring Security with
 * the {@code ROLE_} prefix it expects, so {@code hasRole('ADMIN')} and
 * {@code ck_users_role} agree without either side hard-coding the other's
 * spelling.
 */
public enum Role {

    ADMIN,
    CUSTOMER;

    public static final String ROLE_PREFIX = "ROLE_";

    public String authority() {
        return ROLE_PREFIX + name();
    }
}
