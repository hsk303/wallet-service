package com.paytm.wallet.repository;

import com.paytm.wallet.model.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims an idempotency_key by attempting to insert a PENDING transfer
     * row. Relies on the unique constraint on transfers.idempotency_key.
     *
     * <p>This is the exactly-once mechanism: it is called from inside the
     * same DB transaction that will go on to move money, so the row only
     * ever becomes durably visible with its final status (COMPLETED /
     * DECLINED_INSUFFICIENT_FUNDS) once that transaction commits. A
     * concurrent duplicate's INSERT blocks on the same unique key until this
     * transaction finishes, then sees "no rows affected" and falls back to
     * reading the now-committed row - it never gets to race the money
     * movement itself.
     *
     * @return true if this call claimed the key (caller should proceed with
     *         the transfer); false if the key was already claimed (caller
     *         should look the existing row up).
     */
    public boolean tryInsertPending(UUID id, String idempotencyKey, UUID fromWalletId, UUID toWalletId,
                                     long amountPaise, String requestHash) {
        return tryInsertPending(id, idempotencyKey, fromWalletId, toWalletId, amountPaise, requestHash, null);
    }

    public boolean tryInsertPending(UUID id, String idempotencyKey, UUID fromWalletId, UUID toWalletId,
                                     long amountPaise, String requestHash, UUID reversalOf) {
        int rows = jdbc.update(
                "INSERT INTO transfers (id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, request_hash, reversal_of, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING') ON CONFLICT (idempotency_key) DO NOTHING",
                id, idempotencyKey, fromWalletId, toWalletId, amountPaise, requestHash, reversalOf
        );
        return rows > 0;
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        List<Transfer> results = jdbc.query(
                "SELECT id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, request_hash, reversal_of, status, created_at " +
                        "FROM transfers WHERE idempotency_key = ?",
                this::mapRow, idempotencyKey
        );
        return results.stream().findFirst();
    }

    public Optional<Transfer> findById(UUID id) {
        List<Transfer> results = jdbc.query(
                "SELECT id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, request_hash, reversal_of, status, created_at " +
                        "FROM transfers WHERE id = ?",
                this::mapRow, id
        );
        return results.stream().findFirst();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.update("UPDATE transfers SET status = ? WHERE id = ?", status, id);
    }

        public boolean hasReversal(UUID transferId) {
                Integer count = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM transfers WHERE reversal_of = ?",
                                Integer.class, transferId);
                return count != null && count > 0;
        }

    private Transfer mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Transfer(
                UUID.fromString(rs.getString("id")),
                rs.getString("idempotency_key"),
                UUID.fromString(rs.getString("from_wallet_id")),
                UUID.fromString(rs.getString("to_wallet_id")),
                rs.getLong("amount_paise"),
                rs.getString("request_hash"),
                rs.getObject("reversal_of", UUID.class),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
