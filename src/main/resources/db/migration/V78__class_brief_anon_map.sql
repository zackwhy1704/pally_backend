-- Persist the Student-#N → userId mapping that was in effect at generation time.
-- Without this, any roster change shifts the alphabetical positions and the
-- re-identification at read time resolves "Student #3" to the wrong person.
ALTER TABLE class_brief ADD COLUMN IF NOT EXISTS anon_map_json TEXT;
