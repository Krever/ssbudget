-- How a category's monthly budget predicts remaining spend: 'steady' | 'bill' | 'subscription'; NULL = not a budget.
ALTER TABLE categories ADD COLUMN budget_type TEXT;

-- Existing flagged budgets become time-based (steady). `monthly_budget` (V12) is now unused but kept to avoid a destructive column drop.
UPDATE categories SET budget_type = 'steady' WHERE monthly_budget = 1;
