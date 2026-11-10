package io.github.ahmedmmordi.ledgerly.identity.user;

import io.github.ahmedmmordi.ledgerly.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    public static User create(String email, String passwordHash, String firstName, String lastName, Role role) {
        return User.builder().email(normalizeEmail(email)).passwordHash(passwordHash).firstName(firstName)
                .lastName(lastName).role(role != null ? role : Role.USER).build();
    }

    public static String normalizeEmail(String email) {
        return email != null ? email.trim().toLowerCase(Locale.ROOT) : null;
    }
}
