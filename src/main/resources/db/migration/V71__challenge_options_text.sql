-- The app stores class_challenge.options as a JSON-array STRING (Jackson
-- serialize/deserialize) and never uses jsonb operators on it. Mapping a Java
-- String to a jsonb column made Hibernate bind it as VARCHAR, which Postgres
-- rejects ("column is of type jsonb but expression is of type character
-- varying"), 500ing every challenge POST. Switch the column to TEXT to match
-- how the application actually uses it.
ALTER TABLE class_challenge ALTER COLUMN options TYPE TEXT USING options::text;
