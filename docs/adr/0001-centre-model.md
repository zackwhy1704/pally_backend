# ADR 0001: Centre Model — organizations (not V56 centre_*)

Status: Accepted (2026-06-08)

The canonical centre model is the `organizations` table + `users.centre_id` (V35).
V56's richer `centre_*` schema was scaffolded ahead of code and dropped in V58.
Re-introduce only with a full migration plan once a paying pilot commits.
