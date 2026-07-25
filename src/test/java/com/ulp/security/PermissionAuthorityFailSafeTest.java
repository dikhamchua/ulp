package com.ulp.security;

import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.admin.permissions.service.PermissionResolver;
import com.ulp.features.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Guards the two invariants that make permission resolution unable to break login.
 *
 * <ol>
 *   <li>{@code ROLE_<role>} is ALWAYS present. {@code PERM_*} authorities are only ever
 *       added alongside it, never substituted for it.</li>
 *   <li>Permission resolution can never fail authentication. A resolver that throws, or
 *       that returns null or an empty set, must still yield a usable principal carrying
 *       role-only authorities.</li>
 * </ol>
 *
 * <p>Violating either invariant breaks login for every user, so these scenarios are
 * asserted directly rather than through a full Spring context.
 */
@ExtendWith(MockitoExtension.class)
class PermissionAuthorityFailSafeTest {

    @Mock private UserRepository userRepository;
    @Mock private PermissionResolver permissionResolver;
    @InjectMocks private CustomUserDetailsService service;

    private static final String EMAIL = "lecturer@ulp.edu.vn";

    /** Builds a User entity without touching the database. */
    private static User user(Role role) {
        // The entity's no-arg constructor is protected for JPA; UserFactory is the
        // supported public surface. Only the generated id needs reflection.
        User user = UserFactory.newAdminCreated(
                EMAIL, "{bcrypt}x", "Test User", role, true, null, null);
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }

    private static List<String> authorityNames(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    // ───────── invariant 1: ROLE_* is never substituted ─────────

    @Test
    void role_authority_is_present_alongside_permission_authorities() {
        UlpUserDetails details = new UlpUserDetails(
                user(Role.LECTURER), Set.of("library.view", "upload.lesson_video"));

        assertThat(authorityNames(details))
                .contains(Role.LECTURER.authority())
                .contains("PERM_library.view", "PERM_upload.lesson_video");
    }

    @Test
    void role_authority_is_listed_first() {
        UlpUserDetails details = new UlpUserDetails(user(Role.ADMIN), Set.of("system.permissions"));

        assertThat(authorityNames(details).get(0)).isEqualTo(Role.ADMIN.authority());
    }

    @Test
    void every_role_keeps_its_role_authority_when_permissions_are_present() {
        for (Role role : Role.values()) {
            UlpUserDetails details = new UlpUserDetails(user(role), Set.of("library.view"));

            assertThat(authorityNames(details))
                    .as("PERM_* must never replace ROLE_* for %s", role)
                    .contains(role.authority());
        }
    }

    @Test
    void the_legacy_single_argument_constructor_still_yields_role_only_authorities() {
        // Callers predating permissions must keep working unchanged.
        UlpUserDetails details = new UlpUserDetails(user(Role.STUDENT));

        assertThat(authorityNames(details)).containsExactly(Role.STUDENT.authority());
    }

    @Test
    void blank_and_null_feature_keys_never_become_authorities() {
        List<String> keys = new ArrayList<>();
        keys.add("library.view");
        keys.add(null);
        keys.add("   ");
        keys.add("");

        UlpUserDetails details = new UlpUserDetails(user(Role.LECTURER), keys);

        assertThat(authorityNames(details))
                .containsExactly(Role.LECTURER.authority(), "PERM_library.view");
    }

    // ───────── invariant 2: resolution can never fail login ─────────

    @Test
    void a_throwing_resolver_still_produces_a_principal_with_role_only_authorities() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.LECTURER)));
        when(permissionResolver.resolvePermissions(anyLong()))
                .thenThrow(new RuntimeException("v_user_effective_permissions is missing"));

        UserDetails details = service.loadUserByUsername(EMAIL);

        assertThat(authorityNames(details)).containsExactly(Role.LECTURER.authority());
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void a_throwing_resolver_does_not_propagate_out_of_principal_construction() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.ADMIN)));
        when(permissionResolver.resolvePermissions(anyLong()))
                .thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> service.loadUserByUsername(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void an_empty_resolver_result_yields_role_only_authorities() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.HEAD)));
        when(permissionResolver.resolvePermissions(anyLong())).thenReturn(Set.of());

        UserDetails details = service.loadUserByUsername(EMAIL);

        assertThat(authorityNames(details)).containsExactly(Role.HEAD.authority());
    }

    @Test
    void a_null_resolver_result_yields_role_only_authorities() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.STUDENT)));
        when(permissionResolver.resolvePermissions(anyLong())).thenReturn(null);

        UserDetails details = service.loadUserByUsername(EMAIL);

        assertThat(authorityNames(details)).containsExactly(Role.STUDENT.authority());
    }

    @Test
    void a_successful_resolution_still_adds_permission_authorities() {
        // Confirms the fail-safe path did not disable the feature it protects.
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.LECTURER)));
        when(permissionResolver.resolvePermissions(anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("library.view")));

        UserDetails details = service.loadUserByUsername(EMAIL);

        assertThat(authorityNames(details))
                .containsExactly(Role.LECTURER.authority(), "PERM_library.view");
    }
}
