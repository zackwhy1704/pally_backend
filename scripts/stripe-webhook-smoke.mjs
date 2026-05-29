#!/usr/bin/env node
/**
 * Stripe webhook end-to-end smoke test.
 *
 * Fires a signed `checkout.session.completed` at the live webhook
 * endpoint TWICE and asserts:
 *   1. The first POST returns 200 with duplicate omitted/false →
 *      the handler ran and recorded the event.
 *   2. The second POST (identical event.id) returns 200 with
 *      duplicate=true → the V38 idempotency table did its job.
 *
 * Run:
 *   STRIPE_WEBHOOK_SECRET=whsec_... \
 *     node scripts/stripe-webhook-smoke.mjs
 *
 * Optional env:
 *   WEBHOOK_URL  — defaults to the Railway prod URL.
 *   USER_ID      — the client_reference_id baked into the event; defaults
 *                  to a random uuid so the smoke doesn't activate a real
 *                  subscription against a real user. Pass an actual user
 *                  id to test the full happy path on a known account.
 *   PLAN         — defaults to "family_monthly".
 *   EVENT_ID     — override the deterministic event.id (handy if you've
 *                  already smoke-tested and want a fresh non-duplicate).
 *
 * No npm install needed — uses only Node's built-in crypto + fetch.
 */

import { createHmac, randomUUID } from 'node:crypto';

const SECRET = process.env.STRIPE_WEBHOOK_SECRET;
if (!SECRET) {
  console.error('STRIPE_WEBHOOK_SECRET is required.');
  process.exit(2);
}

const WEBHOOK_URL = process.env.WEBHOOK_URL
  ?? 'https://pallybackend-production.up.railway.app/api/v1/subscription/webhook';
const USER_ID = process.env.USER_ID ?? `smoke-${randomUUID()}`;
const PLAN = process.env.PLAN ?? 'family_monthly';
const EVENT_ID = process.env.EVENT_ID ?? `evt_smoke_${Date.now()}`;
const TIMESTAMP = Math.floor(Date.now() / 1000);

// Minimal but realistic checkout.session.completed shape — only the
// fields the backend handler reads. Real Stripe events have ~80 fields.
const event = {
  id: EVENT_ID,
  object: 'event',
  api_version: '2024-04-10',
  created: TIMESTAMP,
  type: 'checkout.session.completed',
  livemode: true,
  data: {
    object: {
      id: `cs_smoke_${Date.now()}`,
      object: 'checkout.session',
      client_reference_id: USER_ID,
      customer: `cus_smoke_${Date.now()}`,
      subscription: `sub_smoke_${Date.now()}`,
      payment_status: 'paid',
      status: 'complete',
      mode: 'subscription',
      metadata: { plan: PLAN, userId: USER_ID },
    },
  },
};

const body = JSON.stringify(event);
const signedPayload = `${TIMESTAMP}.${body}`;
const signature = createHmac('sha256', SECRET).update(signedPayload).digest('hex');
const sigHeader = `t=${TIMESTAMP},v1=${signature}`;

async function post(label) {
  const res = await fetch(WEBHOOK_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Stripe-Signature': sigHeader,
    },
    body,
  });
  let parsed;
  try { parsed = await res.json(); } catch { parsed = await res.text(); }
  console.log(`[${label}] HTTP ${res.status}`);
  console.log(JSON.stringify(parsed, null, 2));
  return { status: res.status, body: parsed };
}

console.log(`→ POST ${WEBHOOK_URL}`);
console.log(`   event.id   = ${EVENT_ID}`);
console.log(`   user_id    = ${USER_ID}`);
console.log(`   plan       = ${PLAN}`);
console.log('');

const first = await post('1st delivery');
console.log('');
const second = await post('2nd delivery (should be idempotent)');
console.log('');

// Verdict
const innerData = (r) => (r.body && typeof r.body === 'object' ? r.body.data : null);
const firstDup = innerData(first)?.duplicate === true;
const secondDup = innerData(second)?.duplicate === true;

const pass =
  first.status === 200 &&
  second.status === 200 &&
  !firstDup &&
  secondDup;

if (pass) {
  console.log('✅ PASS — first delivery applied, second was deduped.');
  process.exit(0);
} else {
  console.error('❌ FAIL — see responses above.');
  console.error(`   first.status=${first.status} duplicate=${firstDup}`);
  console.error(`   second.status=${second.status} duplicate=${secondDup}`);
  process.exit(1);
}
