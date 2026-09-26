package com.jarves.mh.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarves.mh.R
import com.jarves.mh.data.AppLocale
import java.util.Locale

/**
 * Prepares an Agent reply for on-device TTS: code (fences and inline spans) is
 * dropped because reading it aloud is noise, whitespace is collapsed, and the
 * result is capped so a huge reply never turns into an unbounded monologue.
 */
internal fun replyForSpeech(text: String, maxChars: Int = 1200): String {
    val withoutCode = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`[^`]*`"), " ")
    val collapsed = withoutCode.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
}

/** On-device text-to-speech for Agent replies; created lazily, shut down with its composable. */
class ReplySpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                pending?.let(::doSpeak)
                pending = null
            }
        }
    }

    fun speak(text: String) {
        val prepared = replyForSpeech(text)
        if (prepared.isEmpty()) return
        if (ready) doSpeak(prepared) else pending = prepared
    }

    private fun doSpeak(text: String) {
        val engine = tts ?: return
        engine.language = when (AppLocale.currentTag(appContext)) {
            AppLocale.TAG_ENGLISH -> Locale.ENGLISH
            AppLocale.TAG_ROMANIAN -> Locale.forLanguageTag("ro")
            else -> Locale.getDefault()
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null as Bundle?, "assistant-reply")
    }

    fun shutdown() {
        pending = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

@Composable
fun rememberReplySpeaker(): ReplySpeaker {
    val context = LocalContext.current
    val speaker = remember { ReplySpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}

/**
 * Tap-to-toggle microphone: it only listens between an explicit tap and the
 * end of speech (or a second tap), never in the background. The recognized
 * text is handed back through [onResult]; [onListeningChange] lets the input
 * field show a live "Listening…" hint.
 */
@Composable
fun VoiceMicButton(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    onListeningChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnListening by rememberUpdatedState(onListeningChange)
    val micCd = stringResource(R.string.voice_mic_cd)
    val stopCd = stringResource(R.string.voice_mic_listening)
    val permissionNeeded = stringResource(R.string.voice_permission_needed)
    val unavailable = stringResource(R.string.voice_recognizer_unavailable)

    fun setListening(value: Boolean) {
        listening = value
        currentOnListening(value)
    }

    fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        active.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                setListening(false)
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!heard.isNullOrEmpty()) currentOnResult(heard)
            }

            override fun onError(error: Int) = setListening(false)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            val tag = AppLocale.currentTag(context)
            if (tag != AppLocale.TAG_SYSTEM) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        }
        setListening(true)
        active.startListening(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            Toast.makeText(context, permissionNeeded, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    IconButton(
        onClick = {
            if (listening) {
                recognizer?.stopListening()
            } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = if (listening) stopCd else micCd,
            modifier = Modifier.size(20.dp),
            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
