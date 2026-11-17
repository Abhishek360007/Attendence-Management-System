package com.abhishek.attendance.notify;

import com.abhishek.attendance.auth.AppUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAnyRole('ADMIN', 'HR', 'MANAGER', 'EMPLOYEE')")
public class NotificationController {

    private final NotificationService notifications;
    private final MailService mail;

    public NotificationController(NotificationService notifications, MailService mail) {
        this.notifications = notifications;
        this.mail = mail;
    }

    @GetMapping
    public List<NotificationService.Notification> inbox(@AuthenticationPrincipal AppUser user) {
        return notifications.inbox(user.id(), 100);
    }

    @GetMapping("/unread")
    public Map<String, Object> unread(@AuthenticationPrincipal AppUser user) {
        return Map.of("count", notifications.unread(user.id()), "emailEnabled", mail.enabled());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        notifications.markRead(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal AppUser user) {
        notifications.markRead(user.id(), null);
        return ResponseEntity.noContent().build();
    }
}
