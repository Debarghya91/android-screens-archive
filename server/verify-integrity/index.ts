// supabase/functions/verify-integrity/index.ts
// Payments Training Hub Edge Function: decode a Play Integrity
// token with the Play Integrity API and answer whether the device,
// the app install and the licence check out. This is the
// server half of the Android premium access gate: the app requests
// an integrity token from Google Play (see IntegrityCheck) and
// sends it here, and only this function's answer counts. The token
// is an encrypted payload the app cannot open, so no verdict is
// ever computed on the device.
//
// Guests may call this function: the app gates premium for
// signed-out buyers too. Deploy it with JWT verification OFF (the
// gateway still requires the apikey header). A bearer token, when
// present, is validated and a bad one is rejected; when absent
// the call runs as a guest. The token itself is single-use and
// bound to this app, this device and the nonce, so the oracle
// value of an apikey-only call is nil: a caller learns only
// whether their own freshly minted token passes.
//
// SECURITY MODEL:
//   1. Server-only decoding. The integrity token is decoded by
//      the Play Integrity API using the Play service account.
//      The decryption key never leaves Google; the app never
//      sees the verdict payload, only this function's answer.
//   2. Nonce binding. The decoded request nonce must equal the
//      nonce the app sent. A token minted for another request
//      cannot be replayed.
//   3. Package binding. The decoded request package must equal
//      the configured package, and the body package_name must
//      match it too. The API URL is built from the configured
//      package, never from the body.
//   4. Strict verdict. Pass requires all three: app recognition
//      PLAY_RECOGNIZED (the app is the genuine Play build),
//      device recognition containing MEETS_DEVICE_INTEGRITY
//      (not just MEETS_BASIC_INTEGRITY: rooted, tampered and
//      emulated devices fail), and licensing LICENSED (the user
//      obtained the app from Play). Anything else is a failed
//      verdict, answered 200 with ok false so the app can tell
//      "checked and failed" from "could not check".
//   5. Fail closed. When the Play service-account credentials are
//      not configured, verification returns "unavailable" and
//      the function answers 503 with code
//      verification_unavailable. Nothing is trusted. There is no
//      fallback that trusts the client.
//   6. No logging of secrets. Only the outcome and the code are
//      logged, never the token, the nonce, or any credential.
//
// UNTESTED WARNING. The Play Integrity decoding code below has
// NEVER been run against the Play Integrity API, and no Play
// service-account credential exists yet (Play Console API access
// is not set up and no service account has been created). There
// is no Play sandbox access and no Supabase access from the
// machine that wrote this code. The code was statically checked
// only: re-read line by line, brace and parenthesis balance
// checked, and the API shapes checked against Google's published
// docs. Do NOT describe this function as tested or verified until
// the test steps in server/README.md have actually been run.
// Until the Play secrets are set, every call answers 503 and the
// app runs the gate in degraded mode (installer, signature and
// root checks still enforce; see PremiumAccessGate).
//
// Secrets (set in the Supabase dashboard or with the CLI, never
// in this file). These are the SAME two secrets the
// verify-play-purchase function uses: one service account serves
// both functions.
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY (as before; only
//     needed when a bearer token is present to validate)
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
// access exists). Same account as verify-play-purchase, plus the
// Integrity API:
//   1. In Play Console, go to Setup, then API access. Link or
//      create the Google Cloud project for this app.
//   2. Under Service accounts, create a new service account (or
//      use an existing one) and grant it access in Play Console.
//      The purchase-verification call needs "View financial
//      data". The Integrity API needs the service account linked
//      here; no extra Play Console permission applies to
//      integrity decoding, but the Google Cloud project must
//      have the Play Integrity API enabled (APIs and Services,
//      Library, search "Play Integrity API", Enable).
//   3. In Google Cloud Console, open the service account, go to
//      Keys, create a JSON key, and download it once. Copy
//      client_email and private_key into the Supabase secrets
//      above. Store the JSON file somewhere safe outside the
//      repo. It cannot be downloaded again; if it is lost,
//      delete the key and create a new one.
//   4. Set the secrets, then deploy.
//
// DEPLOY: see server/README.md. Two routes are documented: the
// Supabase CLI, and the dashboard Editor with an esbuild-bundled
// single file (the route proven for the payu-* functions on
// 13 Nov 2026). This file has no local imports, so it bundles
// cleanly. Keep JWT verification OFF for this function: guests
// must be able to verify integrity. The gateway still requires
// the apikey header.
//
// Request: POST with the apikey header, and an optional Bearer
// <user JWT> in the Authorization header. Body:
//   { "integrity_token": "<Play Integrity token>",
//     "nonce": "<UUID the app generated for this request>",
//     "package_name": "com.paymentstraininghub.app" }
//
// Responses: pass is 200 { ok: true, integrity: {...},
// checked_at }; a failed verdict is 200 { ok: false, code:
// "integrity_failed", integrity: {...}, failed_checks: [...] }.
// Error codes: integrity_token_mismatch (400), missing fields
// (400), integrity_package_mismatch (400),
// verification_unavailable (503), verification_failed (502).

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
// Play Developer API credentials, from Supabase secrets only.
// When either is missing, verification is unavailable and calls
// fail closed (503). The values are never logged and never leave
// this function. Shared with verify-play-purchase.
const PLAY_SERVICE_ACCOUNT_EMAIL = Deno.env.get("PLAY_SERVICE_ACCOUNT_EMAIL") ?? "";
const PLAY_SERVICE_ACCOUNT_PRIVATE_KEY = Deno.env.get("PLAY_SERVICE_ACCOUNT_PRIVATE_KEY") ?? "";
const PLAY_PACKAGE_NAME = Deno.env.get("PLAY_PACKAGE_NAME") ?? "com.paymentstraininghub.app";

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity";
const INTEGRITY_API_BASE = "https://playintegrity.googleapis.com/v1";
const GOOGLE_TIMEOUT_MS = 10000;
const SERVICE_ACCOUNT_JWT_LIFETIME_SECONDS = 3600;

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

// The outcome of an integrity decode. "decoded" means Google was
// asked and answered; passed carries the strict-verdict result.
// "unavailable" means the credentials are not configured.
// "rejected" means the token or the request did not check out, or
// Google could not be reached; httpStatus and code are the
// response to send.
type IntegrityVerificationResult =
  | {
    outcome: "decoded";
    passed: boolean;
    failedChecks: string[];
    verdict: {
      appRecognition: string;
      deviceRecognition: string[];
      licensing: string;
    };
  }
  | { outcome: "unavailable" }
  | { outcome: "rejected"; code: string; httpStatus: number; message: string };

function rejected(code: string, httpStatus: number, message: string): IntegrityVerificationResult {
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
// account email, scope the Play Integrity scope, aud the Google
// token endpoint, iat now, exp one hour out (Google's maximum).
// Signed with Web Crypto only, no dependency. Throws when the key
// cannot be imported or the signing fails; the caller turns that
// into a fail-closed 502. UNTESTED against Google.
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
    scope: INTEGRITY_SCOPE,
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
// token is fetched for every call: the function is stateless, the
// volume is at most one call per device per day (the app caches a
// pass for 24 hours), and caching would add expiry bookkeeping
// for no needed gain. Returns null when Google refuses the
// exchange or cannot be reached; the caller turns that into a
// fail-closed 502.
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

// Decode one integrity token with the Play Integrity API.
// UNTESTED against Google: no service account exists yet and this
// code has never run against the API. When the credentials are
// not configured, returns "unavailable" and the caller must fail
// closed (503). Otherwise it exchanges a signed RS256 JWT for an
// access token, calls POST
// /v1/{package}:decodeIntegrityToken, and applies the strict
// verdict: nonce match, package match, PLAY_RECOGNIZED,
// MEETS_DEVICE_INTEGRITY present, LICENSED. Only the outcome and
// the code are logged, never the token, the nonce, or any
// credential.
async function decodeIntegrityToken(
  integrityToken: string,
  expectedNonce: string,
): Promise<IntegrityVerificationResult> {
  if (!PLAY_SERVICE_ACCOUNT_EMAIL || !PLAY_SERVICE_ACCOUNT_PRIVATE_KEY) {
    console.log("verify-integrity verification outcome=unavailable code=verification_unavailable");
    return { outcome: "unavailable" };
  }
  let accessToken: string | null;
  try {
    const jwt = await createServiceAccountJwt();
    accessToken = await fetchGoogleAccessToken(jwt);
  } catch {
    console.log("verify-integrity verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Integrity verification failed.");
  }
  if (!accessToken) {
    console.log("verify-integrity verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Integrity verification failed.");
  }
  const url = `${INTEGRITY_API_BASE}/${encodeURIComponent(PLAY_PACKAGE_NAME)}:decodeIntegrityToken`;
  let response: Response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), GOOGLE_TIMEOUT_MS);
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ integrityToken }),
      signal: controller.signal,
    });
  } catch {
    console.log("verify-integrity verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Integrity verification failed.");
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    console.log(`verify-integrity verification outcome=rejected code=verification_failed play_status=${response.status}`);
    return rejected("verification_failed", 502, "Integrity verification failed.");
  }
  let payload: {
    tokenPayloadExternal?: {
      requestDetails?: { requestPackageName?: unknown; nonce?: unknown };
      appIntegrity?: {
        appRecognitionVerdict?: unknown;
        packageName?: unknown;
      };
      deviceIntegrity?: { deviceRecognitionVerdict?: unknown };
      accountDetails?: { appLicensingVerdict?: unknown };
    };
  };
  try {
    payload = await response.json() as typeof payload;
  } catch {
    console.log("verify-integrity verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Integrity verification failed.");
  }
  const external = payload.tokenPayloadExternal;
  if (!external) {
    console.log("verify-integrity verification outcome=rejected code=verification_failed");
    return rejected("verification_failed", 502, "Integrity verification failed.");
  }
  // Nonce binding: the token must belong to this request.
  const decodedNonce = external.requestDetails?.nonce;
  if (decodedNonce !== expectedNonce) {
    console.log("verify-integrity verification outcome=rejected code=integrity_token_mismatch");
    return rejected("integrity_token_mismatch", 400, "The integrity token does not match this request.");
  }
  // Package binding: the token must belong to this app.
  const requestPackage = external.requestDetails?.requestPackageName;
  const appPackage = external.appIntegrity?.packageName;
  if (
    requestPackage !== PLAY_PACKAGE_NAME ||
    (typeof appPackage === "string" && appPackage !== PLAY_PACKAGE_NAME)
  ) {
    console.log("verify-integrity verification outcome=rejected code=integrity_package_mismatch");
    return rejected("integrity_package_mismatch", 400, "The integrity token is not for this app.");
  }
  const appRecognition = typeof external.appIntegrity?.appRecognitionVerdict === "string"
    ? external.appIntegrity.appRecognitionVerdict
    : "";
  const deviceRecognition = Array.isArray(external.deviceIntegrity?.deviceRecognitionVerdict)
    ? (external.deviceIntegrity.deviceRecognitionVerdict as unknown[]).filter(
      (value): value is string => typeof value === "string",
    )
    : [];
  const licensing = typeof external.accountDetails?.appLicensingVerdict === "string"
    ? external.accountDetails.appLicensingVerdict
    : "";
  const failedChecks: string[] = [];
  if (appRecognition !== "PLAY_RECOGNIZED") failedChecks.push("app_recognition");
  if (!deviceRecognition.includes("MEETS_DEVICE_INTEGRITY")) failedChecks.push("device_integrity");
  if (licensing !== "LICENSED") failedChecks.push("licensing");
  const passed = failedChecks.length === 0;
  console.log(`verify-integrity verification outcome=decoded passed=${passed}`);
  return {
    outcome: "decoded",
    passed,
    failedChecks,
    verdict: {
      appRecognition,
      deviceRecognition,
      licensing,
    },
  };
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  // A bearer token, when present, must be a valid user JWT.
  // When absent the call runs as a guest, which the gate needs
  // for signed-out buyers. JWT verification stays OFF for this
  // function at the gateway so guest calls reach this handler.
  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace("Bearer ", "").trim();
  if (token) {
    if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
      return jsonResponse({ error: "Server is not configured" }, 500);
    }
    const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);
    const { data: userData, error: userError } = await supabase.auth.getUser(token);
    if (userError || !userData?.user) {
      return jsonResponse({ error: "Invalid or expired token" }, 401);
    }
  }

  let body: {
    integrity_token?: string;
    nonce?: string;
    package_name?: string;
  };
  try {
    body = await req.json();
  } catch {
    return jsonResponse({ error: "Body must be JSON" }, 400);
  }
  const {
    integrity_token: integrityToken,
    nonce,
    package_name: packageName,
  } = body;
  if (!integrityToken || !integrityToken.trim() || !nonce || !nonce.trim()) {
    return jsonResponse(
      { error: "Body needs integrity_token and nonce" },
      400,
    );
  }
  // The package name in the body must match the configured
  // package. The Integrity API URL is built from the configured
  // package, never from the body, so a mismatched body value can
  // never redirect verification to another app.
  if (!packageName || packageName !== PLAY_PACKAGE_NAME) {
    return jsonResponse(
      { error: "package_name does not match this app", code: "integrity_package_mismatch" },
      400,
    );
  }

  // Integrity decoding, fail closed. "unavailable" means the Play
  // credentials are not configured: answer 503 and trust nothing.
  // A rejection (mismatched token or package, or a Google error)
  // also trusts nothing. A decoded verdict answers 200 with ok
  // true or false, so the app can tell "checked and failed" from
  // "could not check".
  const verification = await decodeIntegrityToken(
    integrityToken.trim(),
    nonce.trim(),
  );
  if (verification.outcome === "unavailable") {
    return jsonResponse(
      { error: "Integrity verification is not configured", code: "verification_unavailable" },
      503,
    );
  }
  if (verification.outcome === "rejected") {
    return jsonResponse(
      { error: verification.message, code: verification.code },
      verification.httpStatus,
    );
  }
  if (!verification.passed) {
    return jsonResponse({
      ok: false,
      code: "integrity_failed",
      integrity: verification.verdict,
      failed_checks: verification.failedChecks,
      checked_at: new Date().toISOString(),
    });
  }
  return jsonResponse({
    ok: true,
    integrity: verification.verdict,
    checked_at: new Date().toISOString(),
  });
});
