package com.paytm.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Request rate / latency / error rate come for free from Micrometer's
 * http.server.requests timer (see application.yml percentile config,
 * exposed at /actuator/prometheus). These are the extra counters specific
 * to wallet/transfer domain events, called out explicitly by the exercise.
 */
@Component
public class DomainMetrics {

    private final Counter transfersCompleted;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter walletsCreated;

    public DomainMetrics(MeterRegistry registry) {
        this.transfersCompleted = Counter.builder("wallet_transfers_completed_total")
                .description("Transfers that moved money successfully")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet_transfers_declined_insufficient_funds_total")
                .description("Transfers declined because the source wallet lacked funds")
                .register(registry);
        this.idempotentReplays = Counter.builder("wallet_transfers_idempotent_replay_total")
                .description("POST /transfers calls that matched an existing idempotency_key")
                .register(registry);
        this.walletsCreated = Counter.builder("wallet_wallets_created_total")
                .description("Wallets created via POST /wallets")
                .register(registry);
    }

    public void incrementTransferCompleted() {
        transfersCompleted.increment();
    }

    public void incrementTransferDeclined() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void incrementIdempotentReplay() {
        idempotentReplays.increment();
    }

    public void incrementWalletCreated() {
        walletsCreated.increment();
    }
}
