-- Migrate any avatars still in DIRECT mode to SOCRATIC
UPDATE avatars SET pedagogy_mode = 'SOCRATIC' WHERE pedagogy_mode = 'DIRECT';
