CREATE TABLE one_time_expenses (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    amount_cents INTEGER NOT NULL,
    currency TEXT NOT NULL REFERENCES currency_settings(code),
    date TEXT NOT NULL
);

CREATE INDEX idx_one_time_expenses_date ON one_time_expenses(date);
