package io.github.ahmedmmordi.ledgerly.identity.user;

import io.github.ahmedmmordi.ledgerly.identity.user.dto.UserResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserResponse getUserProfile(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> {
            log.warn("User profile retrieval failed: user id '{}' not found", userId);
            return new UserNotFoundException(userId);
        });
        return UserResponse.from(user);
    }
}
