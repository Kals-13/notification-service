package com.example.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Development-only security setup: in-memory users and HTTP Basic auth over a stateless
 * REST API. Production would replace this with an external identity provider (OAuth2/JWT).
 * Tenant-level data isolation is enforced separately at the controller/service layer
 * (verifying tenantId ownership) — role checks here only gate which endpoints a caller
 * may reach at all.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/tenants").hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/tenants/{id}").hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/tenants/{id}/limits").hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/tenants/{id}").hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/templates").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/templates").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/templates/{id}").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/templates/{id}").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/notifications/send").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/notifications/**").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/notifications/{id}/retry").hasRole("TENANT_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/notifications/{id}").hasRole("TENANT_ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails platformAdmin = User.withUsername("admin@platform.com")
                .password(passwordEncoder.encode("admin123"))
                .roles("PLATFORM_ADMIN")
                .build();

        UserDetails tenantAdmin1 = User.withUsername("tenant1@example.com")
                .password(passwordEncoder.encode("tenant123"))
                .roles("TENANT_ADMIN")
                .build();

        UserDetails tenantAdmin2 = User.withUsername("tenant2@example.com")
                .password(passwordEncoder.encode("tenant123"))
                .roles("TENANT_ADMIN")
                .build();

        return new InMemoryUserDetailsManager(platformAdmin, tenantAdmin1, tenantAdmin2);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
