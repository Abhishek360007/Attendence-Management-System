package com.abhishek.attendance.auth;

import com.abhishek.attendance.audit.AuditService;
import com.abhishek.attendance.config.AttendanceProperties;
import com.abhishek.attendance.web.ConflictException;
import com.abhishek.attendance.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService implements UserDetailsService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AttendanceProperties properties;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public UserService(UserRepository users, PasswordEncoder encoder, AuditService audit,
                       AttendanceProperties properties, FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.properties = properties;
        this.sessions = sessions;
    }

    @Override
    public AppUser loadUserByUsername(String email) {
        return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException(email));
    }

    /** Creates the admin from ADMIN_EMAIL / ADMIN_PASSWORD when the database has no users yet. */
    @Override
    public void run(ApplicationArguments args) {
        AttendanceProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (admin != null && admin.configured() && users.count() == 0) {
            create(admin.email(), "Administrator", admin.password(), Role.ADMIN);
            log.info("Created admin account {} from ADMIN_EMAIL", admin.email());
        }
    }

    public boolean setupNeeded() {
        return users.count() == 0;
    }

    /** First-run setup: creates the first admin. Only possible while there are no users. */
    @Transactional
    public AppUser setup(String apiKey, String email, String name, String password) {
        if (!setupNeeded()) {
            throw new ConflictException("Setup is already complete. Sign in instead.");
        }
        if (properties.apiKeyEnabled() && !properties.apiKey().equals(apiKey)) {
            throw new IllegalArgumentException("The setup key does not match API_KEY.");
        }
        return create(email, name, password, Role.ADMIN);
    }

    @Transactional
    public AppUser create(String email, String name, String password, Role role) {
        validatePassword(password);
        if (users.findByEmail(email).isPresent()) {
            throw new ConflictException("An account with email " + email + " already exists.");
        }
        long id = users.insert(email, name, encoder.encode(password), role);
        audit.record("USER_CREATED", "user", id, email + " as " + role);
        return users.findById(id).orElseThrow();
    }

    @Transactional
    public AppUser update(long id, String name, Role role, boolean active, AppUser actor) {
        AppUser existing = get(id);
        if (existing.id().equals(actor.id()) && (role != Role.ADMIN || !active)) {
            throw new ConflictException("You cannot remove your own admin access.");
        }
        users.update(id, name, role, active);
        audit.record("USER_UPDATED", "user", id, describeChange(existing, name, role, active));
        if (existing.role() != role || existing.active() != active) {
            signOutEverywhere(existing.email());
        }
        return get(id);
    }

    @Transactional
    public void resetPassword(long id, String newPassword) {
        AppUser user = get(id);
        validatePassword(newPassword);
        users.updatePassword(id, encoder.encode(newPassword));
        audit.record("PASSWORD_RESET", "user", id, user.email());
        signOutEverywhere(user.email());
    }

    @Transactional
    public void changeOwnPassword(AppUser user, String currentPassword, String newPassword) {
        AppUser fresh = get(user.id());
        if (!encoder.matches(currentPassword, fresh.passwordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        validatePassword(newPassword);
        users.updatePassword(user.id(), encoder.encode(newPassword));
        audit.record("PASSWORD_CHANGED", "user", user.id(), null);
    }

    public AppUser get(long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    public List<AppUser> list() {
        return users.findAll();
    }

    public void recordLogin(AppUser user) {
        users.touchLogin(user.id());
    }

    /** Ends all sessions of a user so role changes, deactivation and password resets take effect at once. */
    private void signOutEverywhere(String email) {
        sessions.findByPrincipalName(email).keySet().forEach(sessions::deleteById);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private static String describeChange(AppUser before, String name, Role role, boolean active) {
        StringBuilder sb = new StringBuilder();
        if (!before.displayName().equals(name)) sb.append("name: ").append(before.displayName()).append(" -> ").append(name).append("; ");
        if (before.role() != role) sb.append("role: ").append(before.role()).append(" -> ").append(role).append("; ");
        if (before.active() != active) sb.append(active ? "reactivated" : "deactivated");
        return sb.isEmpty() ? "no changes" : sb.toString().replaceAll("; $", "");
    }
}
