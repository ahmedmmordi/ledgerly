package io.github.ahmedmmordi.ledgerly.identity.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class UserDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Users table is not empty, skipping dev user seeding.");
            return;
        }

        log.info("Seeding initial dev users...");
        String encodedPassword = passwordEncoder.encode("Password123!");

        User admin1 = User.create("admin1@ledgerly.com", encodedPassword, "Admin", "One", Role.ADMIN);
        User admin2 = User.create("admin2@ledgerly.com", encodedPassword, "Admin", "Two", Role.ADMIN);
        User ahmed = User.create("ahmed@ledgerly.com", encodedPassword, "Ahmed", "Mordi", Role.USER);
        User bob = User.create("bob@ledgerly.com", encodedPassword, "Bob", "Jones", Role.USER);
        User charlie = User.create("charlie@ledgerly.com", encodedPassword, "Charlie", "Brown", Role.USER);

        userRepository.saveAll(List.of(admin1, admin2, ahmed, bob, charlie));
        log.info("Successfully seeded 5 dev users.");
    }
}
