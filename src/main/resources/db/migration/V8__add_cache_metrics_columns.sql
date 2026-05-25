ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS cache_hit           BOOLEAN,
    ADD COLUMN IF NOT EXISTS cache_read_tokens   INT,
    ADD COLUMN IF NOT EXISTS cache_write_tokens  INT,
    ADD COLUMN IF NOT EXISTS total_input_tokens  INT,
    ADD COLUMN IF NOT EXISTS total_output_tokens INT;
