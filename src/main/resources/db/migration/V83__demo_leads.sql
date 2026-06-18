CREATE TABLE demo_leads (
  id            VARCHAR(36)   NOT NULL PRIMARY KEY,
  org_name      VARCHAR(200)  NOT NULL,
  contact_name  VARCHAR(200)  NOT NULL,
  email         VARCHAR(200)  NOT NULL,
  phone         VARCHAR(50)   NOT NULL,
  segment       VARCHAR(20)   NOT NULL DEFAULT 'CENTRE',
  est_classes   INT           NULL,
  est_students  INT           NULL,
  org_id        VARCHAR(36)   NULL,
  status        VARCHAR(30)   NOT NULL DEFAULT 'NEW',
  notes         TEXT          NULL,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_demo_leads_status ON demo_leads(status);
CREATE INDEX idx_demo_leads_email  ON demo_leads(email);
