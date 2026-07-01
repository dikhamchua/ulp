package com.ulp.config;

import com.ulp.security.Roles;
import com.ulp.security.CustomOidcUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security configuration for the ULP application.
 *
 * <ul>
 *   <li>Form login — always active.</li>
 *   <li>Google OAuth2 — always wired into the filter chain. Activation is
 *       driven at runtime by {@code DbClientRegistrationRepository}, which
 *       returns {@code null} when no client id has been saved in
 *       {@code system_settings}. Spring Security then responds with HTTP 404
 *       to {@code /oauth2/authorization/google} and the login button is
 *       hidden in the UI via {@code @oauthSettingsService.isGoogleEnabled()}.</li>
 *   <li>Public endpoints: static assets, login, forgot/reset-password, and uploaded files.</li>
 *   <li>Passwords hashed with BCrypt.</li>
 *   <li>CSRF protection is enabled by default — Thymeleaf forms inject the token automatically.</li>
 * </ul>
 *
 * <p>Because a custom {@link ClientRegistrationRepository} bean is provided,
 * Spring Boot's {@code OAuth2ClientAutoConfiguration} backs off — we therefore
 * also expose {@link OAuth2AuthorizedClientService} and
 * {@link OAuth2AuthorizedClientRepository} beans here as required by the
 * official Spring Security override pattern.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomOidcUserService customOidcUserService;

    public SecurityConfig(CustomOidcUserService customOidcUserService) {
        this.customOidcUserService = customOidcUserService;
    }

    /**
     * Provides a {@link BCryptPasswordEncoder} as the application-wide {@link PasswordEncoder}.
     *
     * @return a BCrypt password encoder bean
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Provides an {@link AuthenticationFailureHandler} for OAuth2 login failures.
     *
     * <p>Redirects the user to {@code /login?error=oauth_unregistered} when an
     * OAuth2 authentication attempt fails (e.g. the Google account is not yet
     * registered in ULP, or Google sign-in is currently disabled in the admin
     * panel).</p>
     *
     * @return an {@link AuthenticationFailureHandler} that redirects to the login error page
     */
    @Bean
    public AuthenticationFailureHandler oauthFailureHandler() {
        return (request, response, exception) ->
                response.sendRedirect("/login?error=oauth_unregistered");
    }

    /**
     * In-memory store for {@link org.springframework.security.oauth2.client.OAuth2AuthorizedClient}
     * instances. Required when a custom {@link ClientRegistrationRepository}
     * disables Spring Boot's OAuth2 client auto-configuration.
     *
     * @param clientRegistrationRepository the registration repository (DB-backed)
     * @return an in-memory authorized-client service
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * Per-request repository that retrieves authorized clients via the
     * provided service. Required alongside the custom
     * {@link ClientRegistrationRepository}.
     *
     * @param authorizedClientService the in-memory client service
     * @return a request-scoped authorized-client repository
     */
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository(
            OAuth2AuthorizedClientService authorizedClientService) {
        return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
    }

    /**
     * Configures the main {@link SecurityFilterChain} for the application.
     *
     * <p>Authorization rules:</p>
     * <ul>
     *   <li>Static resources and upload paths are publicly accessible.</li>
     *   <li>{@code /login}, {@code /forgot-password}, and {@code /reset-password} are public.</li>
     *   <li>{@code /lecturer/**} requires {@code LECTURER}, {@code HEAD}, or {@code ADMIN} role.</li>
     *   <li>{@code /admin/**} requires the {@code ADMIN} role.</li>
     *   <li>All other requests require an authenticated user.</li>
     * </ul>
     *
     * <p>Google OAuth2 login is always wired in. Whether it is actually
     * available is decided per-request by {@code DbClientRegistrationRepository}.</p>
     *
     * @param http the {@link HttpSecurity} to configure
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if an error occurs while building the filter chain
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Allow same-origin framing so the in-app PDF.js / docx
                // viewer iframes render (default is DENY). See decision 0010.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/fonts/**", "/favicon.ico").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/login", "/forgot-password", "/reset-password").permitAll()
                        .requestMatchers("/public/view/**").permitAll()
                        .requestMatchers("/lecturer/**").hasAnyRole(Roles.LECTURER, Roles.HEAD, Roles.ADMIN)
                        .requestMatchers("/admin/**").hasRole(Roles.ADMIN)
                        .requestMatchers("/my/**", "/j/**").authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        // alwaysUse=false so Spring Security installs the
                        // SavedRequestAwareAuthenticationSuccessHandler.
                        // Deep-link flows (e.g. anonymous GET /j/{invite-token}
                        // for class enrollment) rely on HttpSessionRequestCache
                        // to capture the original URI and resume it after a
                        // successful login. With alwaysUse=true Spring would
                        // bypass the saved request and force every login to
                        // land on "/", breaking the link-join scenario.
                        // Fallback "/" remains safe: when a user opens /login
                        // directly there is no saved request and they are sent
                        // to the home page as before.
                        .defaultSuccessUrl("/", false)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .oauth2Login(oauth -> oauth
                        .loginPage("/login")
                        .userInfoEndpoint(ui -> ui.oidcUserService(customOidcUserService))
                        .failureHandler(oauthFailureHandler())
                        // Mirror the form-login behaviour above so Google sign-in
                        // honours the saved request too. Without this an anonymous
                        // user who follows /j/{token} and chooses "Sign in with
                        // Google" would land on "/" instead of completing the
                        // class join.
                        .defaultSuccessUrl("/", false)
                )
                // Eagerly materialize CSRF token before the view starts rendering.
                // Without this, the deferred CSRF lookup happens deep inside Thymeleaf's
                // form rendering, after the response buffer has already been flushed —
                // which makes HttpServletRequest.getSession(true) throw
                // "Cannot create a session after the response has been committed".
                .addFilterAfter(new CsrfTokenEagerFilter(), CsrfFilter.class);

        return http.build();
    }

    /**
     * Filter that resolves the deferred CSRF token at the beginning of the request,
     * forcing Spring Security to create the HttpSession (if needed) and persist the
     * token before any view rendering begins.
     *
     * <p>Spring Security 6 defers CSRF token loading until the first call to
     * {@code CsrfToken.getToken()}/{@code getParameterName()}. In our setup the first
     * such call happens inside Thymeleaf when it renders a form. By that time the
     * response buffer (default 8KB in Tomcat) may already have been flushed for
     * large pages, which makes {@code request.getSession(true)} throw
     * {@code IllegalStateException: Cannot create a session after the response has
     * been committed}.</p>
     *
     * <p>Resolving the token early here keeps the session creation safely before
     * any output is written.</p>
     */
    private static final class CsrfTokenEagerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                // Touch the token so the underlying repository persists it (and
                // creates the HttpSession if needed) before any view rendering
                // begins and the response buffer might get flushed.
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
