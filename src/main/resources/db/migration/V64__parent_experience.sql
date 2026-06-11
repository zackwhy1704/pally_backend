-- Parent experience: FCM, family goals, student assignments, star awards, consent columns.

ALTER TABLE users ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(512);
ALTER TABLE users ADD COLUMN IF NOT EXISTS family_goal_json TEXT;

ALTER TABLE assignment ADD COLUMN IF NOT EXISTS student_id VARCHAR(36) REFERENCES users(id);
-- Make class_id nullable since parent-assigned revisions have no class
ALTER TABLE assignment ALTER COLUMN class_id DROP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_assignment_student ON assignment(student_id);

CREATE TABLE IF NOT EXISTS star_award_log (
    id         VARCHAR(36)  PRIMARY KEY,
    parent_id  VARCHAR(36)  NOT NULL REFERENCES users(id),
    child_id   VARCHAR(36)  NOT NULL REFERENCES users(id),
    amount     INT          NOT NULL,
    note       VARCHAR(255),
    awarded_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_star_award_child ON star_award_log(child_id, awarded_at);
CREATE INDEX IF NOT EXISTS idx_star_award_parent_day ON star_award_log(parent_id, awarded_at);

ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS child_id VARCHAR(36);
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS parent_id VARCHAR(36);
