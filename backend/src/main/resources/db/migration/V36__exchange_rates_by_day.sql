-- An exchange rate is now the rate of ONE DAY. Before, a row had only the moment it was fetched, and the app fetched rates on a bank sync or
-- by hand: some rows a year. A figure at a past day (the balance of a foreign account at the start of a period) thus took a rate that could be
-- months old, and the market result did not see what the rate did.
--
-- `on_date` is the day of the reference rate (the source publishes one rate for each business day). One row for each pair and day: a second
-- fetch of the same day replaces the rate. The app fills the days that are missing itself (`CurrencyService.fillHistory`).
--
-- The old rows stay, each with the day it was fetched on. Of two rows of one pair and day, the newest stays. The fill replaces them with the rate
-- of the source for that day.
--
-- No reporting view reads this table.
CREATE TABLE exchange_rates_new (
    from_currency TEXT NOT NULL,
    to_currency TEXT NOT NULL,
    on_date TEXT NOT NULL,              -- YYYY-MM-DD: the day the rate is for
    rate INTEGER NOT NULL,              -- rate * 10000
    fetched_at TEXT NOT NULL,
    PRIMARY KEY (from_currency, to_currency, on_date)
);
INSERT INTO exchange_rates_new (from_currency, to_currency, on_date, rate, fetched_at)
SELECT r.from_currency, r.to_currency, substr(r.fetched_at, 1, 10), r.rate, r.fetched_at
FROM exchange_rates r
WHERE r.fetched_at = (SELECT MAX(n.fetched_at) FROM exchange_rates n
                      WHERE n.from_currency = r.from_currency AND n.to_currency = r.to_currency
                        AND substr(n.fetched_at, 1, 10) = substr(r.fetched_at, 1, 10))
GROUP BY r.from_currency, r.to_currency, substr(r.fetched_at, 1, 10);
DROP TABLE exchange_rates;
ALTER TABLE exchange_rates_new RENAME TO exchange_rates;
CREATE INDEX idx_exchange_rates_to_date ON exchange_rates(to_currency, on_date);
