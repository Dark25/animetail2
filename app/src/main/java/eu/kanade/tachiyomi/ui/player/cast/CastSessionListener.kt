package eu.kanade.tachiyomi.ui.player.cast

import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import eu.kanade.tachiyomi.ui.main.MainCastManager
import eu.kanade.tachiyomi.ui.player.CastManager

class CastSessionListener : SessionManagerListener<CastSession> {
    private var playerCastManager: CastManager? = null
    private var mainCastManager: MainCastManager? = null

    constructor(castManager: CastManager) {
        this.playerCastManager = castManager
    }

    constructor(castManager: MainCastManager) {
        this.mainCastManager = castManager
    }

    override fun onSessionStarted(session: CastSession, sessionId: String) {
        playerCastManager?.onSessionConnected(session)
        playerCastManager?.handleQualitySelection()
        mainCastManager?.onSessionConnected(session)
    }

    override fun onSessionEnded(session: CastSession, error: Int) {
        playerCastManager?.onSessionEnded()
        mainCastManager?.onSessionEnded()
    }

    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
        playerCastManager?.onSessionConnected(session)
        mainCastManager?.onSessionConnected(session)
    }

    override fun onSessionResumeFailed(session: CastSession, error: Int) {}

    override fun onSessionStarting(session: CastSession) {}

    override fun onSessionStartFailed(session: CastSession, error: Int) {
    }

    override fun onSessionEnding(session: CastSession) {
        // Not used
    }

    override fun onSessionSuspended(session: CastSession, reason: Int) {
        // Not used
    }

    override fun onSessionResuming(session: CastSession, sessionId: String) {
        // Not used
    }
}
