package com.paymentstraininghub.app.billing

/*
 * ServerSync: records Play purchases on the server through the
 * verify-play-purchase Edge Function, so an entitlement can follow
 * the signed-in account to other devices. Best effort only: a failed
 * record never locks the buyer out on this device, because the
 * on-device Play entitlement already works. Failures are queued in
 * EntitlementStore and retried by retryPendingServerSync.
 *
 * VERIFICATION: the function verifies the purchase token against
 * the Google Play Developer API before writing anything: it
 * requires purchaseState PURCHASED, a matching product id and a
 * present order id, and writes the row with store "play", the
 * order id as transaction id and the server clock as
 * purchased_at. A token the Play API does not confirm never
 * becomes a server entitlement. The function source lives in
 * server/verify-play-purchase/ in this project; it is NOT
 * deployed yet, and its Play service-account credential does not
 * exist yet, so until both land every call fails and stays
 * queued, which is the safe behaviour. Recording store "play"
 * also needs a database migration adding "play" to the
 * user_purchases store check (see the function README).
 *
 * SERVER READ PATH (iOS v67 parity, BillingManager): the app also
 * READS user_purchases rows (status=eq.active, NO store filter),
 * so a website purchase unlocks the same account in-app, exactly
 * as on iOS. That read lives in BillingManager, which trusts the
 * server row the verify-play-purchase function wrote, as iOS
 * trusts the row record-purchase wrote. Nothing here fakes
 * verification.
 *
 * Accounts only: sign-in is mandatory, so every record carries
 * the signed-in user's id and the call carries their bearer
 * token. A record with no user id is never built and never
 * queued: the function requires the id to match the token's
 * subject, so such a record could never be accepted. Queued
 * records written by older builds without a user id are claimed
 * by the signed-in account when they are retried (see
 * retryPendingServerSync); with nobody signed in, no retry is
 * attempted. The on-device Play entitlement is unaffected by
 * any of this.
 *
 * No secrets live here. Only the publishable key, read from
 * auth.SupabaseClient.PUBLISHABLE_KEY, is ever sent, as the apikey
 * header. The bearer token comes from AuthManager.validAccessToken
 * at send time, so retries always use a fresh token.
 */

import com.android.billingclient.api.Purchase
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.security.PinningObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ServerSync {

    private const val FUNCTION_URL =
        "https://eofsotgclhwsdwvbbtib.supabase.co/functions/v1/verify-play-purchase"
    private const val FALLBACK_PACKAGE_NAME = "com.paymentstraininghub.app"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(PinningObserver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Build the durable record for one Play purchase, or null when
     * the purchase carries no product or no token and so cannot be
     * recorded, or when there is no signed-in user id: a record
     * without an account can never be accepted by the server, so
     * it is never built and never queued. One-time products carry
     * exactly one product ID; the first is used.
     */
    fun recordFor(purchase: Purchase, userId: String?): PendingPurchaseRecord? {
        if (userId.isNullOrBlank()) return null
        val productId = purchase.products.firstOrNull() ?: return null
        if (purchase.purchaseToken.isBlank()) return null
        return PendingPurchaseRecord(
            productId = productId,
            purchaseToken = purchase.purchaseToken,
            packageName = purchase.packageName ?: FALLBACK_PACKAGE_NAME,
            orderId = purchase.orderId ?: "",
            purchaseTime = purchase.purchaseTime,
            userId = userId
        )
    }

    /**
     * Record one purchase on the server. Returns success only when
     * the function answers 2xx. Any other status, or any network
     * error, returns failure, and the caller queues a retry. A
     * purchase with no signed-in user fails here without a
     * network call, and the caller queues nothing for it. Never
     * throws for an expected failure.
     */
    suspend fun recordPurchase(purchase: Purchase, userId: String?): Result<Unit> {
        val record = recordFor(purchase, userId)
            ?: return Result.failure(
                IllegalStateException("Purchase cannot be recorded: no product, no token, or no signed-in user")
            )
        return sendRecord(record)
    }

    /**
     * Retry every queued server record, oldest first. Records that
     * succeed are removed from the queue; failures stay queued for
     * the next retry. One bad record never stops the rest. With
     * nobody signed in, nothing is attempted: no record can be
     * accepted without an account. A legacy queued record with no
     * user id (written by an older build, when guest purchases
     * were queued) is claimed by the signed-in account: the
     * purchase belongs to this device's Play account, and the
     * signed-in user is the account it now follows.
     */
    suspend fun retryPendingServerSync() {
        val currentUserId = AuthManager.currentUserId()
        if (currentUserId.isNullOrBlank()) return
        val queued = try {
            EntitlementStore.pendingServerSyncRecords()
        } catch (e: Exception) {
            return
        }
        for (record in queued) {
            val effective = if (record.userId.isNullOrBlank()) {
                record.copy(userId = currentUserId)
            } else {
                record
            }
            val outcome = try {
                sendRecord(effective)
            } catch (e: Exception) {
                Result.failure<Unit>(e)
            }
            if (outcome.isSuccess) {
                try {
                    EntitlementStore.removePendingServerSync(record)
                } catch (e: Exception) {
                    // The record stays queued and a later retry will
                    // send it again; the function upserts, so a
                    // repeat send never duplicates the server row.
                }
            }
        }
    }

    private suspend fun sendRecord(record: PendingPurchaseRecord): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val token = try {
                    AuthManager.validAccessToken()
                } catch (e: Exception) {
                    null
                }
                val bodyJson = JSONObject().apply {
                    put("action", "record")
                    put("product_id", record.productId)
                    put("purchase_token", record.purchaseToken)
                    put("package_name", record.packageName)
                    put("order_id", record.orderId)
                    // The function requires user_id to match the
                    // bearer token's subject. Records always carry
                    // an id: recordFor refuses to build one without
                    // it, and retries stamp the signed-in account
                    // onto legacy records, so this guard is
                    // defensive only. purchase_time is deliberately
                    // not sent: the function stamps the server
                    // clock, so a client clock can never backdate
                    // a purchase.
                    if (!record.userId.isNullOrBlank()) {
                        put("user_id", record.userId)
                    }
                }
                val requestBuilder = Request.Builder()
                    .url(FUNCTION_URL)
                    .header("apikey", SupabaseClient.PUBLISHABLE_KEY)
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                val response = httpClient.newCall(requestBuilder.build()).execute()
                response.use {
                    if (it.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            RuntimeException("verify-play-purchase failed with HTTP ${it.code}")
                        )
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
