package com.harshil.movieticketbooking.security;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.user.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication and URL-level authorization.
 * <p>
 * <b>HTTP Basic over BCrypt.</b> A minimal mechanism with real role-based
 * access control and no token lifecycle, which keeps the sample {@code curl}
 * commands short. OAuth or JWT is the upgrade path if this ever faces a
 * browser client.
 * <p>
 * <b>Stateless, CSRF disabled.</b> There is no browser session and no cookie,
 * so there is nothing for a cross-site request to ride on; every request
 * carries its own credentials. Disabling CSRF on a cookie-authenticated app
 * would be a real vulnerability, which is why it is called out rather than
 * silently switched off.
 * <p>
 * <b>Where the rules live.</b> The {@code /api/v1/admin/**} subtree is guarded
 * here, because it is a whole branch of the URL space and that is what URL
 * matching is good at. Individual customer operations are guarded with
 * {@code @PreAuthorize} on their handlers instead, so the rule sits next to
 * the code it protects. The two are not duplicated, so a rule cannot drift.
 * Ownership ("is this <em>your</em> booking") is a domain rule and is enforced
 * in the service layer, where it can report
 * {@code UNAUTHORIZED_BOOKING_ACCESS} rather than a bare 403.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_GET_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**"
    };

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * Strength 10 is BCrypt's default: enough work to make offline cracking
     * expensive without adding noticeable latency to every login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, ApiEndpoints.Auth.REGISTER).permitAll()
                        .requestMatchers(ApiEndpoints.ADMIN_PATTERN).hasRole(Role.ADMIN.name())
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }
}
