-- At most one period is open. The app closes one period and opens the next in one transaction; this index makes a second open period
-- impossible also for a bug or for a hand-made row. The index is on a constant, for the rows with no end: two such rows collide.
CREATE UNIQUE INDEX one_open_period ON periods ((1)) WHERE ended_at IS NULL;
