package com.abhishek.attendance.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    public record CreateUser(@NotBlank @Email String email, @NotBlank @Size(max = 128) String name,
                             @NotBlank String password, @NotNull Role role) {
    }

    public record UpdateUser(@NotBlank @Size(max = 128) String name, @NotNull Role role, boolean active) {
    }

    public record ResetPassword(@NotBlank String password) {
    }

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<AppUser> list() {
        return users.list();
    }

    @PostMapping
    public AppUser create(@Valid @RequestBody CreateUser body) {
        return users.create(body.email(), body.name(), body.password(), body.role());
    }

    @PutMapping("/{id}")
    public AppUser update(@PathVariable long id, @Valid @RequestBody UpdateUser body,
                          @AuthenticationPrincipal AppUser actor) {
        return users.update(id, body.name(), body.role(), body.active(), actor);
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable long id, @Valid @RequestBody ResetPassword body) {
        users.resetPassword(id, body.password());
        return ResponseEntity.noContent().build();
    }
}
