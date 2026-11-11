package io.github.ahmedmmordi.ledgerly.identity.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WelcomeNotificationService {

    @Async("taskExecutor")
    public void sendWelcome(String email, String fullName) {
        log.info("Welcome notification dispatched to {} ({}) on thread [{}]", fullName, email,
                Thread.currentThread().getName());
    }
}
