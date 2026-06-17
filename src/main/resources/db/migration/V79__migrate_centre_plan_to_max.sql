-- V79: Remove CENTRE consumer tier.
-- Any subscription row whose plan contains 'centre' is migrated to max_monthly.
-- These were legacy B2B-owner plans; the entitlement they provided (unlimited)
-- is preserved by mapping to MAX.
UPDATE subscriptions
SET plan       = 'max_monthly',
    updated_at = NOW()
WHERE LOWER(plan) LIKE '%centre%';
