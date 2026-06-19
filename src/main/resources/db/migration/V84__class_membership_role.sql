-- V84: Add role column to class_membership so staff preview enrollments are
-- excluded from the per-class student cap and roster/analytics.
-- Values: STUDENT (default, all existing rows) | STAFF (teacher preview).

ALTER TABLE class_membership
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'STUDENT';

UPDATE class_membership SET role = 'STUDENT' WHERE role IS NULL OR role = '';

CREATE INDEX IF NOT EXISTS idx_class_membership_class_role_status
    ON class_membership (class_id, role, status);
