package com.abhishek.attendance.analytics;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.settings.SettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasAnyRole('ADMIN', 'HR', 'MANAGER', 'API')")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final SettingsService settings;

    public AnalyticsController(AnalyticsService analytics, SettingsService settings) {
        this.analytics = analytics;
        this.settings = settings;
    }

    /** month defaults to the current month; months is the length of the trend (2-12, default 6). */
    @GetMapping
    public AnalyticsService.Report report(@AuthenticationPrincipal AppUser user,
                                          @RequestParam(required = false) YearMonth month,
                                          @RequestParam(defaultValue = "6") int months,
                                          @RequestParam(required = false) Long departmentId) {
        return analytics.report(user, month != null ? month : YearMonth.from(settings.today()), months, departmentId);
    }
}
