package eu.kanade.tachiyomi.ui.main

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import eu.kanade.tachiyomi.ui.player.cast.CastSessionListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class MainCastManager(
    private val context: Context,
) {
    private var _castContext: CastContext? = null
    val castContext: CastContext?
        get() = _castContext

    private var castSession: CastSession? = null
    private var sessionListener: CastSessionListener? = null

    private val _castState = MutableStateFlow(com.google.android.gms.cast.framework.CastState.NO_DEVICES_AVAILABLE)
    val castState: StateFlow<Int> = _castState.asStateFlow()

    private val isCastApiAvailable
        get() = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    init {
        initializeCast()
    }

    private fun initializeCast() {
        if (!isCastApiAvailable) return

        try {
            _castContext = CastContext.getSharedInstance(context.applicationContext)
            sessionListener = CastSessionListener(this)
            registerSessionListener()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            cleanup(endSession = false)
        }
    }

    fun onSessionConnected(session: CastSession) {
        castSession = session
        updateCastState(com.google.android.gms.cast.framework.CastState.CONNECTED)
    }

    fun onSessionEnded() {
        castSession = null
        updateCastState(com.google.android.gms.cast.framework.CastState.NO_DEVICES_AVAILABLE)
    }

    fun updateCastState(state: Int) {
        _castState.value = state
    }

    fun registerSessionListener() {
        try {
            sessionListener?.let {
                _castContext?.sessionManager?.addSessionManagerListener(
                    it,
                    CastSession::class.java,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            cleanup(endSession = false)
        }
    }

    fun unregisterSessionListener() {
        try {
            sessionListener?.let {
                _castContext?.sessionManager?.removeSessionManagerListener(
                    it,
                    CastSession::class.java,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    fun refreshCastContext() {
        try {
            castSession = _castContext?.sessionManager?.currentCastSession
            castSession?.let {
                if (it.isConnected) updateCastState(com.google.android.gms.cast.framework.CastState.CONNECTED)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            cleanup(endSession = false)
        }
    }

    fun cleanup(endSession: Boolean = false) {
        try {
            castSession = null
            sessionListener = null
            _castContext = null
            updateCastState(com.google.android.gms.cast.framework.CastState.NO_DEVICES_AVAILABLE)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
