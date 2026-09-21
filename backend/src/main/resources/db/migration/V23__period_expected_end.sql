-- A period now states when it is expected to end, instead of the app inferring it from a payday.
--
-- The old rule lived in Scala (Period.expectedEnd): "the 25th of the month, at least two weeks after the period started". It was hardcoded to one
-- paycheck a month landing on one day. A second monthly paycheck makes that rule wrong, and any rule written to replace it would be wrong for the
-- next change — so the date is stored per period and corrected by hand when a paycheck lands early or late. What remains derived is only the date
-- PROPOSED when a period opens (Period.defaultExpectedEnd: the same day of the following month).
--
-- Existing rows are backfilled with the OLD rule applied to their own start, so no closed period's history moves: the 25th of the start month,
-- pushed to the next month when that falls less than 14 days after the start. This is the one place the deleted rule survives, on purpose — it
-- reproduces what the app actually believed about each period, which a simpler `+1 month` would not.
--
-- The NOT NULL default is deliberately an absurd date rather than a plausible one. It is unreachable — PeriodRepository always names the column on
-- insert — so if one ever surfaces it should look obviously wrong rather than quietly pass for a real expectation.

ALTER TABLE periods ADD COLUMN expected_end TEXT NOT NULL DEFAULT '1970-01-01'; -- 'YYYY-MM-DD': when the next paycheck is expected, editable

UPDATE periods
SET expected_end = CASE
    WHEN date(started_at, 'start of month', '+24 days') < date(started_at, '+14 days')
        THEN date(started_at, 'start of month', '+24 days', '+1 month')
    ELSE date(started_at, 'start of month', '+24 days')
END;
