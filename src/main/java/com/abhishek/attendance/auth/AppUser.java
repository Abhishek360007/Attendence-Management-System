package com.abhishek.attendance.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * A dashboard account. Doubles as the signed-in principal so the user id is available for auditing.
 */
@JsonIgnoreProperties({"authorities", "accountNonExpired", "accountNonLocked", "credentialsNonExpired", "enabled", "username", "password"})
public record AppUser(
        Long id,
        String email,
        String displayName,
        @JsonIgnore String passwordHash,
        Role role,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return passwordHash;
    }

    @Override
    @JsonIgnore
    public String getUsername() {
        return email;
    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return active;
    }

    public boolean hasRole(Role... roles) {
        for (Role r : roles) {
            if (role == r) {
                return true;
            }
        }
        return false;
    }
}
