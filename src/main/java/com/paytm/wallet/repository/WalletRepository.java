package com.paytm.wallet.repository;

import com.paytm.wallet.model.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately plain JdbcTemplate rather than JPA: money invariants live in a
 * couple of very specific SQL statements (a conditional unique-insert and a
 * sorted SELECT ... FOR UPDATE), and writing those out explicitly is easier
 * to reason about correctly than going through an ORM's dirty-checking /
 * optimistic-locking machinery.
 */
@Repository
public class WalletRepository {

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Race-free get-or-create. INSERT ... ON CONFLICT (user_id) DO NOTHING
     * relies on the unique constraint on wallets.user_id. If two callers race
     * to create the same user's wallet, Postgres serializes them: whichever
     * INSERT commits first "wins", and the loser's INSERT observes the
     * conflict and does nothing (Postgres makes the loser's statement wait
     * for the winner's transaction to finish before deciding there's a
     * conflict, so this is safe even when both attempts are concurrent).
     *
     * @return the newly created wallet, or empty if a wallet already existed
     *         for this user_id (caller should then look it up).
     */
    public Optional<Wallet> insertIfAbsent(UUID id, String userId) {
        int rows = jdbc.update(
                "INSERT INTO wallets (id, user_id, balance_paise) VALUES (?, ?, 0) " +
                        "ON CONFLICT (user_id) DO NOTHING",
                id, userId
        );
        if (rows == 0) {
            return Optional.empty();
        }
        return findById(id);
    }

    public Optional<Wallet> findByUserId(String userId) {
        List<Wallet> results = jdbc.query(
                "SELECT id, user_id, balance_paise, created_at FROM wallets WHERE user_id = ?",
                this::mapRow, userId
        );
        return results.stream().findFirst();
    }

    public Optional<Wallet> findById(UUID id) {
        List<Wallet> results = jdbc.query(
                "SELECT id, user_id, balance_paise, created_at FROM wallets WHERE id = ?",
                this::mapRow, id
        );
        return results.stream().findFirst();
    }

    /**
     * Locks both wallets involved in a transfer, always in ascending-id
     * order, inside the caller's active transaction. Two concurrent
     * transfers touching the same pair of wallets - even in opposite
     * directions (A->B and B->A at once) - always request their row locks
     * in this same global order, so the second one simply waits for the
     * first to finish. Neither can hold "its" lock while waiting for the
     * other's, so there is no circular wait and therefore no deadlock.
     *
     * <p>Must be called within a transaction; the locks are released on
     * commit/rollback of that transaction.
     */
    public List<Wallet> selectForUpdateSorted(UUID walletA, UUID walletB) {
        UUID lo = walletA.compareTo(walletB) <= 0 ? walletA : walletB;
        UUID hi = walletA.compareTo(walletB) <= 0 ? walletB : walletA;
        List<Wallet> locked = new java.util.ArrayList<>(2);
        findByIdForUpdate(lo).ifPresent(locked::add);
        findByIdForUpdate(hi).ifPresent(locked::add);
        return locked;
        }

        private Optional<Wallet> findByIdForUpdate(UUID id) {
        List<Wallet> results = jdbc.query(
            "SELECT id, user_id, balance_paise, created_at FROM wallets " +
                "WHERE id = ? FOR UPDATE",
            this::mapRow, id
        );
        return results.stream().findFirst();
    }

    /**
     * Applies a signed delta to a wallet's balance. Only safe to call for a
     * decrement after the caller has already verified sufficient funds while
     * holding this wallet's row lock (see selectForUpdateSorted) - this
     * method itself does not re-check the balance, by design, since by the
     * time it runs the check has already happened under lock.
     */
    public void adjustBalance(UUID walletId, long deltaPaise) {
        jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?",
                deltaPaise, walletId
        );
    }

    private Wallet mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Wallet(
                UUID.fromString(rs.getString("id")),
                rs.getString("user_id"),
                rs.getLong("balance_paise"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
