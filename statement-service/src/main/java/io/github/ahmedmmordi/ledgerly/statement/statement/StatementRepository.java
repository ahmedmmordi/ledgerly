package io.github.ahmedmmordi.ledgerly.statement.statement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

// MongoDB repository for monthly account statements.
@NullMarked
@Repository
public interface StatementRepository extends MongoRepository<Statement, String> {

    Optional<Statement> findByAccountIdAndYearMonth(UUID accountId, String yearMonth);

    Optional<Statement> findFirstByAccountIdOrderByYearMonthDesc(UUID accountId);

    boolean existsByAccountIdAndYearMonth(UUID accountId, String yearMonth);

    List<Statement> findByAccountIdOrderByYearMonthDesc(UUID accountId);

    @Query(value = "{'accountId': ?0, 'yearMonth': { '$gte': ?1, '$lte': ?2 }}", sort = "{'yearMonth': -1}")
    Page<Statement> findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(UUID accountId, String fromYearMonth,
            String toYearMonth, Pageable pageable);

    @Query(value = "{'accountId': ?0, 'yearMonth': { '$gte': ?1, '$lte': ?2 }}", sort = "{'yearMonth': 1}")
    List<Statement> findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(UUID accountId, String fromYearMonth,
            String toYearMonth);
}
