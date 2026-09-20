package com.fitnesstracker.security;

import com.fitnesstracker.auth.entity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal: the {@link User} row itself.
 *
 * <p>The previous implementation resolves the full user on every request
 * ({@code get_current_user_from_token}) and re-checks {@code is_active} and
 * {@code deleted_at}, so a deactivated account stops working immediately rather than at
 * the next token expiry. That behaviour is preserved, which is why the principal carries
 * the entity rather than just an id.
 */
public record AuthenticatedUser(User user) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return user.isActive() && user.getDeletedAt() == null;
    }
}
