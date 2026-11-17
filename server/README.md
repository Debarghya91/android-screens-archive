# Server functions for the Android app

## verify-play-purchase

Verifies a Google Play purchase token with the Play Developer API
and records it in `user_purchases` with store `play`. The Android
app calls it from `ServerSync` after every Play purchase. The
function never trusts the client: no verified token, no row.

Status: source complete, statically checked only, NOT deployed,
NOT tested against the Play Developer API. The Play
service-account credential does not exist yet. Until the
credential exists, the secrets are set, the migration below is
applied and the function is deployed, every call fails closed
(503) and the app keeps the record queued. On-device Play
entitlements are unaffected.

Products and validity (decided lineup, 15 Nov 2026): the
allowlist holds 32 product IDs, the 16 one-time purchase
products (`com.paymentstraininghub.app.part-1` ... `.part-10`,
`.studio`, `.simulator`, `.investigation-lab`, `.practice`,
`.interview`, `.integrated`) and their 16 renewal subscriptions
(same base plus `.renewal`). Store products carry one price
each: the sale price. Every product gives 1 year of access:
one-time purchases get `expires_at` = the Google-verified
purchase time + 365 days (via `products.get`), and renewal
subscriptions get `expires_at` = the Google-verified
subscription expiry (via `subscriptionsv3`, which requires an
expiry in the future and a payment state of received or free
trial). A renewal re-record updates `expires_at` on the same
row, whose `transaction_id` is the stable token-derived id
(`subs:<product>:<sha256-prefix>`); a one-time re-record never
moves `expires_at`.

## 1. Database migration (required before first write)

The `user_purchases` store check allows only
`apple`, `upi`, `payu`, `paypal` (13 Nov 2026 migration). Add
`play` or every insert fails with code `store_not_configured`.

Run in the Supabase dashboard SQL editor. First read the current
check constraint name (the name depends on how the 13 Nov
migration declared it):

```sql
select conname, pg_get_constraintdef(oid)
from pg_constraint
where conrelid = 'public.user_purchases'::regclass
  and contype = 'c'
  and pg_get_constraintdef(oid) like '%apple%';
```

Then replace the check, using the name that query returned
(`user_purchases_store_check` below is the expected name; use the
real one if it differs):

```sql
alter table public.user_purchases
  drop constraint user_purchases_store_check;

alter table public.user_purchases
  add constraint user_purchases_store_check
  check (store in ('apple', 'upi', 'payu', 'paypal', 'play'));
```

Verify: insert a throwaway row is NOT needed; re-run the first
query and confirm the definition lists `play`. No other schema
change is needed: `transaction_id` is already unique,
`purchased_at` is server-stamped by the function, `expires_at`
already exists (nullable, 0001_init.sql) and is written by the
function, and the `user_id` foreign key is already
`ON DELETE SET NULL` (anonymized retention, Option A).

## 2. Secrets (required before deploy)

Set in the Supabase dashboard under Edge Functions, Secrets, or
with the CLI. Never commit values.

| Secret | Value |
| --- | --- |
| `PLAY_SERVICE_ACCOUNT_EMAIL` | `client_email` from the service-account JSON key |
| `PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` | `private_key` from the JSON key, the full PEM block including `BEGIN/END PRIVATE KEY` lines |
| `PLAY_PACKAGE_NAME` | Optional. Defaults to `com.paymentstraininghub.app` |

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are already set
for the project.

How to obtain the service account (Play Console, owner step):

1. Play Console, Setup, API access. Link or create the Google
   Cloud project for this app.
2. Under Service accounts, create a service account and grant it
   access in Play Console with the "View financial data"
   permission (add "Manage orders and subscriptions" only if
   order management is ever needed).
3. In Google Cloud Console, open the service account, Keys,
   create a JSON key, download it once. Copy `client_email` and
   `private_key` into the secrets above. Store the JSON file
   somewhere safe outside the repo; it cannot be downloaded
   again.

## 3. Deploy

Keep JWT verification ON for this function: it requires a
signed-in user's token. (Unlike `payu-callback`, which has JWT
verification off because PayU calls it.)

Route A, Supabase CLI (from the project root, after the secrets
are set):

```sh
supabase link --project-ref <project-ref>   # once, if needed
supabase secrets set PLAY_SERVICE_ACCOUNT_EMAIL=<email> \
  PLAY_SERVICE_ACCOUNT_PRIVATE_KEY="$(cat <key-file>.json | python3 -c 'import json,sys; print(json.load(sys.stdin)["private_key"])')"
supabase functions deploy verify-play-purchase
supabase secrets list   # names only; values are not shown
```

Route B, dashboard Editor (proven for the `payu-*` functions on
13 Nov 2026): bundle the single file with esbuild, then paste
the bundled file into a new function named
`verify-play-purchase` in the dashboard Editor and deploy. This
file has no local imports, so it bundles cleanly:

```sh
npx esbuild server/verify-play-purchase/index.ts \
  --bundle --platform=browser --format=esm --outfile=/tmp/verify-play-purchase.min.ts
```

## 4. Test steps (run all before calling this done)

A real purchase token comes from a real Play purchase: an
internal-testing build bought with a license tester account.
Tokens from Play Billing test cards that never reach Google's
servers do not verify; that is expected, not a bug.

1. Apply the migration, set the secrets, deploy, make a test
   purchase, and note its purchase token, product ID and order
   ID (GPA.xxxx).
2. POST a record call with a signed-in user's JWT, the real
   token, product ID, package name and order ID. Expect 200
   `{ ok: true }`. Check the row: `store` is `play`,
   `transaction_id` is the GPA order ID, `purchased_at` is close
   to the server time, and `expires_at` is the Google purchase
   time + 365 days.
3. Repeat step 2. Expect 200 and NO change to `purchased_at`
   or `expires_at`.
4. POST with an invented purchase token. Expect 402 with code
   `purchase_not_found`, and no new row.
5. POST with the real token but a different valid product ID.
   Expect 402 with code `purchase_not_found` (the token does
   not exist under that product), and no new row.
6. POST with `product_id` `com.example.fake`. Expect 400 with
   code `unknown_product_id`, and no new row.
7. POST with a wrong `package_name`. Expect 400 with code
   `purchase_package_mismatch`, and no new row.
8. POST with the real token but a different order ID. Expect
   402 with code `order_id_mismatch`, and no new row.
9. Refund the test purchase in Play Console (or void it), then
   POST the record again. Expect 402 with code
   `purchase_revoked`.
10. Unset the two Play secrets (staging only) and POST a
    record. Expect 503 with code `verification_unavailable`,
    and no new row. Then restore the secrets.
11. POST a record for another user's `user_id` with a non-admin
    JWT. Expect 403, and no new row.
12. Buy a renewal subscription (a `.renewal` product) with a
    license tester account, then POST a record with the
    subscription purchase token. Expect 200 `{ ok: true }`.
    Check the row: `transaction_id` starts with `subs:`,
    `expires_at` equals the Google subscription expiry, and
    `product_id` is the renewal product ID.
13. Re-record the same subscription token after a renewal.
    Expect 200 and `expires_at` moved to the new expiry, on
    the same row (same `transaction_id`). Then cancel the
    subscription, wait for expiry, and POST the record again.
    Expect 402 with code `subscription_expired`, and no
    change to the row.

## 5. Follow-ups (not in this build)

- Subscription product pinning: `subscriptionsv3` returns no
  product id, so the recorded renewal product is the
  allowlisted id the caller sent. The `subscriptionsv2`
  endpoint returns line-item product ids; switching the
  renewal path to it would pin the product from Google's
  response.
- Server-side acknowledgement: the app acknowledges purchases
  on device today. A server-side acknowledge call after
  verification is a hardening follow-up.
- Refund/revocation sync: refunds reach the server today only
  through on-device Play reconciliation (revoked products lose
  their entitlement on the next refresh). Real-time Developer
  Notifications (RTDN) or voided-purchases polling would close
  the gap for cross-device revocation.
- No other server function is needed by the Android app beyond
  the two in this folder. The app also calls `chat-assistant`
  (AI) and `delete-account` (both already deployed); it does
  not call `ai-tutor`, `issue-certifier-credential`,
  `verify-upi-claim` or `record-purchase`.

## verify-integrity

Decodes a Play Integrity token with the Play Integrity API and
answers whether the device, the app install and the licence
check out. The Android app calls it from `IntegrityCheck` as
part of the premium access gate; only this function's answer
counts, and no verdict is ever computed on the device.

Status: source complete, statically checked only, NOT deployed,
NOT tested against the Play Integrity API. It uses the SAME
Play service-account credential as `verify-play-purchase`
(Section 2 secrets); that credential does not exist yet. Until
it exists, the secrets are set and the function is deployed,
every call fails closed (503) and the app runs the gate in
degraded mode: installer, signature and root checks still
enforce, and full enforcement starts automatically once this
function answers.

## 1. Secrets (shared with verify-play-purchase)

The same `PLAY_SERVICE_ACCOUNT_EMAIL`,
`PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` and optional
`PLAY_PACKAGE_NAME` secrets. No new secret is needed. The
service account ALSO needs the Play Integrity API enabled in
the linked Google Cloud project (APIs and Services, Library,
search "Play Integrity API", Enable); no extra Play Console
permission applies to integrity decoding beyond the API-access
link itself. `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`
are already set; they are used only when a bearer token is
present to validate.

## 2. Deploy

Keep JWT verification OFF for this function: guests must be
able to verify integrity (signed-out buyers gate premium too).
The gateway still requires the `apikey` header, and a bearer
token, when present, is validated in code.

Route A, Supabase CLI (after the secrets are set):

```sh
supabase functions deploy verify-integrity --no-verify-jwt
```

Route B, dashboard Editor (proven for the `payu-*` functions on
13 Nov 2026): bundle the single file with esbuild, then paste
the bundled file into a new function named `verify-integrity`
in the dashboard Editor, deploy, and turn JWT verification OFF
for it in the function settings. This file has no local
imports, so it bundles cleanly:

```sh
npx esbuild server/verify-integrity/index.ts \
  --bundle --platform=browser --format=esm --outfile=/tmp/verify-integrity.min.ts
```

## 3. Test steps (run all before calling this done)

A real integrity token comes from a real Play install: an
internal-testing build on a real device, requested through the
app's own gate. Tokens cannot be minted by hand.

1. Set the secrets, deploy with JWT verification off, and open
   the app on a genuine Play-installed device. The gate should
   allow premium (with integrity verified). Check the function
   logs: one decode, `passed=true`.
2. POST a call with a freshly minted token and its nonce from
   the app (log the request from a debug build only; never log
   tokens in release). Expect 200 `{ ok: true }`.
3. POST the same token with a different nonce. Expect 400 with
   code `integrity_token_mismatch`.
4. POST with a wrong `package_name`. Expect 400 with code
   `integrity_package_mismatch`.
5. POST with an invented token string. Expect 502 with code
   `verification_failed`.
6. On a rooted device or an emulator, open the app. Expect the
   gate to block premium and the logs to show `passed=false`
   with `device_integrity` in the failed checks.
7. Sideload the release APK with adb (not from Play). Expect
   the gate to block premium on the installer check before the
   integrity call matters.
8. Unset the two Play secrets (staging only) and POST a call.
   Expect 503 with code `verification_unavailable`. Then
   restore the secrets.
9. POST with an invalid bearer token. Expect 401. POST with no
   bearer token and a real token + nonce. Expect 200 (guest
   path works).
