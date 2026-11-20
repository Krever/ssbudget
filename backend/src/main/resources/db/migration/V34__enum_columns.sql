-- A column that holds one value of a fixed set (a role, a status, a kind) now accepts only the values of that set.
--
-- Before, such a column was plain text. The application reads it into an enumeration, and a value that the enumeration does not know makes
-- the read fail: ONE bad row (from a script, or from a newer build after a restore) then fails the whole list that holds it, and a page shows
-- nothing. With this, the bad value is refused at the write, where the cause is.
--
-- These are triggers and not CHECK constraints. SQLite can add a CHECK only by making the table again; a trigger gives the same refusal and
-- leaves the tables as they are. Two triggers for each column: one for an insert, one for an update of the column.
--
-- TO ADD A VALUE to an enumeration, write a migration that drops the two triggers of its column and creates them again with the new list.
-- `EnumColumnsSpec` fails when the list here and the enumeration in the code are not the same. A migration that makes one of these tables
-- again must create the triggers of that table again: a trigger goes with its table.

CREATE TRIGGER chk_accounts_role_ins BEFORE INSERT ON accounts
WHEN NEW.role NOT IN ('spending', 'holding', 'savings', 'investment')
BEGIN SELECT RAISE(ABORT, 'accounts.role: not a value of the set'); END;

CREATE TRIGGER chk_accounts_role_upd BEFORE UPDATE OF role ON accounts
WHEN NEW.role NOT IN ('spending', 'holding', 'savings', 'investment')
BEGIN SELECT RAISE(ABORT, 'accounts.role: not a value of the set'); END;

CREATE TRIGGER chk_accounts_balance_source_ins BEFORE INSERT ON accounts
WHEN NEW.balance_source NOT IN ('manual', 'bank', 'card_group')
BEGIN SELECT RAISE(ABORT, 'accounts.balance_source: not a value of the set'); END;

CREATE TRIGGER chk_accounts_balance_source_upd BEFORE UPDATE OF balance_source ON accounts
WHEN NEW.balance_source NOT IN ('manual', 'bank', 'card_group')
BEGIN SELECT RAISE(ABORT, 'accounts.balance_source: not a value of the set'); END;

CREATE TRIGGER chk_bank_connections_status_ins BEFORE INSERT ON bank_connections
WHEN NEW.status NOT IN ('pending', 'active', 'expired', 'revoked')
BEGIN SELECT RAISE(ABORT, 'bank_connections.status: not a value of the set'); END;

CREATE TRIGGER chk_bank_connections_status_upd BEFORE UPDATE OF status ON bank_connections
WHEN NEW.status NOT IN ('pending', 'active', 'expired', 'revoked')
BEGIN SELECT RAISE(ABORT, 'bank_connections.status: not a value of the set'); END;

CREATE TRIGGER chk_bank_account_links_link_target_kind_ins BEFORE INSERT ON bank_account_links
WHEN NEW.link_target_kind NOT IN ('none', 'account', 'card_group')
BEGIN SELECT RAISE(ABORT, 'bank_account_links.link_target_kind: not a value of the set'); END;

CREATE TRIGGER chk_bank_account_links_link_target_kind_upd BEFORE UPDATE OF link_target_kind ON bank_account_links
WHEN NEW.link_target_kind NOT IN ('none', 'account', 'card_group')
BEGIN SELECT RAISE(ABORT, 'bank_account_links.link_target_kind: not a value of the set'); END;

CREATE TRIGGER chk_bank_transactions_status_ins BEFORE INSERT ON bank_transactions
WHEN NEW.status NOT IN ('booked', 'pending')
BEGIN SELECT RAISE(ABORT, 'bank_transactions.status: not a value of the set'); END;

CREATE TRIGGER chk_bank_transactions_status_upd BEFORE UPDATE OF status ON bank_transactions
WHEN NEW.status NOT IN ('booked', 'pending')
BEGIN SELECT RAISE(ABORT, 'bank_transactions.status: not a value of the set'); END;

CREATE TRIGGER chk_bank_transactions_category_source_ins BEFORE INSERT ON bank_transactions
WHEN NEW.category_source IS NOT NULL AND NEW.category_source NOT IN ('manual', 'rule')
BEGIN SELECT RAISE(ABORT, 'bank_transactions.category_source: not a value of the set'); END;

CREATE TRIGGER chk_bank_transactions_category_source_upd BEFORE UPDATE OF category_source ON bank_transactions
WHEN NEW.category_source IS NOT NULL AND NEW.category_source NOT IN ('manual', 'rule')
BEGIN SELECT RAISE(ABORT, 'bank_transactions.category_source: not a value of the set'); END;

CREATE TRIGGER chk_categories_budget_type_ins BEFORE INSERT ON categories
WHEN NEW.budget_type IS NOT NULL AND NEW.budget_type NOT IN ('steady', 'bill', 'subscription')
BEGIN SELECT RAISE(ABORT, 'categories.budget_type: not a value of the set'); END;

CREATE TRIGGER chk_categories_budget_type_upd BEFORE UPDATE OF budget_type ON categories
WHEN NEW.budget_type IS NOT NULL AND NEW.budget_type NOT IN ('steady', 'bill', 'subscription')
BEGIN SELECT RAISE(ABORT, 'categories.budget_type: not a value of the set'); END;

CREATE TRIGGER chk_categories_budget_method_ins BEFORE INSERT ON categories
WHEN NEW.budget_method NOT IN ('average', 'median', 'fixed')
BEGIN SELECT RAISE(ABORT, 'categories.budget_method: not a value of the set'); END;

CREATE TRIGGER chk_categories_budget_method_upd BEFORE UPDATE OF budget_method ON categories
WHEN NEW.budget_method NOT IN ('average', 'median', 'fixed')
BEGIN SELECT RAISE(ABORT, 'categories.budget_method: not a value of the set'); END;

CREATE TRIGGER chk_import_jobs_status_ins BEFORE INSERT ON import_jobs
WHEN NEW.status NOT IN ('running', 'succeeded', 'failed')
BEGIN SELECT RAISE(ABORT, 'import_jobs.status: not a value of the set'); END;

CREATE TRIGGER chk_import_jobs_status_upd BEFORE UPDATE OF status ON import_jobs
WHEN NEW.status NOT IN ('running', 'succeeded', 'failed')
BEGIN SELECT RAISE(ABORT, 'import_jobs.status: not a value of the set'); END;

CREATE TRIGGER chk_import_jobs_kind_ins BEFORE INSERT ON import_jobs
WHEN NEW.kind NOT IN ('import', 'sync_all')
BEGIN SELECT RAISE(ABORT, 'import_jobs.kind: not a value of the set'); END;

CREATE TRIGGER chk_import_jobs_kind_upd BEFORE UPDATE OF kind ON import_jobs
WHEN NEW.kind NOT IN ('import', 'sync_all')
BEGIN SELECT RAISE(ABORT, 'import_jobs.kind: not a value of the set'); END;

CREATE TRIGGER chk_daily_budget_snapshots_source_ins BEFORE INSERT ON daily_budget_snapshots
WHEN NEW.source NOT IN ('live', 'reconstructed')
BEGIN SELECT RAISE(ABORT, 'daily_budget_snapshots.source: not a value of the set'); END;

CREATE TRIGGER chk_daily_budget_snapshots_source_upd BEFORE UPDATE OF source ON daily_budget_snapshots
WHEN NEW.source NOT IN ('live', 'reconstructed')
BEGIN SELECT RAISE(ABORT, 'daily_budget_snapshots.source: not a value of the set'); END;
