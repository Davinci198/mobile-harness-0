package com.jarves.mh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.app.RemoteInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.runtime.KeepAliveTracker
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
            PocketTheme(themeMode = state.themeMode) {
                PocketDevApp(viewModel)
            }
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

    companion object {
        /** "Ask anything" entry point: assist gesture, launcher shortcut, notification action. */
        const val ACTION_ASK = "com.jarves.mh.ASK"

        /** Result key of the keep-alive notification's inline reply action. */
        const val EXTRA_ASK_TEXT = "com.jarves.mh.ASK_TEXT"
    }
}
