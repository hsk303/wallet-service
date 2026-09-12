package com.paytm.wallet.service;

import com.paytm.wallet.exception.BadRequestException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.NotFoundException;
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
}
