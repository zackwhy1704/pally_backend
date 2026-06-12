-- Trusted-adult content review: tokenized review requests + wiki verification provenance.

CREATE TABLE content_review_request (
  id            VARCHAR(36) PRIMARY KEY,
  token         VARCHAR(64) NOT NULL UNIQUE,
  wiki_page_id  VARCHAR(36) NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
  requester_id  VARCHAR(36) NOT NULL,
  status        VARCHAR(12) NOT NULL DEFAULT 'PENDING',
  reviewer_name VARCHAR(80),
  flag_note     VARCHAR(500),
  created_at    TIMESTAMP NOT NULL,
  reviewed_at   TIMESTAMP,
  expires_at    TIMESTAMP NOT NULL
);

CREATE INDEX idx_review_token ON content_review_request(token);
CREATE INDEX idx_review_page  ON content_review_request(wiki_page_id);

-- Wiki verification provenance columns.
ALTER TABLE wiki_pages ADD COLUMN verification_source VARCHAR(20);
ALTER TABLE wiki_pages ADD COLUMN verified_by VARCHAR(80);

-- Backfill: existing pages verified via an in-app human correction are
-- CORRECTION-sourced. New ADULT_REVIEW pages set their own source going forward.
UPDATE wiki_pages
   SET verification_source = 'CORRECTION'
 WHERE is_human_verified = TRUE
   AND human_correction IS NOT NULL;
