-- Grant lifetime Premium to the admin account (zhengyi1704@gmail.com).
-- Uses an upsert so it's safe to run even if a subscription row already exists.
-- plan = 'admin' distinguishes it from real Stripe subscriptions.
INSERT INTO subscriptions (
    user_id,
    stripe_customer_id,
    stripe_subscription_id,
    plan,
    status,
    current_period_end,
    updated_at
)
SELECT
    u.id,
    'admin_bypass',
    'admin_bypass',
    'admin',
    'active',
    '2099-12-31 00:00:00+00',
    NOW()
FROM users u
WHERE u.email = 'zhengyi1704@gmail.com'
ON CONFLICT (user_id) DO UPDATE
    SET status             = 'active',
        plan               = 'admin',
        current_period_end = '2099-12-31 00:00:00+00',
        updated_at         = NOW();

-- Also mark the trial as CONVERTED so the banner doesn't nag.
UPDATE users
SET trial_status = 'CONVERTED'
WHERE email = 'zhengyi1704@gmail.com'
  AND trial_status = 'ACTIVE';
