package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountService;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountType;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.domain.TransferNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.rule.TransferContext;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.rule.TransferRule;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService Unit Tests")
class TransferServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");
    private static final UUID ID_LOWER = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final UUID ID_HIGHER = UUID.fromString("0193297a-9a90-7888-8123-222222222222");
    private static final UUID TRANSFER_ID = UUID.fromString("0193297a-9a90-7888-8123-333333333333");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private TransferRule mockRule1;

    @Mock
    private TransferRule mockRule2;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(accountRepository, transferRepository, ledgerEntryRepository,
                accountService, List.of(mockRule1, mockRule2));
    }

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
    @DisplayName("Constructor Validation & Initialization")
    class ConstructorTests {

        @Test
        @DisplayName("Should reject null AccountRepository")
        void shouldRejectNullAccountRepository() {
            assertThatThrownBy(() -> new TransferService(null, transferRepository, ledgerEntryRepository,
                    accountService, List.of(mockRule1))).isInstanceOf(NullPointerException.class)
                            .hasMessage("AccountRepository must not be null");
        }

        @Test
        @DisplayName("Should reject null TransferRepository")
        void shouldRejectNullTransferRepository() {
            assertThatThrownBy(() -> new TransferService(accountRepository, null, ledgerEntryRepository, accountService,
                    List.of(mockRule1))).isInstanceOf(NullPointerException.class)
                            .hasMessage("TransferRepository must not be null");
        }

        @Test
        @DisplayName("Should reject null LedgerEntryRepository")
        void shouldRejectNullLedgerEntryRepository() {
            assertThatThrownBy(() -> new TransferService(accountRepository, transferRepository, null, accountService,
                    List.of(mockRule1))).isInstanceOf(NullPointerException.class)
                            .hasMessage("LedgerEntryRepository must not be null");
        }

        @Test
        @DisplayName("Should reject null AccountService")
        void shouldRejectNullAccountService() {
            assertThatThrownBy(() -> new TransferService(accountRepository, transferRepository, ledgerEntryRepository,
                    null, List.of(mockRule1))).isInstanceOf(NullPointerException.class)
                            .hasMessage("AccountService must not be null");
        }

        @Test
        @DisplayName("Should reject null rules list")
        void shouldRejectNullRulesList() {
            assertThatThrownBy(() -> new TransferService(accountRepository, transferRepository, ledgerEntryRepository,
                    accountService, null)).isInstanceOf(NullPointerException.class)
                            .hasMessage("Transfer rules must not be null");
        }

        @Test
        @DisplayName("Should reject empty rules list")
        void shouldRejectEmptyRulesList() {
            List<TransferRule> empty = Collections.emptyList();
            assertThatThrownBy(() -> new TransferService(accountRepository, transferRepository, ledgerEntryRepository,
                    accountService, empty)).isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Transfer rules must not be empty");
        }

        @Test
        @DisplayName("Should reject null rule element in rules list")
        void shouldRejectNullRuleElement() {
            List<TransferRule> rulesWithNull = Collections.singletonList(null);
            assertThatThrownBy(() -> new TransferService(accountRepository, transferRepository, ledgerEntryRepository,
                    accountService, rulesWithNull)).isInstanceOf(NullPointerException.class)
                            .hasMessage("Transfer rule must not be null");
        }

        @Test
        @DisplayName("Should sort rules deterministically using AnnotationAwareOrderComparator")
        void shouldSortRulesDeterministically() {
            List<String> executionOrder = new java.util.ArrayList<>();

            @Order(20)
            class RuleOrder20 implements TransferRule {

                @Override
                public void validate(TransferContext context) {
                    executionOrder.add("RULE_20");
                }
            }

            @Order(5)
            class RuleOrder5 implements TransferRule {

                @Override
                public void validate(TransferContext context) {
                    executionOrder.add("RULE_5");
                }
            }

            TransferService service = new TransferService(accountRepository, transferRepository, ledgerEntryRepository,
                    accountService, List.of(new RuleOrder20(), new RuleOrder5()));

            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            service.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID);

            assertThat(executionOrder).containsExactly("RULE_5", "RULE_20");
        }
    }

    @Nested
    @DisplayName("Deterministic Locking Order (Deadlock Prevention)")
    class DeterministicLockingTests {

        @Test
        @DisplayName("Should lock lower ID first then higher ID when fromAccountId < toAccountId")
        void shouldLockLowerFirstWhenFromIsSmaller() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID);

            InOrder inOrder = inOrder(accountRepository);
            inOrder.verify(accountRepository).findByIdForUpdate(ID_LOWER);
            inOrder.verify(accountRepository).findByIdForUpdate(ID_HIGHER);
        }

        @Test
        @DisplayName("Should lock lower ID first then higher ID when fromAccountId > toAccountId")
        void shouldLockLowerFirstWhenFromIsLarger() {
            Account sender = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(receiver));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(sender));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            transferService.transfer(ID_HIGHER, ID_LOWER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID);

            InOrder inOrder = inOrder(accountRepository);
            inOrder.verify(accountRepository).findByIdForUpdate(ID_LOWER);
            inOrder.verify(accountRepository).findByIdForUpdate(ID_HIGHER);
        }
    }

    @Nested
    @DisplayName("Atomic Transfer Execution & Double-Entry Bookkeeping")
    class AtomicTransferExecutionTests {

        @Test
        @DisplayName("Should execute transfer successfully, updating balances and persisting transfer and balanced ledger entries")
        void shouldExecuteTransferSuccessfully() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            Money transferAmount = Money.ofMinor(3_000L, "USD");
            Transfer completed = transferService.transfer(ID_LOWER, ID_HIGHER, transferAmount, "Payment for services",
                    USER_ID);

            assertThat(completed).isNotNull();
            assertThat(completed.getId()).isEqualTo(TRANSFER_ID);
            assertThat(completed.getFromAccountId()).isEqualTo(ID_LOWER);
            assertThat(completed.getToAccountId()).isEqualTo(ID_HIGHER);
            assertThat(completed.getAmount()).isEqualTo(transferAmount);
            assertThat(completed.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(completed.getDescription()).isEqualTo("Payment for services");
            assertThat(completed.getInitiatedByUserId()).isEqualTo(USER_ID);

            assertThat(sender.getBalance()).isEqualTo(Money.ofMinor(7_000L, "USD"));
            assertThat(receiver.getBalance()).isEqualTo(Money.ofMinor(8_000L, "USD"));

            verify(accountRepository).save(sender);
            verify(accountRepository).save(receiver);

            ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
            verify(ledgerEntryRepository, org.mockito.Mockito.times(2)).save(entryCaptor.capture());

            List<LedgerEntry> entries = entryCaptor.getAllValues();
            LedgerEntry debitEntry = entries.get(0);
            LedgerEntry creditEntry = entries.get(1);

            assertThat(debitEntry.getType()).isEqualTo(EntryType.DEBIT);
            assertThat(debitEntry.getTransferId()).isEqualTo(TRANSFER_ID);
            assertThat(debitEntry.getAccountId()).isEqualTo(ID_LOWER);
            assertThat(debitEntry.getAmount(Currency.getInstance("USD"))).isEqualTo(transferAmount);
            assertThat(debitEntry.getBalanceAfter(Currency.getInstance("USD"))).isEqualTo(Money.ofMinor(7_000L, "USD"));

            assertThat(creditEntry.getType()).isEqualTo(EntryType.CREDIT);
            assertThat(creditEntry.getTransferId()).isEqualTo(TRANSFER_ID);
            assertThat(creditEntry.getAccountId()).isEqualTo(ID_HIGHER);
            assertThat(creditEntry.getAmount(Currency.getInstance("USD"))).isEqualTo(transferAmount);
            assertThat(creditEntry.getBalanceAfter(Currency.getInstance("USD")))
                    .isEqualTo(Money.ofMinor(8_000L, "USD"));

            assertThat(debitEntry.getAmount()).isEqualTo(creditEntry.getAmount());
        }

        @Test
        @DisplayName("Should execute transfer successfully with null description and null initiatedByUserId")
        void shouldExecuteTransferWithNullDescriptionAndUser() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            Transfer completed = transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), null,
                    null);

            assertThat(completed).isNotNull();
            assertThat(completed.getDescription()).isNull();
            assertThat(completed.getInitiatedByUserId()).isNull();
        }

        @Test
        @DisplayName("Should sanitize whitespace-only description to null")
        void shouldSanitizeBlankDescriptionToNull() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            Transfer completed = transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "   ",
                    USER_ID);

            assertThat(completed).isNotNull();
            assertThat(completed.getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("Deposit via System Cash Vault")
    class DepositTests {

        private static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");

        @Test
        @DisplayName("Should execute deposit by debiting system account and crediting customer account")
        void shouldExecuteDepositSuccessfully() {
            Account systemAccount = createMockAccount(SYSTEM_ACCOUNT_ID, "SYSUSD00000001", "USD", 0L,
                    AccountType.SYSTEM);
            Account customerAccount = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L,
                    AccountType.CUSTOMER);

            given(accountService.getOrCreateSystemAccount(USER_ID, "USD")).willReturn(systemAccount);

            UUID firstId = SYSTEM_ACCOUNT_ID.compareTo(ID_HIGHER) < 0 ? SYSTEM_ACCOUNT_ID : ID_HIGHER;
            UUID secondId = SYSTEM_ACCOUNT_ID.compareTo(ID_HIGHER) < 0 ? ID_HIGHER : SYSTEM_ACCOUNT_ID;

            Account firstLocked = firstId.equals(SYSTEM_ACCOUNT_ID) ? systemAccount : customerAccount;
            Account secondLocked = secondId.equals(SYSTEM_ACCOUNT_ID) ? systemAccount : customerAccount;

            given(accountRepository.findByIdForUpdate(firstId)).willReturn(Optional.of(firstLocked));
            given(accountRepository.findByIdForUpdate(secondId)).willReturn(Optional.of(secondLocked));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            Money depositAmount = Money.ofMinor(10_000L, "USD");
            Transfer result = transferService.deposit(ID_HIGHER, depositAmount, "ATM Cash In", USER_ID);

            assertThat(result).isNotNull();
            assertThat(result.getFromAccountId()).isEqualTo(SYSTEM_ACCOUNT_ID);
            assertThat(result.getToAccountId()).isEqualTo(ID_HIGHER);
            assertThat(result.getAmount()).isEqualTo(depositAmount);
            assertThat(result.getDescription()).isEqualTo("ATM Cash In");

            assertThat(systemAccount.getBalance()).isEqualTo(Money.ofMinor(-10_000L, "USD"));
            assertThat(customerAccount.getBalance()).isEqualTo(Money.ofMinor(15_000L, "USD"));

            verify(accountRepository).save(systemAccount);
            verify(accountRepository).save(customerAccount);
            verify(ledgerEntryRepository, org.mockito.Mockito.times(2)).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("Should use default description 'Cash Deposit' when deposit description is blank or null")
        void shouldUseDefaultDescriptionWhenNullOrBlank() {
            Account systemAccount = createMockAccount(SYSTEM_ACCOUNT_ID, "SYSUSD00000001", "USD", 0L,
                    AccountType.SYSTEM);
            Account customerAccount = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 0L, AccountType.CUSTOMER);

            given(accountService.getOrCreateSystemAccount(USER_ID, "USD")).willReturn(systemAccount);

            UUID firstId = SYSTEM_ACCOUNT_ID.compareTo(ID_HIGHER) < 0 ? SYSTEM_ACCOUNT_ID : ID_HIGHER;
            UUID secondId = SYSTEM_ACCOUNT_ID.compareTo(ID_HIGHER) < 0 ? ID_HIGHER : SYSTEM_ACCOUNT_ID;
            Account firstLocked = firstId.equals(SYSTEM_ACCOUNT_ID) ? systemAccount : customerAccount;
            Account secondLocked = secondId.equals(SYSTEM_ACCOUNT_ID) ? systemAccount : customerAccount;

            given(accountRepository.findByIdForUpdate(firstId)).willReturn(Optional.of(firstLocked));
            given(accountRepository.findByIdForUpdate(secondId)).willReturn(Optional.of(secondLocked));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            Transfer result = transferService.deposit(ID_HIGHER, Money.ofMinor(5_000L, "USD"), "   ", USER_ID);

            assertThat(result.getDescription()).isEqualTo("Cash Deposit");
        }

        @Test
        @DisplayName("Should reject null account ID on deposit")
        void shouldRejectNullAccountIdOnDeposit() {
            assertThatThrownBy(() -> transferService.deposit(null, Money.ofMinor(1_000L, "USD"), "Deposit", USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessage("Account ID must not be null");
        }

        @Test
        @DisplayName("Should reject null amount on deposit")
        void shouldRejectNullAmountOnDeposit() {
            assertThatThrownBy(() -> transferService.deposit(ID_HIGHER, null, "Deposit", USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessage("Amount must not be null");
        }

        @Test
        @DisplayName("Should reject null initiatedByUserId on deposit")
        void shouldRejectNullUserIdOnDeposit() {
            assertThatThrownBy(() -> transferService.deposit(ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Deposit", null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Initiated by user ID must not be null");
        }

        @Test
        @DisplayName("Should reject non-positive amount on deposit without touching account service or database")
        void shouldRejectNonPositiveAmountOnDeposit() {
            assertThatThrownBy(() -> transferService.deposit(ID_HIGHER, Money.ofMinor(0L, "USD"), "Deposit", USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Transfer amount must be strictly positive");

            assertThatThrownBy(
                    () -> transferService.deposit(ID_HIGHER, Money.ofMinor(-500L, "USD"), "Deposit", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Transfer amount must be strictly positive");

            verify(accountService, never()).getOrCreateSystemAccount(any(), any());
            verify(accountRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("Should reject deposit when target account is the system account itself")
        void shouldRejectDepositIntoSystemAccountItself() {
            Account systemAccount = createMockAccount(SYSTEM_ACCOUNT_ID, "SYSUSD00000001", "USD", 0L,
                    AccountType.SYSTEM);
            given(accountService.getOrCreateSystemAccount(USER_ID, "USD")).willReturn(systemAccount);

            assertThatThrownBy(
                    () -> transferService.deposit(SYSTEM_ACCOUNT_ID, Money.ofMinor(1_000L, "USD"), "Deposit", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("From account and to account must be distinct");

            verify(accountRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("Should throw AccountNotFoundException when target account does not exist on deposit")
        void shouldThrowWhenTargetAccountNotFoundOnDeposit() {
            Account systemAccount = createMockAccount(SYSTEM_ACCOUNT_ID, "SYSUSD00000001", "USD", 0L,
                    AccountType.SYSTEM);
            given(accountService.getOrCreateSystemAccount(USER_ID, "USD")).willReturn(systemAccount);

            UUID firstId = SYSTEM_ACCOUNT_ID.compareTo(ID_HIGHER) < 0 ? SYSTEM_ACCOUNT_ID : ID_HIGHER;
            UUID secondId = SYSTEM_ACCOUNT_ID.compareTo(ID_HIGHER) < 0 ? ID_HIGHER : SYSTEM_ACCOUNT_ID;

            Account firstLocked = firstId.equals(SYSTEM_ACCOUNT_ID) ? systemAccount : null;

            given(accountRepository.findByIdForUpdate(firstId)).willReturn(Optional.ofNullable(firstLocked));
            if (firstLocked != null) {
                given(accountRepository.findByIdForUpdate(secondId)).willReturn(Optional.empty());
            }

            assertThatThrownBy(
                    () -> transferService.deposit(ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Deposit", USER_ID))
                            .isInstanceOf(AccountNotFoundException.class).satisfies(ex -> {
                                AccountNotFoundException anfe = (AccountNotFoundException) ex;
                                assertThat(anfe.getAccountId()).isEqualTo(ID_HIGHER);
                            });
        }
    }

    @Nested
    @DisplayName("Pre-Lock Validation (Fail-Fast)")
    class PreLockValidationTests {

        @Test
        @DisplayName("Should reject null fromAccountId without locking")
        void shouldRejectNullFromAccountId() {
            assertThatThrownBy(
                    () -> transferService.transfer(null, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(NullPointerException.class).hasMessage("From account ID must not be null");

            verify(accountRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("Should reject null toAccountId without locking")
        void shouldRejectNullToAccountId() {
            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, null, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(NullPointerException.class).hasMessage("To account ID must not be null");

            verify(accountRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("Should reject null amount without locking")
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> transferService.transfer(ID_LOWER, ID_HIGHER, null, "Test", USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessage("Amount must not be null");

            verify(accountRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("Should reject self-transfer without locking")
        void shouldRejectSelfTransfer() {
            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_LOWER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("From account and to account must be distinct");

            verify(accountRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("Should reject non-positive amount without locking")
        void shouldRejectNonPositiveAmount() {
            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(0L, "USD"), "Test", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Transfer amount must be strictly positive");

            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(-1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Transfer amount must be strictly positive");

            verify(accountRepository, never()).findByIdForUpdate(any());
        }
    }

    @Nested
    @DisplayName("Rule Pipeline & Failure Rollback")
    class RulePipelineAndFailureRollbackTests {

        @Test
        @DisplayName("Should execute all rules in pipeline with transfer context")
        void shouldExecuteAllRulesInPipeline() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));
            given(transferRepository.save(any(Transfer.class))).willAnswer(inv -> {
                Transfer t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", TRANSFER_ID);
                return t;
            });

            Money amount = Money.ofMinor(1_000L, "USD");
            transferService.transfer(ID_LOWER, ID_HIGHER, amount, "Test", USER_ID);

            ArgumentCaptor<TransferContext> captor1 = ArgumentCaptor.forClass(TransferContext.class);
            ArgumentCaptor<TransferContext> captor2 = ArgumentCaptor.forClass(TransferContext.class);

            verify(mockRule1).validate(captor1.capture());
            verify(mockRule2).validate(captor2.capture());

            assertThat(captor1.getValue().fromAccount()).isSameAs(sender);
            assertThat(captor1.getValue().toAccount()).isSameAs(receiver);
            assertThat(captor1.getValue().amount()).isEqualTo(amount);

            assertThat(captor2.getValue().fromAccount()).isSameAs(sender);
        }

        @Test
        @DisplayName("Should propagate InsufficientFundsException and perform zero mutations when rule fails")
        void shouldRollbackWhenInsufficientFunds() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 1_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));

            Money amount = Money.ofMinor(5_000L, "USD");
            doThrow(new InsufficientFundsException(ID_LOWER, amount, sender.getBalance())).when(mockRule1)
                    .validate(any());

            assertThatThrownBy(() -> transferService.transfer(ID_LOWER, ID_HIGHER, amount, "Test", USER_ID))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(sender.getBalance()).isEqualTo(Money.ofMinor(1_000L, "USD"));
            assertThat(receiver.getBalance()).isEqualTo(Money.ofMinor(5_000L, "USD"));

            verify(mockRule2, never()).validate(any());
            verify(accountRepository, never()).save(any());
            verify(transferRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should propagate CurrencyMismatchException and perform zero mutations when currencies differ")
        void shouldRollbackWhenCurrencyMismatch() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "EUR", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));

            doThrow(new CurrencyMismatchException(Currency.getInstance("USD"), Currency.getInstance("EUR")))
                    .when(mockRule1).validate(any());

            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(CurrencyMismatchException.class);

            verify(accountRepository, never()).save(any());
            verify(transferRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should propagate AccountNotActiveException when an account is not active")
        void shouldRollbackWhenAccountNotActive() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);
            Account receiver = createMockAccount(ID_HIGHER, "ACC2222222222", "USD", 5_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.of(receiver));

            doThrow(new AccountNotActiveException(ID_LOWER, "FROZEN")).when(mockRule1).validate(any());

            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(AccountNotActiveException.class)
                            .hasMessage("Account " + ID_LOWER + " is not active (status: FROZEN)");

            verify(accountRepository, never()).save(any());
            verify(transferRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Missing Account Handling")
    class MissingAccountTests {

        @Test
        @DisplayName("Should throw AccountNotFoundException when sender account does not exist")
        void shouldThrowWhenSenderNotFound() {
            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.empty());

            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(AccountNotFoundException.class).satisfies(ex -> {
                                AccountNotFoundException anfe = (AccountNotFoundException) ex;
                                assertThat(anfe.getAccountId()).isEqualTo(ID_LOWER);
                            });

            verify(accountRepository, never()).findByIdForUpdate(ID_HIGHER);
            verify(transferRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw AccountNotFoundException when receiver account does not exist")
        void shouldThrowWhenReceiverNotFound() {
            Account sender = createMockAccount(ID_LOWER, "ACC1111111111", "USD", 10_000L, AccountType.CUSTOMER);

            given(accountRepository.findByIdForUpdate(ID_LOWER)).willReturn(Optional.of(sender));
            given(accountRepository.findByIdForUpdate(ID_HIGHER)).willReturn(Optional.empty());

            assertThatThrownBy(
                    () -> transferService.transfer(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test", USER_ID))
                            .isInstanceOf(AccountNotFoundException.class).satisfies(ex -> {
                                AccountNotFoundException anfe = (AccountNotFoundException) ex;
                                assertThat(anfe.getAccountId()).isEqualTo(ID_HIGHER);
                            });

            verify(transferRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Query Methods")
    class QueryMethodsTests {

        @Test
        @DisplayName("Should retrieve transfer by ID successfully")
        void shouldGetTransferByIdSuccessfully() {
            Transfer mockTransfer = Transfer.createCompleted(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "Test",
                    USER_ID);
            ReflectionTestUtils.setField(mockTransfer, "id", TRANSFER_ID);
            given(transferRepository.findById(TRANSFER_ID)).willReturn(Optional.of(mockTransfer));

            Transfer result = transferService.getTransfer(TRANSFER_ID);

            assertThat(result).isSameAs(mockTransfer);
            assertThat(result.getId()).isEqualTo(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should throw TransferNotFoundException when transfer ID is not found")
        void shouldThrowWhenTransferNotFound() {
            given(transferRepository.findById(TRANSFER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transferService.getTransfer(TRANSFER_ID))
                    .isInstanceOf(TransferNotFoundException.class)
                    .hasMessage("Transfer not found with ID: " + TRANSFER_ID).satisfies(ex -> {
                        TransferNotFoundException tnfe = (TransferNotFoundException) ex;
                        assertThat(tnfe.getTransferId()).isEqualTo(TRANSFER_ID);
                    });
        }

        @Test
        @DisplayName("Should reject null transfer ID on getTransfer")
        void shouldRejectNullTransferIdOnGet() {
            assertThatThrownBy(() -> transferService.getTransfer(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Transfer ID must not be null");
        }

        @Test
        @DisplayName("Should retrieve paginated transfers by account ID successfully")
        void shouldGetTransfersByAccountId() {
            Pageable pageable = PageRequest.of(0, 10);
            Transfer t1 = Transfer.createCompleted(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "T1", USER_ID);
            Page<Transfer> page = new PageImpl<>(List.of(t1));

            given(transferRepository.findByAccountId(ID_LOWER, pageable)).willReturn(page);

            Page<Transfer> result = transferService.getTransfersByAccountId(ID_LOWER, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0)).isSameAs(t1);
            verify(transferRepository).findByAccountId(ID_LOWER, pageable);
        }

        @Test
        @DisplayName("Should return empty page when no transfers found for account")
        void shouldReturnEmptyPageWhenNoTransfersFound() {
            Pageable pageable = PageRequest.of(0, 10);
            given(transferRepository.findByAccountId(ID_LOWER, pageable)).willReturn(Page.empty(pageable));

            Page<Transfer> result = transferService.getTransfersByAccountId(ID_LOWER, pageable);

            assertThat(result.isEmpty()).isTrue();
            verify(transferRepository).findByAccountId(ID_LOWER, pageable);
        }

        @Test
        @DisplayName("Should reject null account ID or null pageable on getTransfersByAccountId")
        void shouldRejectNullsOnGetTransfersByAccountId() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> transferService.getTransfersByAccountId(null, pageable))
                    .isInstanceOf(NullPointerException.class).hasMessage("Account ID must not be null");

            assertThatThrownBy(() -> transferService.getTransfersByAccountId(ID_LOWER, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Pageable must not be null");
        }

        @Test
        @DisplayName("Should retrieve paginated transfers by initiated user ID successfully")
        void shouldGetTransfersByInitiatedUserId() {
            Pageable pageable = PageRequest.of(0, 10);
            Transfer t1 = Transfer.createCompleted(ID_LOWER, ID_HIGHER, Money.ofMinor(1_000L, "USD"), "T1", USER_ID);
            Page<Transfer> page = new PageImpl<>(List.of(t1));

            given(transferRepository.findByInitiatedByUserId(USER_ID, pageable)).willReturn(page);

            Page<Transfer> result = transferService.getTransfersByInitiatedUserId(USER_ID, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0)).isSameAs(t1);
            verify(transferRepository).findByInitiatedByUserId(USER_ID, pageable);
        }

        @Test
        @DisplayName("Should reject null user ID or null pageable on getTransfersByInitiatedUserId")
        void shouldRejectNullsOnGetTransfersByInitiatedUserId() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> transferService.getTransfersByInitiatedUserId(null, pageable))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");

            assertThatThrownBy(() -> transferService.getTransfersByInitiatedUserId(USER_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Pageable must not be null");
        }

        @Test
        @DisplayName("Should retrieve ledger entries by transfer ID ordered by type ascending")
        void shouldGetLedgerEntriesByTransferId() {
            LedgerEntry debit = LedgerEntry.createDebit(TRANSFER_ID, ID_LOWER, Money.ofMinor(1_000L, "USD"),
                    Money.ofMinor(9_000L, "USD"));
            LedgerEntry credit = LedgerEntry.createCredit(TRANSFER_ID, ID_HIGHER, Money.ofMinor(1_000L, "USD"),
                    Money.ofMinor(6_000L, "USD"));

            given(ledgerEntryRepository.findByTransferIdOrderByTypeAsc(TRANSFER_ID)).willReturn(List.of(credit, debit));

            List<LedgerEntry> result = transferService.getLedgerEntriesByTransferId(TRANSFER_ID);

            assertThat(result).hasSize(2);
            assertThat(result).containsExactly(credit, debit);
            verify(ledgerEntryRepository).findByTransferIdOrderByTypeAsc(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should return empty list when no ledger entries exist for transfer ID")
        void shouldReturnEmptyListWhenNoEntriesFound() {
            given(ledgerEntryRepository.findByTransferIdOrderByTypeAsc(TRANSFER_ID))
                    .willReturn(Collections.emptyList());

            List<LedgerEntry> result = transferService.getLedgerEntriesByTransferId(TRANSFER_ID);

            assertThat(result).isEmpty();
            verify(ledgerEntryRepository).findByTransferIdOrderByTypeAsc(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should reject null transfer ID on getLedgerEntriesByTransferId")
        void shouldRejectNullTransferIdOnGetEntries() {
            assertThatThrownBy(() -> transferService.getLedgerEntriesByTransferId(null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Transfer ID must not be null");
        }

        @Test
        @DisplayName("Should retrieve paginated ledger entries by account ID in chronological descending order")
        void shouldGetLedgerEntriesByAccountId() {
            Pageable pageable = PageRequest.of(0, 10);
            LedgerEntry entry = LedgerEntry.createDebit(TRANSFER_ID, ID_LOWER, Money.ofMinor(1_000L, "USD"),
                    Money.ofMinor(9_000L, "USD"));
            Page<LedgerEntry> page = new PageImpl<>(List.of(entry));

            given(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(ID_LOWER, pageable)).willReturn(page);

            Page<LedgerEntry> result = transferService.getLedgerEntriesByAccountId(ID_LOWER, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0)).isSameAs(entry);
            verify(ledgerEntryRepository).findByAccountIdOrderByCreatedAtDesc(ID_LOWER, pageable);
        }

        @Test
        @DisplayName("Should reject null account ID or null pageable on getLedgerEntriesByAccountId")
        void shouldRejectNullsOnGetLedgerEntriesByAccountId() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> transferService.getLedgerEntriesByAccountId(null, pageable))
                    .isInstanceOf(NullPointerException.class).hasMessage("Account ID must not be null");

            assertThatThrownBy(() -> transferService.getLedgerEntriesByAccountId(ID_LOWER, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Pageable must not be null");
        }
    }
}
