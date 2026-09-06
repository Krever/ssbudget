-- How a category's monthly budget figure is DERIVED, alongside V14's budget_type which says how that figure is drawn down over a period.
--
--   budget_method          'average' | 'median' | 'fixed'
--   budget_lookback_months how many completed months the statistic sees; NULL = all history (only meaningful for average/median)
--   budget_fixed_cents     the figure itself when budget_method = 'fixed'; signed (negative for a category whose money comes in)
--
-- The defaults reproduce today's behaviour exactly: a mean over everything on record.
ALTER TABLE categories ADD COLUMN budget_method TEXT NOT NULL DEFAULT 'average';

ALTER TABLE categories ADD COLUMN budget_lookback_months INTEGER;

ALTER TABLE categories ADD COLUMN budget_fixed_cents INTEGER;
