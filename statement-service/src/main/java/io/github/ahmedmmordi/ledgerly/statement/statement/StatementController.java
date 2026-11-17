package io.github.ahmedmmordi.ledgerly.statement.statement;

import io.github.ahmedmmordi.ledgerly.statement.statement.dto.StatementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Statements", description = "Endpoints for querying monthly account statements and CSV transaction exports")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@RequiredArgsConstructor
@NullMarked
public class StatementController {

    private final StatementService statementService;

    @Operation(summary = "Query statements", description = "Retrieves paginated monthly bank statements for an account within a date range")
    @GetMapping("/statements")
    public ResponseEntity<Page<StatementResponse>> getStatements(
            @Parameter(description = "Account ID") @PathVariable UUID accountId,
            @Parameter(description = "Start month in YYYY-MM format", example = "2026-01") @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @Parameter(description = "End month in YYYY-MM format", example = "2026-12") @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth to,
            @PageableDefault(size = 12) Pageable pageable) {
        Page<StatementResponse> statements = statementService.getStatements(accountId, from, to, pageable);
        return ResponseEntity.ok(statements);
    }

    @Operation(summary = "Export statements CSV", description = "Streams CSV transaction export for an account within a date range")
    @GetMapping(value = { "/statements.csv", "/statements/csv" }, produces = "text/csv; charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> streamCsv(
            @Parameter(description = "Account ID") @PathVariable UUID accountId,
            @Parameter(description = "Start month in YYYY-MM format", example = "2026-01") @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @Parameter(description = "End month in YYYY-MM format", example = "2026-12") @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth to) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename("statements.csv", StandardCharsets.UTF_8).build();

        StreamingResponseBody responseBody = outputStream -> statementService.streamCsvEntries(accountId, from, to,
                outputStream);

        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(responseBody);
    }
}
