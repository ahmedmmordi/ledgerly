package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

@FunctionalInterface
public interface TransferRule {

    void validate(TransferContext context);
}
