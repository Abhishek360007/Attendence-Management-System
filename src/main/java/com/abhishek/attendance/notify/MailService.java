package com.abhishek.attendance.notify;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Sends email when a mail server is configured (MAIL_HOST etc.); otherwise does nothing, and notifications are only
 * shown in the dashboard. Failures are logged, never thrown: a broken mail server must not break approvals.
 */
@Service
public class MailService {

    public record Attachment(String filename, String contentType, byte[] content) {
        public static Attachment csv(String filename, String csv) {
            return new Attachment(filename, "text/csv", (((char) 0xFEFF) + csv).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String host;
    private final String publicUrl;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${attendance.mail.from:}") String from,
                       @Value("${attendance.public-url:}") String publicUrl) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    public boolean enabled() {
        return host != null && !host.isBlank() && sender.getIfAvailable() != null;
    }

    /** Absolute link into the dashboard, when PUBLIC_URL is set. */
    public String link(String route) {
        return publicUrl.isEmpty() || route == null ? null : publicUrl + "/#" + route;
    }

    public void send(Collection<String> to, String subject, String text) {
        send(to, subject, text, List.of());
    }

    public void send(Collection<String> to, String subject, String text, List<Attachment> attachments) {
        List<String> recipients = to.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        if (!enabled() || recipients.isEmpty()) {
            return;
        }
        try {
            JavaMailSender mail = sender.getObject();
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, !attachments.isEmpty(), "UTF-8");
            if (!from.isBlank()) helper.setFrom(from);
            helper.setTo(recipients.getFirst());
            if (recipients.size() > 1) helper.setBcc(recipients.subList(1, recipients.size()).toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(text, false);
            for (Attachment a : attachments) {
                helper.addAttachment(a.filename(), new ByteArrayResource(a.content()), a.contentType());
            }
            mail.send(message);
        } catch (Exception e) {
            log.warn("Could not send email '{}' to {} recipient(s): {}", subject, recipients.size(), e.getMessage());
        }
    }
}
