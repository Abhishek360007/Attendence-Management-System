package com.abhishek.attendance.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record SetupRequest(String setupKey, @NotBlank @Email String email,
                               @NotBlank @Size(max = 128) String name, @NotBlank String password) {
    }

    public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final UserService users;
    private final LoginRateLimiter limiter;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          UserService users, LoginRateLimiter limiter) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.users = users;
        this.limiter = limiter;
    }

    @GetMapping("/me")
    public AppUser me(@AuthenticationPrincipal AppUser user) {
        if (user == null) {
            throw new org.springframework.security.access.AccessDeniedException("Signed in with an API key, not a user account");
        }
        return users.get(user.id());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                   HttpServletResponse response) {
        String address = request.getRemoteAddr();
        if (limiter.isBlocked(body.email(), address)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes."));
        }
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        } catch (AuthenticationException e) {
            limiter.recordFailure(body.email(), address);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED, "Email or password is incorrect, or the account is deactivated."));
        }
        limiter.recordSuccess(body.email());
        startSession(auth, request, response);
        AppUser user = (AppUser) auth.getPrincipal();
        users.recordLogin(user);
        return ResponseEntity.ok(users.get(user.id()));
    }

    @GetMapping("/setup")
    public Map<String, Boolean> setupStatus() {
        return Map.of("needed", users.setupNeeded());
    }

    /** First run only: creates the first admin account and signs it in. */
    @PostMapping("/setup")
    public AppUser setup(@Valid @RequestBody SetupRequest body, HttpServletRequest request,
                         HttpServletResponse response) {
        users.setup(body.setupKey(), body.email(), body.name(), body.password());
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        startSession(auth, request, response);
        AppUser user = (AppUser) auth.getPrincipal();
        users.recordLogin(user);
        return users.get(user.id());
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AppUser user,
                                               @Valid @RequestBody PasswordChange body) {
        users.changeOwnPassword(user, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private void startSession(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId(); // session fixation protection
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
