package io.github.ahmedmmordi.ledgerly.identity.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
public class WelcomeNotificationService {

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        log.info("Welcome notification dispatched for user id [{}] on thread [{}]", event.userId(),
                Thread.currentThread().getName());
    }
}
