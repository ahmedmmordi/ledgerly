CREATE TABLE accounts (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    account_number VARCHAR(34) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT chk_accounts_account_number_format CHECK (account_number ~ '^[A-Z0-9]{8,34}$'),
    CONSTRAINT chk_accounts_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_accounts_type CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_accounts_balance CHECK (type = 'SYSTEM' OR balance >= 0)
);

CREATE INDEX idx_accounts_user_id ON accounts (user_id);


CREATE TABLE transfers (
    id UUID NOT NULL,
    from_account_id UUID NOT NULL,
    to_account_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    description VARCHAR(255),
    failure_reason VARCHAR(255),
    initiated_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_transfers PRIMARY KEY (id),
    CONSTRAINT fk_transfers_from_account FOREIGN KEY (from_account_id) REFERENCES accounts(id),
    CONSTRAINT fk_transfers_to_account FOREIGN KEY (to_account_id) REFERENCES accounts(id),
    CONSTRAINT chk_transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transfers_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfers_from_account_created ON transfers (from_account_id, created_at DESC);
CREATE INDEX idx_transfers_to_account_created ON transfers (to_account_id, created_at DESC);
CREATE INDEX idx_transfers_status ON transfers (status);


CREATE TABLE ledger_entries (
    id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    account_id UUID NOT NULL,
    type VARCHAR(10) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_transfer FOREIGN KEY (transfer_id) REFERENCES transfers(id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT uq_ledger_entries_transfer_account UNIQUE (transfer_id, account_id),
    CONSTRAINT chk_ledger_entries_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_transfer_id ON ledger_entries (transfer_id);
