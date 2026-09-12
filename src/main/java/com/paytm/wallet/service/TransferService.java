package com.paytm.wallet.service;

import com.paytm.wallet.exception.BadRequestException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.exception.TransferConflictException;
import com.paytm.wallet.metrics.DomainMetrics;
import com.paytm.wallet.model.Transfer;
import com.paytm.wallet.model.Wallet;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final TransactionTemplate transactionTemplate;
    private final DomainMetrics metrics;

    public TransferService(WalletRepository walletRepository, TransferRepository transferRepository,
                            PlatformTransactionManager transactionManager, DomainMetrics metrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // READ COMMITTED (Postgres's default) is enough: we never do a
        // read-modify-write of a balance in application code. The only
        // reads that matter for correctness (the balance check) happen
        // after the row is explicitly locked via SELECT ... FOR UPDATE, so
        // there is nothing left for a higher isolation level to protect
        // against here - and SERIALIZABLE would add retry-on-conflict
        // complexity for no extra safety in this design.
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public TransferResult transfer(UUID from, UUID to, long amountPaise, String idempotencyKey) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are required");
        }
        if (from.equals(to)) {
            throw new BadRequestException("from and to wallets must differ");
        }
        if (amountPaise <= 0) {
            throw new BadRequestException("amount_paise must be positive");
        }

        String requestHash = hashRequest(from, to, amountPaise);
        UUID candidateId = UUID.randomUUID();

        return transactionTemplate.execute(status -> {
            List<Wallet> locked = walletRepository.selectForUpdateSorted(from, to);
            if (locked.size() != 2) {
                throw new NotFoundException("from/to wallet does not exist");
            }

            boolean claimed = transferRepository.tryInsertPending(
                    candidateId, idempotencyKey, from, to, amountPaise, requestHash);

            if (!claimed) {
                // Someone already claimed this idempotency_key. Because the
                // claiming INSERT above blocks on a conflicting uncommitted
                // row, by the time our own INSERT reports "0 rows affected"
                // the other transaction has necessarily already committed -
                // so this read is guaranteed to see its final result, never
                // a half-applied one.
                Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency_key row vanished unexpectedly: " + idempotencyKey));
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            "idempotency_key '" + idempotencyKey + "' was already used with a different request body");
                }
                metrics.incrementIdempotentReplay();
                log.info("idempotent_replay_hit transferId={} idempotencyKey={}", existing.getId(), idempotencyKey);
                return TransferResult.from(existing);
            }

            log.info("transfer_created transferId={} from={} to={} amountPaise={}",
                    candidateId, from, to, amountPaise);

            Wallet fromWallet = locked.stream().filter(w -> w.getId().equals(from)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("locked wallet set missing 'from'"));

            if (fromWallet.getBalancePaise() < amountPaise) {
                transferRepository.updateStatus(candidateId, "DECLINED_INSUFFICIENT_FUNDS");
                metrics.incrementTransferDeclined();
                log.info("declined_insufficient_funds transferId={} from={} balance={} requested={}",
                        candidateId, from, fromWallet.getBalancePaise(), amountPaise);
                return TransferResult.declined(candidateId, from, to, amountPaise);
            }

            walletRepository.adjustBalance(from, -amountPaise);
            log.info("debited transferId={} wallet={} amountPaise={}", candidateId, from, amountPaise);
            walletRepository.adjustBalance(to, amountPaise);
            log.info("credited transferId={} wallet={} amountPaise={}", candidateId, to, amountPaise);

            transferRepository.updateStatus(candidateId, "COMPLETED");
            metrics.incrementTransferCompleted();
            return TransferResult.completed(candidateId, from, to, amountPaise);
        });
    }

    public TransferResult getById(UUID id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("transfer not found: " + id));
        return TransferResult.from(transfer);
    }

    public TransferResult reverse(UUID transferId, String idempotencyKey) {
        if (transferId == null) {
            throw new BadRequestException("transfer id is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("idempotency_key is required");
        }

        UUID candidateId = UUID.randomUUID();
        String requestHash = hashReversalRequest(transferId);

        return transactionTemplate.execute(status -> {
            Transfer original = transferRepository.findById(transferId)
                    .orElseThrow(() -> new NotFoundException("transfer not found: " + transferId));
            if (!"COMPLETED".equals(original.getStatus())) {
                throw new TransferConflictException("only completed transfers can be reversed");
            }
            if (original.getReversalOf() != null) {
                throw new TransferConflictException("a reversal cannot be reversed");
            }

            UUID reversalFrom = original.getToWalletId();
            UUID reversalTo = original.getFromWalletId();
            List<Wallet> locked = walletRepository.selectForUpdateSorted(reversalFrom, reversalTo);
            if (locked.size() != 2) {
                throw new NotFoundException("transfer wallet does not exist");
            }

            Transfer existingKey = transferRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existingKey != null) {
                if (!existingKey.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            "idempotency_key '" + idempotencyKey + "' was already used with a different reversal");
                }
                metrics.incrementIdempotentReplay();
                log.info("idempotent_replay_hit transferId={} idempotencyKey={}", existingKey.getId(), idempotencyKey);
                return TransferResult.from(existingKey);
            }

            if (transferRepository.hasReversal(original.getId())) {
                throw new TransferConflictException("transfer has already been reversed");
            }

            boolean claimed = transferRepository.tryInsertPending(
                    candidateId, idempotencyKey, reversalFrom, reversalTo,
                    original.getAmountPaise(), requestHash, original.getId());
            if (!claimed) {
                Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency_key row vanished unexpectedly: " + idempotencyKey));
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            "idempotency_key '" + idempotencyKey + "' was already used with a different reversal");
                }
                metrics.incrementIdempotentReplay();
                log.info("idempotent_replay_hit transferId={} idempotencyKey={}", existing.getId(), idempotencyKey);
                return TransferResult.from(existing);
            }

            Wallet source = locked.stream().filter(w -> w.getId().equals(reversalFrom)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("locked wallet set missing reversal source"));
            log.info("reversal_created transferId={} originalTransferId={} from={} to={} amountPaise={}",
                    candidateId, original.getId(), reversalFrom, reversalTo, original.getAmountPaise());

            if (source.getBalancePaise() < original.getAmountPaise()) {
                transferRepository.updateStatus(candidateId, "DECLINED_INSUFFICIENT_FUNDS");
                metrics.incrementTransferDeclined();
                log.info("reversal_declined_insufficient_funds transferId={} originalTransferId={} balance={} requested={}",
                        candidateId, original.getId(), source.getBalancePaise(), original.getAmountPaise());
                return TransferResult.declined(candidateId, reversalFrom, reversalTo, original.getAmountPaise());
            }

            walletRepository.adjustBalance(reversalFrom, -original.getAmountPaise());
            walletRepository.adjustBalance(reversalTo, original.getAmountPaise());
            transferRepository.updateStatus(candidateId, "COMPLETED");
            metrics.incrementTransferCompleted();
            log.info("reversal_completed transferId={} originalTransferId={} amountPaise={}",
                    candidateId, original.getId(), original.getAmountPaise());
            return TransferResult.completed(candidateId, reversalFrom, reversalTo, original.getAmountPaise());
        });
    }

    private String hashRequest(UUID from, UUID to, long amountPaise) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = from + "|" + to + "|" + amountPaise;
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String hashReversalRequest(UUID transferId) {
        return hashText("reverse|" + transferId);
    }

    private String hashText(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
