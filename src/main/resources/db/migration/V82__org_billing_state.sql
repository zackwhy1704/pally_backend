ALTER TABLE organizations
  ADD COLUMN tier               VARCHAR(20)   NOT NULL DEFAULT 'NONE',
  ADD COLUMN sub_status         VARCHAR(20)   NOT NULL DEFAULT 'NONE',
  ADD COLUMN pilot_ends_at      TIMESTAMPTZ   NULL,
  ADD COLUMN class_limit        INT           NOT NULL DEFAULT 0,
  ADD COLUMN class_student_cap  INT           NOT NULL DEFAULT 20,
  ADD COLUMN stripe_customer_ref VARCHAR(120) NULL,
  ADD COLUMN terms_accepted_at  TIMESTAMPTZ   NULL,
  ADD COLUMN terms_accepted_by  VARCHAR(36)   NULL;
