ALTER TABLE users
    ADD COLUMN IF NOT EXISTS firebase_uid VARCHAR(200);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_firebase_uid
    ON users(firebase_uid)
    WHERE firebase_uid IS NOT NULL;
