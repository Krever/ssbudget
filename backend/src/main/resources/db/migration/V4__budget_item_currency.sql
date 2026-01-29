-- Add currency to budget items (planned expenses, incomes, estimated expenses)
-- Default to PLN for existing rows, then make NOT NULL
ALTER TABLE expense_definitions ADD COLUMN currency TEXT NOT NULL DEFAULT 'PLN';
