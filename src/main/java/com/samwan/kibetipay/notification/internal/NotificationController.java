package com.samwan.kibetipay.notification.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {
    private final JdbcClient jdbc;
    NotificationController(JdbcClient jdbc) { this.jdbc = jdbc; }
    record Message(UUID id, String kind, String message, Instant createdAt, Instant readAt) {}

    @GetMapping("/api/notifications")
    List<Message> mine() {
        return jdbc.sql("SELECT id, kind, message, created_at, read_at FROM notification_message WHERE recipient_id = :user ORDER BY created_at DESC LIMIT 100")
                .param("user", CurrentUser.id()).query((rs, n) -> new Message(rs.getObject("id", UUID.class),
                        rs.getString("kind"), rs.getString("message"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant())).list();
    }

    @PostMapping("/api/notifications/{id}/read")
    void read(@PathVariable UUID id) {
        int updated = jdbc.sql("UPDATE notification_message SET read_at = coalesce(read_at, now()) WHERE id = :id AND recipient_id = :user")
                .param("id", id).param("user", CurrentUser.id()).update();
        if (updated == 0) {
            throw new DomainException.NotFound("notification_not_found", "No notification " + id);
        }
    }
}
