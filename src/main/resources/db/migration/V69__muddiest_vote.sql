-- Part A3: anonymous "muddiest point" votes. One vote per student per module
-- (UPSERT changes the concept). user_id is stored for dedup ONLY and is never
-- exposed in any aggregate response.

CREATE TABLE muddiest_vote (
    id          VARCHAR(36)  PRIMARY KEY,
    module_id   VARCHAR(36)  NOT NULL,
    class_id    VARCHAR(36),
    concept_id  VARCHAR(255) NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_muddiest_module_user UNIQUE (module_id, user_id)
);

CREATE INDEX idx_muddiest_module ON muddiest_vote (module_id);
CREATE INDEX idx_muddiest_class ON muddiest_vote (class_id);
