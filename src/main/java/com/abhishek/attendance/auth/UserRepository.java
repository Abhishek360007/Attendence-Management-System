package com.abhishek.attendance.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final String COLUMNS =
            "id, email, display_name, password_hash, role, active, created_at, last_login_at";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE LOWER(email) = LOWER(?)")
                .param(email).query(AppUser.class).optional();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ?").param(id).query(AppUser.class).optional();
    }

    public List<AppUser> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user ORDER BY LOWER(display_name)").query(AppUser.class).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
    }

    public long insert(String email, String displayName, String passwordHash, Role role) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO app_user (email, display_name, password_hash, role) VALUES (?, ?, ?, ?)")
                .params(email.trim(), displayName.trim(), passwordHash, role.name())
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void update(long id, String displayName, Role role, boolean active) {
        jdbc.sql("UPDATE app_user SET display_name = ?, role = ?, active = ? WHERE id = ?")
                .params(displayName.trim(), role.name(), active, id).update();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public void touchLogin(long id) {
        jdbc.sql("UPDATE app_user SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
    }
}
