package io.github.ahmedmmordi.ledgerly.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.List;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService Unit Tests")
class AccountServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");
    private static final UUID ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-111111111111");

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    private Account createMockAccount(UUID id, String accountNumber, String currency, long balance, AccountType type) {
        Account account = (type == AccountType.CUSTOMER) ? Account.createCustomer(USER_ID, accountNumber, currency)
                : Account.createSystem(USER_ID, accountNumber, currency);
        if (balance > 0L) {
            account.credit(Money.ofMinor(balance, currency));
        }
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    @Nested
    @DisplayName("Customer Account Creation")
    class CustomerAccountCreationTests {

        @Test
        @DisplayName("Should create customer account successfully with unique generated number")
        void shouldCreateCustomerAccountSuccessfully() {
            given(accountRepository.existsByAccountNumber(anyString())).willReturn(false);
            given(accountRepository.save(any(Account.class))).willAnswer(invocation -> {
                Account toSave = invocation.getArgument(0);
                ReflectionTestUtils.setField(toSave, "id", ACCOUNT_ID);
                return toSave;
            });

            Account created = accountService.createCustomerAccount(USER_ID, "usd");

            assertThat(created).isNotNull();
            assertThat(created.getId()).isEqualTo(ACCOUNT_ID);
            assertThat(created.getUserId()).isEqualTo(USER_ID);
            assertThat(created.getCurrency()).isEqualTo("USD");
            assertThat(created.getType()).isEqualTo(AccountType.CUSTOMER);
            assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(created.getBalance()).isEqualTo(Money.ofMinor(0L, "USD"));
            assertThat(created.getAccountNumber()).matches("^ACC\\d{10}$");

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue().getAccountNumber()).isEqualTo(created.getAccountNumber());
        }

        @Test
        @DisplayName("Should retry when generated account number collides and succeed on next attempt")
        void shouldRetryOnCollisionAndSucceed() {
            given(accountRepository.existsByAccountNumber(anyString())).willReturn(true) // First attempt collides
                    .willReturn(false); // Second attempt is unique
            given(accountRepository.save(any(Account.class))).willAnswer(invocation -> invocation.getArgument(0));

            Account created = accountService.createCustomerAccount(USER_ID, "EUR");

            assertThat(created).isNotNull();
            assertThat(created.getCurrency()).isEqualTo("EUR");
            verify(accountRepository, times(2)).existsByAccountNumber(anyString());
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when all 5 generation attempts collide")
        void shouldThrowWhenAllAttemptsCollide() {
            given(accountRepository.existsByAccountNumber(anyString())).willReturn(true);

            assertThatThrownBy(() -> accountService.createCustomerAccount(USER_ID, "USD"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to generate unique account number after 5 attempts");

            verify(accountRepository, times(5)).existsByAccountNumber(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject null user ID on customer account creation")
        void shouldRejectNullUserId() {
            assertThatThrownBy(() -> accountService.createCustomerAccount(null, "USD"))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");
        }

        @Test
        @DisplayName("Should reject null currency on customer account creation")
        void shouldRejectNullCurrency() {
            assertThatThrownBy(() -> accountService.createCustomerAccount(USER_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Currency code must not be null");
        }

        @Test
        @DisplayName("Should reject invalid ISO currency code")
        void shouldRejectInvalidCurrencyCode() {
            assertThatThrownBy(() -> accountService.createCustomerAccount(USER_ID, "INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(accountRepository, never()).existsByAccountNumber(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject blank currency code on customer account creation")
        void shouldRejectBlankCurrencyCode() {
            assertThatThrownBy(() -> accountService.createCustomerAccount(USER_ID, "   "))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Currency code must not be blank");

            verify(accountRepository, never()).existsByAccountNumber(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }
    }

    @Nested
    @DisplayName("System Account Creation")
    class SystemAccountCreationTests {

        @Test
        @DisplayName("Should create system account successfully")
        void shouldCreateSystemAccountSuccessfully() {
            given(accountRepository.existsByAccountNumber("SYSUSD00000001")).willReturn(false);
            given(accountRepository.save(any(Account.class))).willAnswer(invocation -> {
                Account toSave = invocation.getArgument(0);
                ReflectionTestUtils.setField(toSave, "id", ACCOUNT_ID);
                return toSave;
            });

            Account created = accountService.createSystemAccount(USER_ID, "usd");

            assertThat(created).isNotNull();
            assertThat(created.getAccountNumber()).isEqualTo("SYSUSD00000001");
            assertThat(created.getCurrency()).isEqualTo("USD");
            assertThat(created.getType()).isEqualTo(AccountType.SYSTEM);
            assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject creating system account if one already exists for currency")
        void shouldRejectDuplicateSystemAccount() {
            given(accountRepository.existsByAccountNumber("SYSUSD00000001")).willReturn(true);

            assertThatThrownBy(() -> accountService.createSystemAccount(USER_ID, "USD"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("System account already exists for currency USD");

            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject null user ID on system account creation")
        void shouldRejectNullUserIdOnSystemCreation() {
            assertThatThrownBy(() -> accountService.createSystemAccount(null, "USD"))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");
        }

        @Test
        @DisplayName("Should reject null currency code on system account creation")
        void shouldRejectNullCurrencyOnSystemCreation() {
            assertThatThrownBy(() -> accountService.createSystemAccount(USER_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Currency code must not be null");
        }

        @Test
        @DisplayName("Should reject invalid currency code on system account creation")
        void shouldRejectInvalidCurrencyOnSystemCreation() {
            assertThatThrownBy(() -> accountService.createSystemAccount(USER_ID, "INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(accountRepository, never()).existsByAccountNumber(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject blank currency code on system account creation")
        void shouldRejectBlankCurrencyOnSystemCreation() {
            assertThatThrownBy(() -> accountService.createSystemAccount(USER_ID, "   "))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Currency code must not be blank");

            verify(accountRepository, never()).existsByAccountNumber(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }
    }

    @Nested
    @DisplayName("Get Or Create System Account")
    class GetOrCreateSystemAccountTests {

        @Test
        @DisplayName("Should return existing system account if already present in database")
        void shouldReturnExistingSystemAccount() {
            Account existing = createMockAccount(ACCOUNT_ID, "SYSUSD00000001", "USD", 0L, AccountType.SYSTEM);
            given(accountRepository.findSystemAccount("USD")).willReturn(Optional.of(existing));

            Account result = accountService.getOrCreateSystemAccount(USER_ID, "usd");

            assertThat(result).isSameAs(existing);
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should create and save new system account if not present")
        void shouldCreateNewSystemAccountIfAbsent() {
            given(accountRepository.findSystemAccount("USD")).willReturn(Optional.empty());
            given(accountRepository.save(any(Account.class))).willAnswer(invocation -> {
                Account toSave = invocation.getArgument(0);
                ReflectionTestUtils.setField(toSave, "id", ACCOUNT_ID);
                return toSave;
            });

            Account result = accountService.getOrCreateSystemAccount(USER_ID, "usd");

            assertThat(result).isNotNull();
            assertThat(result.getAccountNumber()).isEqualTo("SYSUSD00000001");
            assertThat(result.getType()).isEqualTo(AccountType.SYSTEM);
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject null user ID on get or create system account")
        void shouldRejectNullUserIdOnGetOrCreate() {
            assertThatThrownBy(() -> accountService.getOrCreateSystemAccount(null, "USD"))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");

            verify(accountRepository, never()).findSystemAccount(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject null currency code on get or create system account")
        void shouldRejectNullCurrencyOnGetOrCreate() {
            assertThatThrownBy(() -> accountService.getOrCreateSystemAccount(USER_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Currency code must not be null");

            verify(accountRepository, never()).findSystemAccount(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject invalid currency code on get or create system account")
        void shouldRejectInvalidCurrencyOnGetOrCreate() {
            assertThatThrownBy(() -> accountService.getOrCreateSystemAccount(USER_ID, "INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(accountRepository, never()).findSystemAccount(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should reject blank currency code on get or create system account")
        void shouldRejectBlankCurrencyOnGetOrCreate() {
            assertThatThrownBy(() -> accountService.getOrCreateSystemAccount(USER_ID, "   "))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Currency code must not be blank");

            verify(accountRepository, never()).findSystemAccount(anyString());
            verify(accountRepository, never()).save(any(Account.class));
        }
    }

    @Nested
    @DisplayName("Account Retrieval")
    class AccountRetrievalTests {

        @Test
        @DisplayName("Should retrieve account by ID successfully")
        void shouldGetAccountByIdSuccessfully() {
            Account mockAccount = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 10_000L, AccountType.CUSTOMER);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(mockAccount));

            Account result = accountService.getAccount(ACCOUNT_ID);

            assertThat(result).isSameAs(mockAccount);
            assertThat(result.getId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should throw AccountNotFoundException when account ID is not found")
        void shouldThrowWhenAccountIdNotFound() {
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.getAccount(ACCOUNT_ID)).isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account not found with ID: " + ACCOUNT_ID).satisfies(ex -> {
                        AccountNotFoundException anfe = (AccountNotFoundException) ex;
                        assertThat(anfe.getAccountId()).isEqualTo(ACCOUNT_ID);
                        assertThat(anfe.getAccountNumber()).isNull();
                    });
        }

        @Test
        @DisplayName("Should reject null account ID on retrieval")
        void shouldRejectNullAccountId() {
            assertThatThrownBy(() -> accountService.getAccount(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account ID must not be null");
        }

        @Test
        @DisplayName("Should retrieve account by account number successfully")
        void shouldGetAccountByNumberSuccessfully() {
            Account mockAccount = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 5_000L, AccountType.CUSTOMER);
            given(accountRepository.findByAccountNumber("ACC1234567890")).willReturn(Optional.of(mockAccount));

            Account result = accountService.getAccountByNumber("  acc1234567890  ");

            assertThat(result).isSameAs(mockAccount);
            assertThat(result.getAccountNumber()).isEqualTo("ACC1234567890");
        }

        @Test
        @DisplayName("Should throw AccountNotFoundException when account number is not found")
        void shouldThrowWhenAccountNumberNotFound() {
            given(accountRepository.findByAccountNumber("ACC9999999999")).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.getAccountByNumber("ACC9999999999"))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account not found with account number: ACC9999999999").satisfies(ex -> {
                        AccountNotFoundException anfe = (AccountNotFoundException) ex;
                        assertThat(anfe.getAccountNumber()).isEqualTo("ACC9999999999");
                        assertThat(anfe.getAccountId()).isNull();
                    });
        }

        @Test
        @DisplayName("Should reject null account number on retrieval")
        void shouldRejectNullAccountNumber() {
            assertThatThrownBy(() -> accountService.getAccountByNumber(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account number must not be null");
        }

        @Test
        @DisplayName("Should reject blank account number on retrieval")
        void shouldRejectBlankAccountNumber() {
            assertThatThrownBy(() -> accountService.getAccountByNumber("   "))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Account number must not be blank");

            verify(accountRepository, never()).findByAccountNumber(anyString());
        }
    }

    @Nested
    @DisplayName("User Accounts Query")
    class UserAccountsQueryTests {

        @Test
        @DisplayName("Should return paginated accounts for a user")
        void shouldGetAccountsByUserIdWithPagination() {
            Account account1 = createMockAccount(UUID.randomUUID(), "ACC0000000001", "USD", 1_000L,
                    AccountType.CUSTOMER);
            Account account2 = createMockAccount(UUID.randomUUID(), "ACC0000000002", "EUR", 2_000L,
                    AccountType.CUSTOMER);
            Page<Account> page = new PageImpl<>(List.of(account1, account2));
            Pageable pageable = PageRequest.of(0, 10);

            given(accountRepository.findByUserId(USER_ID, pageable)).willReturn(page);

            Page<Account> result = accountService.getAccountsByUserId(USER_ID, pageable);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent()).containsExactly(account1, account2);
            verify(accountRepository).findByUserId(USER_ID, pageable);
        }

        @Test
        @DisplayName("Should return empty page when user has no accounts")
        void shouldReturnEmptyPageWhenUserHasNoAccounts() {
            Pageable pageable = PageRequest.of(0, 10);
            given(accountRepository.findByUserId(USER_ID, pageable)).willReturn(Page.empty(pageable));

            Page<Account> result = accountService.getAccountsByUserId(USER_ID, pageable);

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.getContent()).isEmpty();
            verify(accountRepository).findByUserId(USER_ID, pageable);
        }

        @Test
        @DisplayName("Should reject null user ID on get accounts")
        void shouldRejectNullUserIdOnGetAccounts() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> accountService.getAccountsByUserId(null, pageable))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");
        }

        @Test
        @DisplayName("Should reject null pageable on get accounts")
        void shouldRejectNullPageableOnGetAccounts() {
            assertThatThrownBy(() -> accountService.getAccountsByUserId(USER_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Pageable must not be null");
        }
    }

    @Nested
    @DisplayName("Account Ownership Authorization")
    class AccountOwnershipTests {

        @Test
        @DisplayName("Should return true when account belongs to user")
        void shouldReturnTrueWhenAccountBelongsToUser() {
            given(accountRepository.existsByIdAndUserId(ACCOUNT_ID, USER_ID)).willReturn(true);

            boolean isOwned = accountService.isAccountOwnedByUser(ACCOUNT_ID, USER_ID);

            assertThat(isOwned).isTrue();
            verify(accountRepository).existsByIdAndUserId(ACCOUNT_ID, USER_ID);
        }

        @Test
        @DisplayName("Should return false when account does not belong to user")
        void shouldReturnFalseWhenAccountDoesNotBelongToUser() {
            given(accountRepository.existsByIdAndUserId(ACCOUNT_ID, USER_ID)).willReturn(false);

            boolean isOwned = accountService.isAccountOwnedByUser(ACCOUNT_ID, USER_ID);

            assertThat(isOwned).isFalse();
            verify(accountRepository).existsByIdAndUserId(ACCOUNT_ID, USER_ID);
        }

        @Test
        @DisplayName("Should reject null account ID on ownership check")
        void shouldRejectNullAccountId() {
            assertThatThrownBy(() -> accountService.isAccountOwnedByUser(null, USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessage("Account ID must not be null");
        }

        @Test
        @DisplayName("Should reject null user ID on ownership check")
        void shouldRejectNullUserId() {
            assertThatThrownBy(() -> accountService.isAccountOwnedByUser(ACCOUNT_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");
        }
    }

    @Nested
    @DisplayName("Balance Query")
    class BalanceQueryTests {

        @Test
        @DisplayName("Should return correct account balance as Money")
        void shouldReturnCorrectBalance() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 25_000L, AccountType.CUSTOMER);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

            Money balance = accountService.getBalance(ACCOUNT_ID);

            assertThat(balance).isEqualTo(Money.ofMinor(25_000L, "USD"));
            assertThat(balance.amountInMinorUnits()).isEqualTo(25_000L);
        }

        @Test
        @DisplayName("Should reject null account ID on balance query")
        void shouldRejectNullAccountIdOnBalanceQuery() {
            assertThatThrownBy(() -> accountService.getBalance(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account ID must not be null");

            verify(accountRepository, never()).findById(any(UUID.class));
        }

        @Test
        @DisplayName("Should throw AccountNotFoundException when account not found on balance query")
        void shouldThrowWhenAccountNotFoundOnBalanceQuery() {
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.getBalance(ACCOUNT_ID)).isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account not found with ID: " + ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("Lifecycle State Transitions")
    class LifecycleStateTransitionsTests {

        @Test
        @DisplayName("Should freeze active account successfully")
        void shouldFreezeActiveAccount() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 10_000L, AccountType.CUSTOMER);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.save(account)).willReturn(account);

            Account updated = accountService.freezeAccount(ACCOUNT_ID);

            assertThat(updated.getStatus()).isEqualTo(AccountStatus.FROZEN);
            assertThat(updated.isActive()).isFalse();
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("Should activate frozen account successfully")
        void shouldActivateFrozenAccount() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 10_000L, AccountType.CUSTOMER);
            account.freeze();
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.save(account)).willReturn(account);

            Account updated = accountService.activateAccount(ACCOUNT_ID);

            assertThat(updated.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(updated.isActive()).isTrue();
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("Should close account with zero balance successfully")
        void shouldCloseAccountWithZeroBalance() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 0L, AccountType.CUSTOMER);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.save(account)).willReturn(account);

            Account updated = accountService.closeAccount(ACCOUNT_ID);

            assertThat(updated.getStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(updated.isActive()).isFalse();
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("Should throw IllegalStateException when closing account with non-zero balance")
        void shouldThrowWhenClosingAccountWithNonZeroBalance() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 5_000L, AccountType.CUSTOMER);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.closeAccount(ACCOUNT_ID)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot close account with non-zero balance: 5000");

            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when freezing a closed account")
        void shouldThrowWhenFreezingClosedAccount() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 0L, AccountType.CUSTOMER);
            account.close();
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.freezeAccount(ACCOUNT_ID)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot freeze a closed account");

            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when activating a closed account")
        void shouldThrowWhenActivatingClosedAccount() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 0L, AccountType.CUSTOMER);
            account.close();
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.activateAccount(ACCOUNT_ID))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Cannot activate a closed account");

            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should be idempotent when freezing already frozen account")
        void shouldBeIdempotentWhenFreezingAlreadyFrozenAccount() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 10_000L, AccountType.CUSTOMER);
            account.freeze();
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.save(account)).willReturn(account);

            Account updated = accountService.freezeAccount(ACCOUNT_ID);

            assertThat(updated.getStatus()).isEqualTo(AccountStatus.FROZEN);
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("Should be idempotent when activating already active account")
        void shouldBeIdempotentWhenActivatingAlreadyActiveAccount() {
            Account account = createMockAccount(ACCOUNT_ID, "ACC1234567890", "USD", 10_000L, AccountType.CUSTOMER);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.save(account)).willReturn(account);

            Account updated = accountService.activateAccount(ACCOUNT_ID);

            assertThat(updated.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("Should reject null account ID on lifecycle operations")
        void shouldRejectNullAccountIdOnLifecycleOperations() {
            assertThatThrownBy(() -> accountService.freezeAccount(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account ID must not be null");

            assertThatThrownBy(() -> accountService.activateAccount(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account ID must not be null");

            assertThatThrownBy(() -> accountService.closeAccount(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account ID must not be null");

            verify(accountRepository, never()).findById(any(UUID.class));
        }

        @Test
        @DisplayName("Should throw AccountNotFoundException when account not found on lifecycle operations")
        void shouldThrowWhenAccountNotFoundOnLifecycleOperations() {
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.freezeAccount(ACCOUNT_ID))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account not found with ID: " + ACCOUNT_ID);

            assertThatThrownBy(() -> accountService.activateAccount(ACCOUNT_ID))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account not found with ID: " + ACCOUNT_ID);

            assertThatThrownBy(() -> accountService.closeAccount(ACCOUNT_ID))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account not found with ID: " + ACCOUNT_ID);
        }
    }
}
