package com.abhishek.attendance.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    /** Company name, time zone and today's date in that zone: needed by every signed-in user. */
    @GetMapping("/public")
    public Map<String, String> publicSettings() {
        return settings.publicSettings();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public Map<String, String> all() {
        return settings.all();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> update(@RequestBody Map<String, String> changes) {
        return settings.update(changes);
    }
}
