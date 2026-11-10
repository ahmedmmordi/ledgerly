package io.github.ahmedmmordi.ledgerly.identity.auth;

import io.github.ahmedmmordi.ledgerly.identity.auth.dto.AuthResponse;
import io.github.ahmedmmordi.ledgerly.identity.auth.dto.LoginRequest;
import io.github.ahmedmmordi.ledgerly.identity.auth.dto.RegisterRequest;
import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.github.ahmedmmordi.ledgerly.identity.user.User;
import io.github.ahmedmmordi.ledgerly.identity.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = User.normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.warn("Registration rejected: email '{}' already registered", normalizedEmail);
            throw new UserAlreadyExistsException(normalizedEmail);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.create(normalizedEmail, passwordHash, request.firstName().trim(), request.lastName().trim(),
                Role.USER);

        User savedUser = userRepository.save(user);
        log.info("Registered new user with id: {}", savedUser.getId());
        return AuthResponse.from(savedUser);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = User.normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElseThrow(() -> {
            log.warn("Login failed: user with email '{}' not found", normalizedEmail);
            return new InvalidCredentialsException();
        });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed: invalid password for user id '{}'", user.getId());
            throw new InvalidCredentialsException();
        }

        log.info("User logged in successfully: {}", user.getId());
        return AuthResponse.from(user);
    }
}
