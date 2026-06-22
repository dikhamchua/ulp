package com.ulp.shared.config;

import com.ulp.auth.Roles;
import com.ulp.auth.service.CustomOidcUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Cau hinh bao mat cho ULP (Sprint 1).
 *
 * <ul>
 *   <li>Form login (luon on).</li>
 *   <li>Google OAuth2 — chi enable khi {@code google.client-id} duoc cau hinh.</li>
 *   <li>Cong khai: static assets, login, forgot/reset-password, uploads.</li>
 *   <li>Mat khau ma hoa BCrypt.</li>
 *   <li>CSRF bat (mac dinh) — form Thymeleaf tu chen token.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private CustomOidcUserService customOidcUserService;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty("spring.security.oauth2.client.registration.google.client-id")
    public AuthenticationFailureHandler oauthFailureHandler() {
        return (request, response, exception) -> {
            response.sendRedirect("/login?error=oauth_unregistered");
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/fonts/**", "/favicon.ico").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/login", "/forgot-password", "/reset-password").permitAll()
                        .requestMatchers("/lecturer/**").hasAnyRole(Roles.LECTURER, Roles.HEAD, Roles.ADMIN)
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        // OAuth2: only wired when CustomOidcUserService is available (Google client-id configured)
        if (customOidcUserService != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .userInfoEndpoint(ui -> ui.oidcUserService(customOidcUserService))
                    .failureHandler(oauthFailureHandler())
                    .defaultSuccessUrl("/", true)
            );
        }

        return http.build();
    }
}
