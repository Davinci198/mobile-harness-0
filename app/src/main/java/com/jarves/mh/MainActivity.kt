package com.jarves.mh

import android.content.ComponentCallbacks2
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.RemoteInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.runtime.KeepAliveTracker
import com.jarves.mh.update.UpdateRestart
import com.jarves.mh.ui.MainViewModel
import com.jarves.mh.ui.PocketDevApp
import com.jarves.mh.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeepAliveTracker.init(this, AppPreferences(application).keepAliveEnabled)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        handleAsk(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val screenShareLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                viewModel.onScreenShareConsent(result.resultCode, result.data)
            }
            LaunchedEffect(state.screenShareConsentRequest) {
                if (state.screenShareConsentRequest != null) {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    screenShareLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
            PocketTheme(
                themeMode = state.themeMode,
                colorTheme = state.colorTheme,
                customAccent = state.customAccent,
                customBackground = state.customBackground,
            ) {
                PocketDevApp(viewModel)
            }
        }
        relaunchAfterUpdate()
    }

    /**
     * The update install kills this process; the marker written before it is
     * consumed here, on the first start of the new build. If the relaunch is
     * refused (no notification permission on some OEMs, background-activity
     * limits), a notification carries the same intent so the update is not a
     * dead end.
     */
    private fun relaunchAfterUpdate() {
        val from = UpdateRestart.pendingRestartFrom(this) ?: return
        if (!UpdateRestart.updateApplied(from, BuildConfig.VERSION_NAME)) return
        if (savedInstanceState == null) {
            UpdateRestart.notifyUpdateInstalled(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Deliberately not calling setIntent: only this delivery opens the chat,
        // so a later process-death recreation replays the original launch intent
        // instead of re-triggering "Ask anything".
        handleAsk(intent)
    }

    private fun handleAsk(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_ASK || intent.action == Intent.ACTION_ASSIST) {
            val reply = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(EXTRA_ASK_TEXT)
                ?.toString()
                .orEmpty()
            viewModel.handleAsk(reply)
        }
    }

    /**
     * The transcript writer batches live deltas for a short window; leaving the
     * app must not drop the last of them (the process can be killed while
     * backgrounded).
     */
    override fun onStop() {
        viewModel.flushTranscriptWrites()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            viewModel.releaseMemoryCaches()
        }
    }

    companion object {
        /** "Ask anything" entry point: assist gesture, launcher shortcut, notification action. */
        const val ACTION_ASK = "com.jarves.mh.ASK"

        /** Result key of the keep-alive notification's inline reply action. */
        const val EXTRA_ASK_TEXT = "com.jarves.mh.ASK_TEXT"
    }
}
