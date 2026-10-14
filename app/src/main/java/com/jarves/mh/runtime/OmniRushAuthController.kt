package com.jarves.mh.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class OmniRushAuthStatus {
    SIGNED_OUT,
    STARTING,
    AWAITING_APPROVAL,
    COMPLETING,
    SIGNED_IN,
    EXPIRED,
    ERROR,
}

data class OmniRushAuthState(
    val status: OmniRushAuthStatus = OmniRushAuthStatus.SIGNED_OUT,
    val userCode: String? = null,
    val approveUrl: String? = null,
    val message: String? = null,
)

/**
 * OmniRush device-flow login, mirroring the CLI (`omnirush login`): the app
 * gets its OWN device identity, so its token pair never races another
 * client (Termux CLI, desktop) sharing one login. Pure HTTPS against the
 * manager — no guest CLI involved.
 */
class OmniRushAuthController(
    private val deviceName: String,
    private val clientVersion: () -> String,
    private val onTokens: (accessToken: String, refreshToken: String) -> Unit,
    private val onSignedOut: () -> Unit,
    initiallySignedIn: Boolean = false,
) {
    private val mutableState = MutableStateFlow(
        OmniRushAuthState(
            status = if (initiallySignedIn) OmniRushAuthStatus.SIGNED_IN else OmniRushAuthStatus.SIGNED_OUT,
        ),
    )
    val state: StateFlow<OmniRushAuthState> = mutableState.asStateFlow()

    @Volatile private var cancelled = false

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (mutableState.value.status == OmniRushAuthStatus.AWAITING_APPROVAL ||
            mutableState.value.status == OmniRushAuthStatus.COMPLETING
        ) return@withContext
        cancelled = false
        mutableState.value = OmniRushAuthState(OmniRushAuthStatus.STARTING)
        val auth = OmniRush.authorizeNow(deviceName, clientVersion())
        if (auth == null) {
            if (!cancelled) {
                mutableState.value = OmniRushAuthState(
                    OmniRushAuthStatus.ERROR,
                    message = "Could not reach omnirush.ai. Check the connection and retry.",
                )
            }
            return@withContext
        }
        mutableState.value = OmniRushAuthState(
            OmniRushAuthStatus.AWAITING_APPROVAL,
            userCode = auth.userCode,
            approveUrl = auth.approveUrl,
        )
        while (!cancelled && System.currentTimeMillis() < auth.expiresAtMillis) {
            delay(auth.intervalSec.coerceIn(2, 30) * 1000)
            if (cancelled) break
            when (val poll = OmniRush.pollTokenNow(auth.deviceCode, clientVersion())) {
                is OmniRush.DeviceTokenPoll.Approved -> {
                    mutableState.value = OmniRushAuthState(OmniRushAuthStatus.COMPLETING)
                    runCatching { onTokens(poll.accessToken, poll.refreshToken) }
                    if (!cancelled) mutableState.value = OmniRushAuthState(OmniRushAuthStatus.SIGNED_IN)
                    return@withContext
                }
                is OmniRush.DeviceTokenPoll.Failed -> {
                    if (poll.detail == "network") continue
                    if (!cancelled) {
                        mutableState.value = OmniRushAuthState(
                            if (poll.detail == "expired_token") OmniRushAuthStatus.EXPIRED
                            else OmniRushAuthStatus.ERROR,
                            message = if (poll.detail == "expired_token") "The code expired. Start again."
                            else "Sign-in failed (${poll.detail}).",
                        )
                    }
                    return@withContext
                }
                OmniRush.DeviceTokenPoll.Pending -> Unit
            }
        }
        if (!cancelled) {
            mutableState.value = OmniRushAuthState(
                OmniRushAuthStatus.EXPIRED,
                message = "The code expired. Start again.",
            )
        }
    }

    fun cancel() {
        cancelled = true
        if (mutableState.value.status != OmniRushAuthStatus.SIGNED_IN) {
            mutableState.value = OmniRushAuthState(OmniRushAuthStatus.SIGNED_OUT)
        }
    }

    /** Marks an explicit sign-out (the caller wipes the vault pair). */
    fun signedOut() {
        cancelled = true
        runCatching { onSignedOut() }
        mutableState.value = OmniRushAuthState(OmniRushAuthStatus.SIGNED_OUT)
    }

    /** Surfaces a dead pair discovered elsewhere (409/401 on rotation). */
    fun invalidateSession(message: String) {
        mutableState.value = OmniRushAuthState(OmniRushAuthStatus.ERROR, message = message)
    }
}
