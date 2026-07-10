-- Built-in "internal transfer" rule: a transaction is internal when its counterparty account (IBAN) is one of the user's own linked accounts.
-- Detection runs on every import (see TransactionImportService.markInternalTransfers); this migration adds the column and backfills existing rows.

ALTER TABLE bank_transactions ADD COLUMN is_internal INTEGER NOT NULL DEFAULT 0;

UPDATE bank_transactions SET is_internal = 1
WHERE counterparty_account IS NOT NULL
  AND REPLACE(UPPER(counterparty_account), ' ', '') IN (
    SELECT REPLACE(UPPER(iban), ' ', '') FROM bank_account_links WHERE iban IS NOT NULL
  );

CREATE INDEX idx_bank_tx_internal ON bank_transactions(is_internal);
