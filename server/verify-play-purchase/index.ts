// supabase/functions/verify-play-purchase/index.ts
// Payments Training Hub Edge Function: verify a Google Play
// purchase token with the Play Developer API and record it in
// user_purchases with store "play". This is the Android half of
// the record-purchase hardening: the Android app sends its Play
// purchase token here (see ServerSync), and the function writes
// ONLY after Google confirms the purchase. The hardened
// record-purchase function is untouched: it verifies Apple JWS
// transactions, and this function verifies Play tokens. The two
// never share a code path.
//
// Called ONLY by the signed-in user the purchase belongs to (or
// by an admin, for any user). A caller can never record another
// user's purchase: user_id in the body must match the JWT, unless
// the caller's app_metadata has is_admin true. Writes use the JWT
// caller id for a non-admin caller (effectiveUserId); the body
// user_id only names the admin target.
//
// SECURITY MODEL (mirrors record-purchase):
//   1. Product allowlist. Only the 32 product IDs in
//      ALLOWED_PRODUCT_IDS are accepted: the 16 one-time purchase
//      products and their 16 renewal subscriptions (base id +
//      ".renewal"), decided 15 Nov 2026. Any other product_id is
//      rejected with 400 and code unknown_product_id, before any
//      Google call and before any write. The old 12-product
//      division is shelved and its IDs are rejected here.
//   2. Play verification for every record. A record is written
//      ONLY after verifyPurchaseWithPlay confirms the token with
//      the Play Developer API. One-time purchases use
//      products.get: the purchase must exist, its purchaseState
//      must be 0 (purchased), its orderId must be present and
//      must match the order_id the app sent, and the package
//      name in the body must match the configured package (the
//      API URL itself is built from the configured package,
//      never from the body). A forged token, a cancelled or
//      pending purchase, or a mismatched order id is rejected and
//      nothing is stored. The Play products.get response carries
//      no product id: the product is pinned by the API URL.
//      Renewal subscriptions use subscriptionsv3 (see point 2b).
//   2b. Renewal subscriptions. A renewal product id (ending in
//      ".renewal") is verified with GET
//      /purchases/subscriptionsv3/tokens/{token} (no product id
//      in the URL). The subscription must report an
//      expiryTimeMillis in the future, and a paymentState of 1
//      (payment received) or 2 (free trial); a pending payment
//      (0 or 3) or an expired subscription is rejected and
//      nothing is stored. The subscriptionsv3 response carries
//      no product id, so the product cannot be pinned by the
//      API the way products.get pins it: the recorded product
//      is the allowlisted renewal id the signed-in caller sent,
//      and the token proves an active subscription for this app.
//      Pinning the product from Google's response (the
//      subscriptionsv2 endpoint returns line-item product ids)
//      is a follow-up hardening step, not done here.
//   3. Fail closed. When the Play service-account credentials are
//      not configured, verification returns "unavailable" and the
//      record action answers 503 with code
//      verification_unavailable. Nothing is stored. There is no
//      fallback that trusts the client.
//   4. Server-side purchased_at. The client-supplied purchase
//      time is never sent and never used. New rows get the server
//      clock time, and re-recording an existing transaction never
//      moves purchased_at.
//   4b. Expiry. Every product gives 1 year of access (decided
//      15 Nov 2026). A one-time purchase gets expires_at = the
//      Google-verified purchaseTimeMillis + 365 days; the
//      purchase time must be present in the verified response,
//      or the record is rejected with 402 and code
//      purchase_expiry_missing. A renewal subscription gets
//      expires_at = the Google-verified expiryTimeMillis, which
//      must be in the future. Re-recording a renewal
//      transaction UPDATES expires_at to the latest verified
//      expiry (a renewal extends access); re-recording a
//      one-time purchase never moves expires_at, just as it
//      never moves purchased_at.
//   5. No re-activation. Re-recording a transaction whose row is
//      revoked or refunded is rejected with 409 and code
//      purchase_revoked. A transaction recorded for one user can
//      never be re-recorded for another user (409,
//      transaction_owner_mismatch).
//   6. Transaction identity. For a one-time purchase,
//      transaction_id is the Play order id (GPA.xxxx) returned
//      by Google, not the client-supplied value: the two must
//      match, and the server value is the one stored. For a
//      renewal subscription, subscriptionsv3 returns no stable
//      order id (latestOrderId changes every renewal), so
//      transaction_id is "subs:" plus the product id plus the
//      first 32 hex characters of the SHA-256 hash of the
//      purchase token: stable across renewals of the same
//      subscription, distinct per product, and never the raw
//      token. The transaction_id unique constraint stops the
//      same purchase being recorded twice, and a renewal
//      re-record updates expires_at on the same row.
//   7. Store value. Rows are written with store "play". That
//      value is NOT in the user_purchases store check yet: the
//      13 Nov 2026 migration allows only apple/upi/payu/paypal.
//      A further migration must add "play" before this function
//      can write; until then inserts fail with code
//      store_not_configured (503) and the app keeps the record
//      queued. See server/README.md for the migration SQL.
//
// UNTESTED WARNING. The Play verification code below has NEVER
// been run against the Play Developer API, and no Play
// service-account credential exists yet (Play Console API access
// is not set up and no service account has been created). There
// is no Play sandbox access and no Supabase access from the
// machine that wrote this code. The code was statically checked
// only: re-read line by line, brace and parenthesis balance
// checked, and the API shapes checked against Google's published
// docs. Do NOT describe this function as tested or verified until
// the test steps in server/README.md have actually been run.
// Until the Play secrets are set, every record answers 503; the
// app keeps the on-device Play entitlement and marks the server
// sync pending, so buyers are not locked out on the device, but
// cross-device entitlement sync stays blocked.
//
// Secrets (set in the Supabase dashboard or with the CLI, never
// in this file):
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY (as before)
//   PLAY_SERVICE_ACCOUNT_EMAIL: the client_email of the Play
//     service account (from its JSON key file).
//   PLAY_SERVICE_ACCOUNT_PRIVATE_KEY: the private_key of the
//     service account JSON key, the full PEM block including the
//     BEGIN/END PRIVATE KEY lines. The key is shown once.
//   PLAY_PACKAGE_NAME (optional): the app package name. Defaults
//     to com.paymentstraininghub.app. Set it only if the package ever
//     changes.
// The service-role key bypasses Row Level Security and the Play
// private key proves identity to Google, so both must only ever
// live in the server environment, never in the app and never in
// this file. No secret value appears in this file, in any log
// line, or in any response.
//
// HOW TO OBTAIN THE PLAY SERVICE ACCOUNT (once Play Console API
// access exists):
//   1. In Play Console, go to Setup, then API access. Link or
//      create the Google Cloud project for this app.
//   2. Under Service accounts, create a new service account (or
//      use an existing one) and grant it access in Play Console
//      with the "View financial data" permission (and "Manage
//      orders and subscriptions" if order management is ever
//      needed). The purchase-verification call needs the
//      financial-data permission.
//   3. In Google Cloud Console, open the service account, go to
//      Keys, create a JSON key, and download it once. Copy
//      client_email and private_key into the Supabase secrets
//      above. Store the JSON file somewhere safe outside the
//      repo. It cannot be downloaded again; if it is lost,
//      delete the key and create a new one.
//   4. Apply the store migration (server/README.md), set the
//      secrets, then deploy.
//
// DEPLOY: see server/README.md. Two routes are documented: the
// Supabase CLI, and the dashboard Editor with an esbuild-bundled
// single file (the route proven for the payu-* functions on
// 13 Nov 2026). This file has no local imports, so it bundles
// cleanly. Keep JWT verification ON for this function: it
// requires a signed-in user's token.
//
// Request: POST with a Bearer <user JWT> in the Authorization
// header. Body for recording a purchase (Play verification
// applies):
//   { "action": "record", "product_id":
//     "com.paymentstraininghub.app.practice",
//     "purchase_token": "<Play purchase token>",
//     "package_name": "com.paymentstraininghub.app",
//     "order_id": "GPA.xxxx-xxxx-xxxx-xxxxx",
//     "user_id": "<auth user id>" }
// The app sends order_id from the Play Purchase object; when it
// is blank (Play does not always set it on older purchases) the
// order id returned by Google is used as the transaction id and
// no mismatch check runs. For a renewal subscription the app
// sends the subscription purchase token; order_id is ignored,
// because subscriptionsv3 has no order-id match to check, and
// the transaction id is derived from the token (see point 6).
// No other action exists: refunds and
// revocations reach the server through a future
// voided-purchases poller, not through this function, and the
// app re-checks Play entitlements on device separately.
//
// Error codes (all errors keep the existing { error } shape and
// add a code field; success keeps { ok: true, action,
// transaction_id }):
//   unknown_product_id (400), invalid_purchase_token (400),
//   purchase_package_mismatch (400),
//   verification_unavailable (503), purchase_not_found (402),
//   purchase_revoked (402), purchase_pending (402),
//   purchase_expiry_missing (402), subscription_expired (402),
//   order_id_mismatch (402), verification_failed (502),
//   store_not_configured (503), transaction_owner_mismatch
//   (409), purchase_revoked (409), transaction_conflict (409).

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
// Play Developer API credentials, from Supabase secrets only.
// When either is missing, Play verification is unavailable and
// records fail closed (503). The values are never logged and
// never leave this function.
const PLAY_SERVICE_ACCOUNT_EMAIL = Deno.env.get("PLAY_SERVICE_ACCOUNT_EMAIL") ?? "";
const PLAY_SERVICE_ACCOUNT_PRIVATE_KEY = Deno.env.get("PLAY_SERVICE_ACCOUNT_PRIVATE_KEY") ?? "";
const PLAY_PACKAGE_NAME = Deno.env.get("PLAY_PACKAGE_NAME") ?? "com.paymentstraininghub.app";

// The 32 buyable products (decided 15 Nov 2026): the 16 one-time
// purchase products and their 16 renewal subscriptions, exactly
// as in ProductCatalogue and Play Console. Keep this list in step
// with both. Any product_id outside this list is rejected before
// any write. A renewal id is its purchase id plus ".renewal"; no
// purchase id ends in ".renewal", so the suffix test in the
// verification and record branches is exact.
const ALLOWED_PRODUCT_IDS = new Set([
  "com.paymentstraininghub.app.part-1",
  "com.paymentstraininghub.app.part-2",
  "com.paymentstraininghub.app.part-3",
  "com.paymentstraininghub.app.part-4",
  "com.paymentstraininghub.app.part-5",
  "com.paymentstraininghub.app.part-6",
  "com.paymentstraininghub.app.part-7",
  "com.paymentstraininghub.app.part-8",
  "com.paymentstraininghub.app.part-9",
  "com.paymentstraininghub.app.part-10",
  "com.paymentstraininghub.app.studio",
  "com.paymentstraininghub.app.simulator",
  "com.paymentstraininghub.app.investigation-lab",
  "com.paymentstraininghub.app.practice",
  "com.paymentstraininghub.app.interview",
  "com.paymentstraininghub.app.integrated",
  "com.paymentstraininghub.app.part-1.renewal",
  "com.paymentstraininghub.app.part-2.renewal",
  "com.paymentstraininghub.app.part-3.renewal",
  "com.paymentstraininghub.app.part-4.renewal",
  "com.paymentstraininghub.app.part-5.renewal",
  "com.paymentstraininghub.app.part-6.renewal",
  "com.paymentstraininghub.app.part-7.renewal",
  "com.paymentstraininghub.app.part-8.renewal",
  "com.paymentstraininghub.app.part-9.renewal",
  "com.paymentstraininghub.app.part-10.renewal",
  "com.paymentstraininghub.app.studio.renewal",
  "com.paymentstraininghub.app.simulator.renewal",
  "com.paymentstraininghub.app.investigation-lab.renewal",
  "com.paymentstraininghub.app.practice.renewal",
  "com.paymentstraininghub.app.interview.renewal",
  "com.paymentstraininghub.app.integrated.renewal",
]);

// One year of access for every product, in milliseconds. Used
// for one-time purchases (verified purchase time + 365 days).
// Renewal subscriptions use the Google-verified expiry.
const VALIDITY_MS = 365 * 24 * 60 * 60 * 1000;

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const PLAY_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const PLAY_API_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3";
const GOOGLE_TIMEOUT_MS = 10000;
const SERVICE_ACCOUNT_JWT_LIFETIME_SECONDS = 3600;
// Play purchaseState values for products.get: 0 purchased,
// 1 canceled, 2 pending.
const PURCHASE_STATE_PURCHASED = 0;
const PURCHASE_STATE_CANCELED = 1;
const PURCHASE_STATE_PENDING = 2;
// Play paymentState values for subscriptionsv3: 0 payment
// pending, 1 payment received, 2 free trial, 3 pending purchase
// for a deferred upgrade or downgrade. Only 1 and 2 mean the
// subscription is active.
const SUBSCRIPTION_PAYMENT_RECEIVED = 1;
const SUBSCRIPTION_PAYMENT_FREE_TRIAL = 2;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

// The outcome of a Play verification attempt. "verified" is the
// only outcome that allows a write, and it carries the
// transaction id to store (the Google order id for a one-time
// purchase, the token-derived stable id for a renewal
// subscription) plus expiresAt, the ISO expiry for the row.
// "unavailable" means the credentials are not configured.
// "rejected" means Google was asked and the purchase did not
// check out, or Google could not be reached; httpStatus and code
// are the response to send.
type PlayVerificationResult =
  | { outcome: "verified"; transactionId: string; expiresAt: string }
  | { outcome: "unavailable" }
  | { outcome: "rejected"; code: string; httpStatus: number; message: string };

function rejected(code: string, httpStatus: number, message: string): PlayVerificationResult {
  return { outcome: "rejected", code, httpStatus, message };
}

// Base64url-encode bytes, without padding. Used for the JWT
// header, payload and signature.
function base64UrlEncode(data: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < data.length; i++) {
    binary += String.fromCharCode(data[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeText(text: string): string {
  return base64UrlEncode(new TextEncoder().encode(text));
}

// Decode standard base64 to bytes. Returns null on bad input
// instead of throwing, so a malformed key fails closed.
function base64Decode(base64: string): Uint8Array | null {
  try {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch {
    return null;
  }
}

// Extract the DER bytes from a PKCS8 private key in PEM form. The
// secret may hold real newlines or literal backslash-n pairs
// (both happen when a multiline value is set from a shell, and
// the JSON key file itself uses literal pairs), so literal pairs
// are normalized first. Returns null when the value lacks the PEM
// markers or is not decodable base64, so a mistyped secret fails
// closed instead of reaching Google.
function privateKeyBytes(pem: string): Uint8Array | null {
  const normalized = pem.replace(/\\n/g, "\n");
  if (
    !normalized.includes("-----BEGIN PRIVATE KEY-----") ||
    !normalized.includes("-----END PRIVATE KEY-----")
  ) {
    return null;
  }
  const body = normalized
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  if (!body) {
    return null;
  }
  return base64Decode(body);
}

// Build the signed RS256 JWT that authorizes the OAuth2 token
// exchange. Header: alg RS256, typ JWT. Payload: iss the service
// account email, scope the Android Publisher scope, aud the
// Google token endpoint, iat now, exp one hour out (Google's
// maximum). Signed with Web Crypto only, no dependency. Throws
// when the key cannot be imported or the signing fails; the
// caller turns that into a fail-closed 502. UNTESTED against
// Google.
async function createServiceAccountJwt(): Promise<string> {
  const keyBytes = privateKeyBytes(PLAY_SERVICE_ACCOUNT_PRIVATE_KEY);
  if (!keyBytes) {
    throw new Error("Play service account private key is not a usable PEM block");
  }
  // Copy into a fresh typed array so the key data is backed by a
  // plain ArrayBuffer, which crypto.subtle.importKey requires.
  const keyData = new Uint8Array(keyBytes);
  const key = await crypto.subtle.importKey(
    "pkcs8",
    keyData,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const nowSeconds = Math.floor(Date.now() / 1000);
  const header = base64UrlEncodeText(JSON.stringify({
    alg: "RS256",
    typ: "JWT",
  }));
  const payload = base64UrlEncodeText(JSON.stringify({
    iss: PLAY_SERVICE_ACCOUNT_EMAIL,
    scope: PLAY_SCOPE,
    aud: GOOGLE_TOKEN_URL,
    iat: nowSeconds,
    exp: nowSeconds + SERVICE_ACCOUNT_JWT_LIFETIME_SECONDS,
  }));
  const signingInput = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}

// Exchange the signed JWT for a Google access token. A fresh
// token is fetched for every record call: the function is
// stateless, the volume is one call per purchase, and caching
// would add expiry bookkeeping for no needed gain. Returns null
// when Google refuses the exchange or cannot be reached; the
// caller turns that into a fail-closed 502.
async function fetchGoogleAccessToken(jwt: string): Promise<string | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), GOOGLE_TIMEOUT_MS);
  try {
    const response = await fetch(GOOGLE_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: jwt,
      }).toString(),
      signal: controller.signal,
    });
    if (!response.ok) {
      return null;
    }
    const body = await response.json() as { access_token?: unknown };
    if (typeof body.access_token !== "string" || !body.access_token) {
      return null;
    }
    return body.access_token;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

// Parse one Play millisecond timestamp field. Google returns
// int64 values as strings; a number is accepted too. Anything
// else (absent, non-numeric, non-finite, non-positive) is null.
function toTimestampMs(value: unknown): number | null {
  if (typeof value === "number" || typeof value === "string") {
    const ms = Number(value);
    if (Number.isFinite(ms) && ms > 0) {
      return ms;
    }
  }
  return null;
}

// SHA-256 of a string, as lowercase hex. Used only to derive the
// stable subscription transaction id; the raw token is never
// stored or logged.
async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  const bytes = new Uint8Array(digest);
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, "0");
  }
  return hex;
}

// Verify one renewal subscription token with subscriptionsv3.
// UNTESTED against Google: no service account exists yet and this
// code has never run against the API. Calls GET
// /applications/{package}/purchases/subscriptionsv3/tokens/{token}
// and requires: an expiryTimeMillis in the future, and a
// paymentState of 1 (payment received) or 2 (free trial). A
// missing subscription (404) is not found, a pending payment
// (0 or 3) is pending, an expired subscription is expired, and
// a missing expiry or payment state fails closed. The returned
// transaction id is stable across renewals (see point 6 in the
// header) and expiresAt is the verified expiry. Only the
// outcome and the code are logged, never the token, the product
// ID, or any credential.
async function verifySubscriptionWithPlay(
  accessToken: string,
  productId: string,
  purchaseToken: string,
): Promise<PlayVerificationResult> {
  const url = `${PLAY_API_BASE}/applications/${encodeURIComponent(PLAY_PACKAGE_NAME)}` +
    `/purchases/subscriptionsv3/tokens/${encodeURIComponent(purchaseToken)}`;
  let response: Response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), GOOGLE_TIMEOUT_MS);
  try {
    response = await fetch(url, {
      method: "GET",
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: controller.signal,
    });
  } catch {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  } finally {
    clearTimeout(timer);
  }
  if (response.status === 404) {
    console.log("verify-play-purchase verification outcome=rejected code=purchase_not_found");
    return rejected("purchase_not_found", 402, "Google Play has no record of this subscription. The purchase was not recorded.");
  }
  if (!response.ok) {
    console.log(`verify-play-purchase verification outcome=rejected code=verification_failed play_status=${response.status}`);
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  let subsBody: { expiryTimeMillis?: unknown; paymentState?: unknown };
  try {
    subsBody = await response.json() as { expiryTimeMillis?: unknown; paymentState?: unknown };
  } catch {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  const expiryMs = toTimestampMs(subsBody.expiryTimeMillis);
  if (expiryMs === null) {
    console.log("verify-play-purchase verification outcome=rejected code=purchase_expiry_missing");
    return rejected("purchase_expiry_missing", 402, "Google Play did not report a subscription expiry. The purchase was not recorded.");
  }
  const paymentState = subsBody.paymentState;
  if (
    paymentState !== SUBSCRIPTION_PAYMENT_RECEIVED &&
    paymentState !== SUBSCRIPTION_PAYMENT_FREE_TRIAL
  ) {
    // A missing or pending payment state never verifies: a
    // payment pending (0), a deferred upgrade pending (3), or
    // an absent value all mean the subscription is not active.
    console.log("verify-play-purchase verification outcome=rejected code=purchase_pending");
    return rejected("purchase_pending", 402, "This subscription payment is still pending on Google Play. The purchase was not recorded.");
  }
  if (expiryMs <= Date.now()) {
    console.log("verify-play-purchase verification outcome=rejected code=subscription_expired");
    return rejected("subscription_expired", 402, "This subscription has expired on Google Play. The purchase was not recorded.");
  }
  const tokenHash = await sha256Hex(purchaseToken);
  const transactionId = `subs:${productId}:${tokenHash.slice(0, 32)}`;
  console.log("verify-play-purchase verification outcome=verified");
  return { outcome: "verified", transactionId, expiresAt: new Date(expiryMs).toISOString() };
}

// Verify one Play purchase token with the Play Developer API.
// UNTESTED against Google: no service account exists yet and this
// code has never run against the API. When the credentials are
// not configured, returns "unavailable" and the caller must fail
// closed (503, no write). Otherwise it exchanges a signed RS256
// JWT for an access token. A renewal product id is verified
// through subscriptionsv3 (see verifySubscriptionWithPlay). A
// one-time purchase calls GET
// /applications/{package}/purchases/products/{product}/tokens/{token},
// and requires: purchaseState 0 (purchased), an orderId, and a
// purchaseTimeMillis (the verified purchase time, for expires_at).
// A canceled purchase (state 1) is rejected as revoked, a
// pending purchase (state 2) as pending, and a missing purchase
// (404) as not found. Any mismatch, or any Google or network
// failure, is a rejection and nothing is stored. Only the
// outcome and the code are logged, never the token, the product
// ID, the order id, or any credential.
async function verifyPurchaseWithPlay(
  productId: string,
  purchaseToken: string,
  clientOrderId: string,
): Promise<PlayVerificationResult> {
  if (!PLAY_SERVICE_ACCOUNT_EMAIL || !PLAY_SERVICE_ACCOUNT_PRIVATE_KEY) {
    console.log("verify-play-purchase verification outcome=unavailable code=verification_unavailable");
    return { outcome: "unavailable" };
  }
  let accessToken: string | null;
  try {
    const jwt = await createServiceAccountJwt();
    accessToken = await fetchGoogleAccessToken(jwt);
  } catch {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  if (!accessToken) {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  // Renewal subscriptions verify through subscriptionsv3, not
  // products.get: the endpoints, the response shape, and the
  // transaction identity all differ.
  if (productId.endsWith(".renewal")) {
    return await verifySubscriptionWithPlay(accessToken, productId, purchaseToken);
  }
  const url = `${PLAY_API_BASE}/applications/${encodeURIComponent(PLAY_PACKAGE_NAME)}` +
    `/purchases/products/${encodeURIComponent(productId)}` +
    `/tokens/${encodeURIComponent(purchaseToken)}`;
  let response: Response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), GOOGLE_TIMEOUT_MS);
  try {
    response = await fetch(url, {
      method: "GET",
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: controller.signal,
    });
  } catch {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  } finally {
    clearTimeout(timer);
  }
  if (response.status === 404) {
    console.log("verify-play-purchase verification outcome=rejected code=purchase_not_found");
    return rejected("purchase_not_found", 402, "Google Play has no record of this purchase. The purchase was not recorded.");
  }
  if (!response.ok) {
    console.log(`verify-play-purchase verification outcome=rejected code=verification_failed play_status=${response.status}`);
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  let playBody: { purchaseState?: unknown; orderId?: unknown; purchaseTimeMillis?: unknown };
  try {
    playBody = await response.json() as { purchaseState?: unknown; orderId?: unknown; purchaseTimeMillis?: unknown };
  } catch {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  const purchaseState = playBody.purchaseState;
  if (purchaseState === PURCHASE_STATE_CANCELED) {
    console.log("verify-play-purchase verification outcome=rejected code=purchase_revoked");
    return rejected("purchase_revoked", 402, "This purchase was cancelled or refunded on Google Play. The purchase was not recorded.");
  }
  if (purchaseState === PURCHASE_STATE_PENDING) {
    console.log("verify-play-purchase verification outcome=rejected code=purchase_pending");
    return rejected("purchase_pending", 402, "This purchase is still pending on Google Play. The purchase was not recorded.");
  }
  if (purchaseState !== PURCHASE_STATE_PURCHASED) {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  const orderId = typeof playBody.orderId === "string" ? playBody.orderId : "";
  if (!orderId) {
    console.log("verify-play-purchase verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Play verification failed. The purchase was not recorded.");
  }
  // When the app sent an order id, it must match Google's. A
  // mismatch means the token and the order id come from different
  // purchases, so nothing is stored. A blank client order id
  // skips the check and Google's order id is stored.
  if (clientOrderId && clientOrderId !== orderId) {
    console.log("verify-play-purchase verification outcome=rejected code=order_id_mismatch");
    return rejected("order_id_mismatch", 402, "The Play purchase does not match the order id sent. The purchase was not recorded.");
  }
  // Expiry needs the verified purchase time: no purchase time,
  // no record. The expiry is never guessed from the server clock.
  const purchaseMs = toTimestampMs(playBody.purchaseTimeMillis);
  if (purchaseMs === null) {
    console.log("verify-play-purchase verification outcome=rejected code=purchase_expiry_missing");
    return rejected("purchase_expiry_missing", 402, "Google Play did not report a purchase time. The purchase was not recorded.");
  }
  console.log("verify-play-purchase verification outcome=verified");
  return {
    outcome: "verified",
    transactionId: orderId,
    expiresAt: new Date(purchaseMs + VALIDITY_MS).toISOString(),
  };
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return jsonResponse({ error: "Server is not configured" }, 500);
  }

  // The caller must be signed in. The service-role client is only
  // used for the writes, after the caller's JWT is verified.
  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace("Bearer ", "").trim();
  if (!token) {
    return jsonResponse({ error: "Missing Authorization header" }, 401);
  }

  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData?.user) {
    return jsonResponse({ error: "Invalid or expired token" }, 401);
  }
  const callerId = userData.user.id;
  const isAdmin = (userData.user.app_metadata as Record<string, unknown>)?.is_admin === true;

  let body: {
    action?: string;
    product_id?: string;
    purchase_token?: string;
    package_name?: string;
    order_id?: string;
    user_id?: string;
  };
  try {
    body = await req.json();
  } catch {
    return jsonResponse({ error: "Body must be JSON" }, 400);
  }
  const {
    action,
    product_id: productId,
    purchase_token: purchaseToken,
    package_name: packageName,
    order_id: orderId,
    user_id: userId,
  } = body;
  if (action !== "record") {
    return jsonResponse({ error: 'action must be "record"' }, 400);
  }
  if (!productId || !purchaseToken || !userId) {
    return jsonResponse(
      { error: "Body needs product_id, purchase_token and user_id" },
      400,
    );
  }
  // A caller records only their own purchases. An admin may act
  // for any user.
  if (!isAdmin && userId !== callerId) {
    return jsonResponse({ error: "You can only record your own purchases" }, 403);
  }
  // The write identity comes from the verified JWT, not from the
  // body. A non-admin caller writes only as callerId. An admin
  // writes for the body user_id, which names the admin target.
  // Every database write below uses effectiveUserId, never the
  // raw body value, so a body user_id can never choose the owner
  // of a row.
  const effectiveUserId = isAdmin ? userId : callerId;

  // Product allowlist, checked before any Google call and before
  // any write. Unknown product strings never reach the database,
  // even from a signed-in caller.
  if (!ALLOWED_PRODUCT_IDS.has(productId)) {
    return jsonResponse(
      { error: "product_id is not a known product", code: "unknown_product_id" },
      400,
    );
  }
  if (!purchaseToken.trim()) {
    return jsonResponse(
      { error: "purchase_token must not be blank", code: "invalid_purchase_token" },
      400,
    );
  }
  // The package name in the body must match the configured
  // package. The Play API URL is built from the configured
  // package, never from the body, so a mismatched body value can
  // never redirect verification to another app.
  if (!packageName || packageName !== PLAY_PACKAGE_NAME) {
    return jsonResponse(
      { error: "package_name does not match this app", code: "purchase_package_mismatch" },
      400,
    );
  }

  // Play verification, fail closed. "unavailable" means the Play
  // credentials are not configured: answer 503 and store
  // nothing. A rejection (missing, cancelled, pending, or
  // mismatched purchase, or a Google error) also stores nothing.
  const verification = await verifyPurchaseWithPlay(
    productId,
    purchaseToken,
    (orderId ?? "").trim(),
  );
  if (verification.outcome === "unavailable") {
    return jsonResponse(
      { error: "Play verification is not configured, so the purchase cannot be recorded", code: "verification_unavailable" },
      503,
    );
  }
  if (verification.outcome === "rejected") {
    return jsonResponse(
      { error: verification.message, code: verification.code },
      verification.httpStatus,
    );
  }
  const transactionId = verification.transactionId;
  const isRenewal = productId.endsWith(".renewal");

  // purchased_at comes from the server clock only. New rows get
  // the current server time, and an existing row is updated
  // WITHOUT purchased_at, so re-recording never moves the
  // original purchase time backwards or forwards. expires_at
  // follows the same rule for one-time purchases, but a renewal
  // re-record UPDATES expires_at to the latest verified expiry,
  // because a renewal extends access. This mirrors
  // record-purchase: no blind upsert, no re-activation of
  // revoked rows, and no reassignment of a transaction to
  // another user.
  const { data: existing, error: lookupError } = await supabase
    .from("user_purchases")
    .select("id, user_id, status")
    .eq("transaction_id", transactionId)
    .maybeSingle();
  if (lookupError) {
    return jsonResponse({ error: "Could not record the purchase" }, 500);
  }
  if (existing) {
    if (existing.user_id !== effectiveUserId) {
      return jsonResponse(
        { error: "This transaction is already recorded for another user", code: "transaction_owner_mismatch" },
        409,
      );
    }
    // A revoked or refunded row stays revoked or refunded. A
    // repeat record must never re-activate it, even when Google
    // still reports the purchase as valid.
    if (existing.status === "revoked" || existing.status === "refunded") {
      return jsonResponse(
        { error: "This purchase was revoked or refunded and cannot be recorded again", code: "purchase_revoked" },
        409,
      );
    }
    const updateFields: Record<string, unknown> = {
      user_id: effectiveUserId,
      product_id: productId,
      store: "play",
      status: "active",
    };
    // A renewal re-record moves expires_at to the latest
    // verified expiry; a one-time re-record leaves it alone.
    if (isRenewal) {
      updateFields.expires_at = verification.expiresAt;
    }
    const { error: updateError } = await supabase
      .from("user_purchases")
      .update(updateFields)
      .eq("transaction_id", transactionId)
      .eq("user_id", effectiveUserId);
    if (updateError) {
      return jsonResponse({ error: "Could not record the purchase" }, 500);
    }
    return jsonResponse({ ok: true, action, transaction_id: transactionId });
  }
  const { error: insertError } = await supabase
    .from("user_purchases")
    .insert({
      user_id: effectiveUserId,
      product_id: productId,
      store: "play",
      transaction_id: transactionId,
      purchased_at: new Date().toISOString(),
      expires_at: verification.expiresAt,
      status: "active",
    });
  if (insertError) {
    // Check violation (23514) means the store "play" value is
    // not in the user_purchases store check yet: the migration
    // in server/README.md has not been applied. Answer 503 so
    // the app keeps the record queued instead of treating it as
    // a permanent failure.
    if ((insertError as { code?: string }).code === "23514") {
      return jsonResponse(
        { error: "The play store value is not enabled in the database yet", code: "store_not_configured" },
        503,
      );
    }
    // Unique violation (23505) means a concurrent record wrote
    // the same transaction_id first. Re-read: if the row now
    // belongs to this user and is active, the purchase is
    // recorded and the repeat call succeeds. Anything else is
    // a genuine conflict, not a success.
    if ((insertError as { code?: string }).code === "23505") {
      const { data: raced } = await supabase
        .from("user_purchases")
        .select("user_id, status")
        .eq("transaction_id", transactionId)
        .maybeSingle();
      if (raced && raced.user_id === effectiveUserId && raced.status === "active") {
        return jsonResponse({ ok: true, action, transaction_id: transactionId });
      }
      return jsonResponse(
        { error: "This transaction is already recorded", code: "transaction_conflict" },
        409,
      );
    }
    return jsonResponse({ error: "Could not record the purchase" }, 500);
  }
  return jsonResponse({ ok: true, action, transaction_id: transactionId });
});
