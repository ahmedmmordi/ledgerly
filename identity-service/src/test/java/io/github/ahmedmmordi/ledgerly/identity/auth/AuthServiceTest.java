package io.github.ahmedmmordi.ledgerly.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.ahmedmmordi.ledgerly.identity.auth.dto.AuthResponse;
import io.github.ahmedmmordi.ledgerly.identity.auth.dto.LoginRequest;
import io.github.ahmedmmordi.ledgerly.identity.auth.dto.RegisterRequest;
import io.github.ahmedmmordi.ledgerly.identity.config.JwtService;
import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.github.ahmedmmordi.ledgerly.identity.user.User;
import io.github.ahmedmmordi.ledgerly.identity.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private WelcomeNotificationService welcomeNotificationService;

    @InjectMocks
    private AuthService authService;

    @Nested
    @DisplayName("register")
    class RegisterTests {

        @Test
        @DisplayName("saves user with hashed password, dispatches welcome notification, and returns token")
        void withValidRequest_savesUserDispatchesWelcomeAndReturnsAuthResponse() {
            String rawPassword = "Password123!";
            String encodedHash = "$2a$12$hashedPassword";
            String expectedToken = "jwt.token.value";
            UUID userId = UUID.randomUUID();
            RegisterRequest request = new RegisterRequest("ahmed.mordi@example.com", rawPassword, "Ahmed", "Mordi");

            when(userRepository.existsByEmailIgnoreCase("ahmed.mordi@example.com")).thenReturn(false);
            when(passwordEncoder.encode(rawPassword)).thenReturn(encodedHash);
            mockSaveUserWithId(userId);
            when(jwtService.generateToken(userId, "ahmed.mordi@example.com", Role.USER)).thenReturn(expectedToken);

            AuthResponse response = authService.register(request);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(userId);
            assertThat(response.email()).isEqualTo("ahmed.mordi@example.com");
            assertThat(response.firstName()).isEqualTo("Ahmed");
            assertThat(response.lastName()).isEqualTo("Mordi");
            assertThat(response.role()).isEqualTo(Role.USER);
            assertThat(response.token()).isEqualTo(expectedToken);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getEmail()).isEqualTo("ahmed.mordi@example.com");
            assertThat(savedUser.getPasswordHash()).isEqualTo(encodedHash).isNotEqualTo(rawPassword);
            assertThat(savedUser.getRole()).isEqualTo(Role.USER);

            verify(welcomeNotificationService).sendWelcome("ahmed.mordi@example.com", "Ahmed Mordi");
        }

        @Test
        @DisplayName("normalizes email and trims names before saving and dispatching welcome")
        void withWhitespaceAndUppercaseEmail_normalizesEmailAndNamesBeforeSaving() {
            UUID userId = UUID.randomUUID();
            RegisterRequest request = new RegisterRequest("  Ahmed.Mordi@EXAMPLE.COM  ", "Password123!", "  Ahmed  ",
                    "  Mordi  ");

            when(userRepository.existsByEmailIgnoreCase("ahmed.mordi@example.com")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("encodedHash");
            mockSaveUserWithId(userId);
            when(jwtService.generateToken(any(), any(), any())).thenReturn("token");

            AuthResponse response = authService.register(request);

            assertThat(response.email()).isEqualTo("ahmed.mordi@example.com");
            assertThat(response.firstName()).isEqualTo("Ahmed");
            assertThat(response.lastName()).isEqualTo("Mordi");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getEmail()).isEqualTo("ahmed.mordi@example.com");
            assertThat(savedUser.getFirstName()).isEqualTo("Ahmed");
            assertThat(savedUser.getLastName()).isEqualTo("Mordi");

            verify(welcomeNotificationService).sendWelcome("ahmed.mordi@example.com", "Ahmed Mordi");
        }

        @Test
        @DisplayName("throws UserAlreadyExistsException and prevents side effects when email is already registered")
        void withExistingEmail_throwsUserAlreadyExistsException() {
            RegisterRequest request = new RegisterRequest("existing@example.com", "Password123!", "Jane", "Doe");

            when(userRepository.existsByEmailIgnoreCase("existing@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request)).isInstanceOf(UserAlreadyExistsException.class)
                    .hasMessage("An account with that email address already exists");

            verify(passwordEncoder, never()).encode(any());
            verify(userRepository, never()).save(any());
            verifyNoInteractions(welcomeNotificationService);
            verifyNoInteractions(jwtService);
        }

        @Test
        @DisplayName("prevents welcome notification and token generation when user save throws DataIntegrityViolationException")
        void whenSaveThrows_preventsWelcomeNotificationAndTokenGeneration() {
            RegisterRequest request = new RegisterRequest("ahmed.mordi@example.com", "Password123!", "Ahmed", "Mordi");

            when(userRepository.existsByEmailIgnoreCase("ahmed.mordi@example.com")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("encodedHash");
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

            assertThatThrownBy(() -> authService.register(request)).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("duplicate key value");

            verifyNoInteractions(welcomeNotificationService);
            verifyNoInteractions(jwtService);
        }
    }

    @Nested
    @DisplayName("login")
    class LoginTests {

        @Test
        @DisplayName("returns AuthResponse with token when credentials are valid")
        void withValidCredentials_returnsAuthResponseWithToken() {
            UUID userId = UUID.randomUUID();
            User user = buildTestUser(userId, "ahmed.mordi@example.com", "$2a$12$hashedPassword", "Ahmed", "Mordi",
                    Role.USER);
            LoginRequest request = new LoginRequest("ahmed.mordi@example.com", "Password123!");
            String expectedToken = "valid.jwt.token";

            when(userRepository.findByEmailIgnoreCase("ahmed.mordi@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Password123!", "$2a$12$hashedPassword")).thenReturn(true);
            when(jwtService.generateToken(userId, "ahmed.mordi@example.com", Role.USER)).thenReturn(expectedToken);

            AuthResponse response = authService.login(request);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(userId);
            assertThat(response.email()).isEqualTo("ahmed.mordi@example.com");
            assertThat(response.firstName()).isEqualTo("Ahmed");
            assertThat(response.lastName()).isEqualTo("Mordi");
            assertThat(response.role()).isEqualTo(Role.USER);
            assertThat(response.token()).isEqualTo(expectedToken);
        }

        @Test
        @DisplayName("normalizes email before repository lookup")
        void withWhitespaceAndUppercaseEmail_normalizesEmailBeforeLookup() {
            UUID userId = UUID.randomUUID();
            User user = buildTestUser(userId, "ahmed.mordi@example.com", "$2a$12$hashedPassword", "Ahmed", "Mordi",
                    Role.USER);
            LoginRequest request = new LoginRequest("  Ahmed.Mordi@EXAMPLE.COM  ", "Password123!");

            when(userRepository.findByEmailIgnoreCase("ahmed.mordi@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Password123!", "$2a$12$hashedPassword")).thenReturn(true);
            when(jwtService.generateToken(any(), any(), any())).thenReturn("token");

            AuthResponse response = authService.login(request);

            assertThat(response).isNotNull();
            verify(userRepository).findByEmailIgnoreCase("ahmed.mordi@example.com");
        }

        @Test
        @DisplayName("throws InvalidCredentialsException when email is not found")
        void withUnknownEmail_throwsInvalidCredentialsException() {
            LoginRequest request = new LoginRequest("unknown@example.com", "Password123!");

            when(userRepository.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request)).isInstanceOf(InvalidCredentialsException.class);

            verify(passwordEncoder, never()).matches(any(), any());
            verifyNoInteractions(jwtService);
        }

        @Test
        @DisplayName("throws InvalidCredentialsException when password does not match")
        void withWrongPassword_throwsInvalidCredentialsException() {
            UUID userId = UUID.randomUUID();
            User user = buildTestUser(userId, "ahmed.mordi@example.com", "$2a$12$hashedPassword", "Ahmed", "Mordi",
                    Role.USER);
            LoginRequest request = new LoginRequest("ahmed.mordi@example.com", "WrongPassword!");

            when(userRepository.findByEmailIgnoreCase("ahmed.mordi@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("WrongPassword!", "$2a$12$hashedPassword")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request)).isInstanceOf(InvalidCredentialsException.class);

            verifyNoInteractions(jwtService);
        }
    }

    private void mockSaveUserWithId(UUID userId) {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", userId);
            return user;
        });
    }

    private User buildTestUser(UUID id, String email, String passwordHash, String firstName, String lastName,
            Role role) {
        User user = User.create(email, passwordHash, firstName, lastName, role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
