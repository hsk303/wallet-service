ALTER TABLE transfers
    ADD COLUMN reversal_of UUID REFERENCES transfers(id);

CREATE UNIQUE INDEX uq_transfers_reversal_of
    ON transfers (reversal_of)
    WHERE reversal_of IS NOT NULL;