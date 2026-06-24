-- Groundedness gate (B3) output. When a generated DRAFT item carries a flagged
-- claim (a hard fact not entailed by the source notes, or a contradiction), the
-- gate stores a payload here so the teacher sees the claim and the source line
-- side by side in the Review tab. Null = clean (no flag).
ALTER TABLE module_content_item ADD COLUMN verification_json TEXT;
