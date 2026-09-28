-- How many payments a Bill-type category budget expects per period (e.g. a kindergarten billed twice a month). Each payment that lands releases
-- its 1/N share of the budget; with the default of 1 the first payment settles it, which is the behaviour Bill had before this column existed.
ALTER TABLE categories ADD COLUMN bill_payments INTEGER NOT NULL DEFAULT 1;
