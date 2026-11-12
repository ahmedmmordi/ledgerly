package io.github.ahmedmmordi.ledgerly.identity.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WelcomeNotificationService {

    @Async("taskExecutor")
    public void sendWelcome(String email, String fullName) {
        log.info("Welcome notification dispatched to [{}] on thread [{}]", maskEmail(email),
                Thread.currentThread().getName());
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        return (at > 1 ? email.substring(0, 2) : email.substring(0, 1)) + "***" + email.substring(at);
    }
}
