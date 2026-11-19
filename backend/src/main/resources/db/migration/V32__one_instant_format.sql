-- Every instant is now stored in ONE text form: 'YYYY-MM-DDTHH:MM:SS.mmmZ' (UTC, milliseconds, 24 characters).
--
-- Before, an instant was stored as Java printed it: with 0, 3, 6 or 9 digits of the second. The columns are compared as text, and text of
-- different width does not sort as time does: '…:05Z' is after '…:05.300Z', because 'Z' is after '.'. So two instants inside one second could
-- be in the wrong order ("the balance at or before this instant" could take the wrong record). With one width, the order of the text is the
-- order of time. The application writes this form from now on (`DoobieMeta`, `Meta[Instant]`).
--
-- The digits after the milliseconds are cut, not rounded, as the application does. Nothing else of a row changes. The tables of the removed
-- planned items (`expense_definitions`, `expense_records`) are not read by any code and stay as they are.

UPDATE periods SET started_at = substr(substr(started_at, 1, length(started_at) - 1) || CASE WHEN length(started_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE started_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(started_at) <> 24;

UPDATE periods SET ended_at = substr(substr(ended_at, 1, length(ended_at) - 1) || CASE WHEN length(ended_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE ended_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(ended_at) <> 24;

UPDATE auth_config SET created_at = substr(substr(created_at, 1, length(created_at) - 1) || CASE WHEN length(created_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(created_at) <> 24;

UPDATE auth_config SET updated_at = substr(substr(updated_at, 1, length(updated_at) - 1) || CASE WHEN length(updated_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(updated_at) <> 24;

UPDATE sessions SET created_at = substr(substr(created_at, 1, length(created_at) - 1) || CASE WHEN length(created_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(created_at) <> 24;

UPDATE sessions SET expires_at = substr(substr(expires_at, 1, length(expires_at) - 1) || CASE WHEN length(expires_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE expires_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(expires_at) <> 24;

UPDATE sessions SET last_used_at = substr(substr(last_used_at, 1, length(last_used_at) - 1) || CASE WHEN length(last_used_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE last_used_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(last_used_at) <> 24;

UPDATE sessions SET confirmed_at = substr(substr(confirmed_at, 1, length(confirmed_at) - 1) || CASE WHEN length(confirmed_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE confirmed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(confirmed_at) <> 24;

UPDATE passkey_credentials SET created_at = substr(substr(created_at, 1, length(created_at) - 1) || CASE WHEN length(created_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(created_at) <> 24;

UPDATE passkey_credentials SET last_used_at = substr(substr(last_used_at, 1, length(last_used_at) - 1) || CASE WHEN length(last_used_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE last_used_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(last_used_at) <> 24;

UPDATE currency_settings SET enabled_at = substr(substr(enabled_at, 1, length(enabled_at) - 1) || CASE WHEN length(enabled_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE enabled_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(enabled_at) <> 24;

UPDATE accounts SET balance_updated_at = substr(substr(balance_updated_at, 1, length(balance_updated_at) - 1) || CASE WHEN length(balance_updated_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE balance_updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(balance_updated_at) <> 24;

UPDATE balance_snapshots SET recorded_at = substr(substr(recorded_at, 1, length(recorded_at) - 1) || CASE WHEN length(recorded_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE recorded_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(recorded_at) <> 24;

UPDATE exchange_rates SET fetched_at = substr(substr(fetched_at, 1, length(fetched_at) - 1) || CASE WHEN length(fetched_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE fetched_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(fetched_at) <> 24;

UPDATE bank_connections SET valid_until = substr(substr(valid_until, 1, length(valid_until) - 1) || CASE WHEN length(valid_until) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE valid_until GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(valid_until) <> 24;

UPDATE bank_connections SET created_at = substr(substr(created_at, 1, length(created_at) - 1) || CASE WHEN length(created_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(created_at) <> 24;

UPDATE bank_account_links SET last_synced_at = substr(substr(last_synced_at, 1, length(last_synced_at) - 1) || CASE WHEN length(last_synced_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE last_synced_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(last_synced_at) <> 24;

UPDATE bank_transactions SET booked_at = substr(substr(booked_at, 1, length(booked_at) - 1) || CASE WHEN length(booked_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE booked_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(booked_at) <> 24;

UPDATE bank_transactions SET imported_at = substr(substr(imported_at, 1, length(imported_at) - 1) || CASE WHEN length(imported_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE imported_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(imported_at) <> 24;

UPDATE classification_rules SET created_at = substr(substr(created_at, 1, length(created_at) - 1) || CASE WHEN length(created_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(created_at) <> 24;

UPDATE import_jobs SET started_at = substr(substr(started_at, 1, length(started_at) - 1) || CASE WHEN length(started_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE started_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(started_at) <> 24;

UPDATE import_jobs SET finished_at = substr(substr(finished_at, 1, length(finished_at) - 1) || CASE WHEN length(finished_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE finished_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(finished_at) <> 24;

UPDATE category_budget_overrides SET updated_at = substr(substr(updated_at, 1, length(updated_at) - 1) || CASE WHEN length(updated_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(updated_at) <> 24;

UPDATE analytics_state SET updated_at = substr(substr(updated_at, 1, length(updated_at) - 1) || CASE WHEN length(updated_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(updated_at) <> 24;

UPDATE daily_budget_snapshots SET captured_at = substr(substr(captured_at, 1, length(captured_at) - 1) || CASE WHEN length(captured_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE captured_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(captured_at) <> 24;

UPDATE mcp_tokens SET created_at = substr(substr(created_at, 1, length(created_at) - 1) || CASE WHEN length(created_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(created_at) <> 24;

UPDATE mcp_tokens SET last_used_at = substr(substr(last_used_at, 1, length(last_used_at) - 1) || CASE WHEN length(last_used_at) = 20 THEN '.000' ELSE '000' END, 1, 23) || 'Z'
WHERE last_used_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]*Z' AND length(last_used_at) <> 24;
