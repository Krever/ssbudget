-- User-defined categorization rules, plus provenance for transaction categories.
--
-- category_source records who set a transaction's category: 'manual' (hand-set by the user; the rule engine never overwrites it) or 'rule'
-- (assigned by the rule engine; re-evaluated whenever rules change). Existing categorized rows predate the rule engine and are all hand-set,
-- so they backfill to 'manual' and survive the first applyRules pass.

CREATE TABLE classification_rules (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    category_id TEXT NOT NULL REFERENCES categories(id),
    priority INTEGER NOT NULL,      -- ascending; first matching rule wins
    criteria TEXT NOT NULL,         -- JSON array of criteria (ANDed)
    created_at TEXT NOT NULL        -- ISO-8601
);

CREATE INDEX idx_rules_priority ON classification_rules(priority);

ALTER TABLE bank_transactions ADD COLUMN category_source TEXT;   -- 'manual' | 'rule' | NULL (uncategorized)

UPDATE bank_transactions SET category_source = 'manual' WHERE category_id IS NOT NULL;
