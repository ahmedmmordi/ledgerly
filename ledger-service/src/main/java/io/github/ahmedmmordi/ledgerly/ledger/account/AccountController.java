package io.github.ahmedmmordi.ledgerly.ledger.account;

import io.github.ahmedmmordi.ledgerly.ledger.account.dto.AccountResponse;
import io.github.ahmedmmordi.ledgerly.ledger.account.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createCustomerAccount(request.userId(), request.currency());
        AccountResponse response = AccountResponse.from(account);
        URI location = URI.create("/api/v1/accounts/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccountById(@PathVariable UUID id) {
        return ResponseEntity.ok(AccountResponse.from(accountService.getAccount(id)));
    }

    @GetMapping("/number/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccountByNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(AccountResponse.from(accountService.getAccountByNumber(accountNumber)));
    }

    @GetMapping
    public ResponseEntity<Page<AccountResponse>> getAccountsByUserId(@RequestParam UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AccountResponse> accounts = accountService.getAccountsByUserId(userId, pageable)
                .map(AccountResponse::from);
        return ResponseEntity.ok(accounts);
    }

    @PutMapping("/{id}/freeze")
    public ResponseEntity<AccountResponse> freezeAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(AccountResponse.from(accountService.freezeAccount(id)));
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<AccountResponse> activateAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(AccountResponse.from(accountService.activateAccount(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> closeAccount(@PathVariable UUID id) {
        accountService.closeAccount(id);
        return ResponseEntity.noContent().build();
    }
}
