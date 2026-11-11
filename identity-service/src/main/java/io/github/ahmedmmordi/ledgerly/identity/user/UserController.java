package io.github.ahmedmmordi.ledgerly.identity.user;

import io.github.ahmedmmordi.ledgerly.identity.user.dto.UserResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return userService.getUserProfile(userId);
    }
}
