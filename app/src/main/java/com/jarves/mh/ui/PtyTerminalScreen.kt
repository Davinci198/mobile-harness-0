package com.jarves.mh.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.runtime.RuntimeInstaller
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * Pas 3 (PLAN-TERMINAL): terminal VT real (grid + ANSI + fullscreen) peste
 * [TerminalView]/[TerminalSession] din termux, cu procesul lansat in PRoot
 * cu exact argv/env din [RuntimeInstaller.process].
 *
 * Nu atinge fluxul vechi (TerminalScreen pe linii); inlocuirea tabului se
 * face in spate cu flagul [USE_PTY_TERMINAL] din PocketDevApp.
 */
@Composable
fun PtyTerminalScreen(
    installer: RuntimeInstaller,
    projectSlug: String,
    modifier: Modifier = Modifier,
    quickCommands: List<String> = listOf("uname -a", "ls -la", "pwd"),
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    val sessionHolder = remember {
        try {
            PtyTerminalBackend(installer, context, projectSlug)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            null
        }
    }

    DisposableEffect(sessionHolder) {
        onDispose { sessionHolder?.close() }
    }

    val viewState = remember { PtyViewState() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        error?.let {
            Text(
                text = "PTY error: $it",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            for (cmd in quickCommands) {
                OutlinedButton(
                    onClick = { sessionHolder?.write("$cmd\r") },
                    modifier = Modifier.padding(end = 4.dp),
                ) { Text(cmd, maxLines = 1) }
            }
        }
        sessionHolder?.let { backend ->
            AndroidView(
                factory = { ctx ->
                    FocusAwareTerminalView(ctx, null).apply {
                        // Obligatoriu inainte de layout: mRenderer se creeaza
                        // doar aici; altfel onSizeChanged -> updateSize() da
                        // NullPointerException (mRenderer null).
                        setTextSize(14)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTerminalViewClient(PtyViewClient(viewState))
                        terminalView = this
                        attachSession(backend.session)
                        requestFocus()
                    }
                },
                onRelease = { terminalView = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
        }
        PtyExtraKeys(
            view = terminalView,
            session = sessionHolder?.session,
            state = viewState,
            context = context,
        )
    }
}

/** Stare partajata intre clientul TerminalView si randul de taste extra. */
private class PtyViewState {
    var controlDown by mutableStateOf(false)
    var altDown by mutableStateOf(false)
    var shiftDown by mutableStateOf(false)
}

/** Rand de taste extra (pas 4): ESC/CTRL/ALT/TAB/`/`/sageti si toggle tastatura. */
@Composable
private fun PtyExtraKeys(
    view: TerminalView?,
    session: TerminalSession?,
    state: PtyViewState,
    context: Context,
) {
    fun sendKeyCode(keyCode: Int) {
        val view = view ?: return
        val meta = (if (state.controlDown) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (state.altDown) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (state.shiftDown) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
        view.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    fun write(raw: String) {
        session?.write(raw)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        ExtraKeyButton("KEYBOARD", active = false) {
            val view = view ?: return@ExtraKeyButton
            val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (ime.isActive) ime.hideSoftInputFromWindow(view.windowToken, 0)
            else {
                view.requestFocus()
                ime.showSoftInput(view, 0)
            }
        }
        ExtraKeyButton("ESC", active = false) { sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
        ExtraKeyButton("CTRL", active = state.controlDown) { state.controlDown = !state.controlDown }
        ExtraKeyButton("ALT", active = state.altDown) { state.altDown = !state.altDown }
        ExtraKeyButton("SHIFT", active = state.shiftDown) { state.shiftDown = !state.shiftDown }
        ExtraKeyButton("TAB", active = false) { sendKeyCode(KeyEvent.KEYCODE_TAB) }
        ExtraKeyButton("/", active = false) { write("/") }
        ExtraKeyButton("~", active = false) { write("~") }
        ExtraKeyButton("↑", active = false) { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
        ExtraKeyButton("↓", active = false) { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        ExtraKeyButton("←", active = false) { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        ExtraKeyButton("→", active = false) { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
    }
}

@Composable
private fun ExtraKeyButton(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = if (active) {
        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary)
    } else {
        androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
    }
    OutlinedButton(
        onClick = onClick,
        colors = colors,
        modifier = Modifier.padding(end = 4.dp),
    ) { Text(label) }
}

/** Construieste TerminalSession cu shell = proot spre Ubuntu guest. */
private class PtyTerminalBackend(
    installer: RuntimeInstaller,
    context: android.content.Context,
    projectSlug: String,
) {
    val session: TerminalSession
    private val appContext = context.applicationContext

    init {
        val installed = installer.installedRuntime()
        val guestWorkspacePath = "/workspace/$projectSlug"
        val workspace = File(appContext.filesDir, "workspace/$projectSlug").apply { mkdirs() }
        File(installed.rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        val prootTemp = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() }
        val bridge = File(appContext.filesDir, "runtime-bridge").apply { mkdirs() }

        val argv = buildList {
            add(installed.proot.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(installed.rootfs.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            for (hostPath in listOf("/system", "/apex", "/vendor", "/product")) {
                if (File(hostPath).exists()) {
                    File(installed.rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b"); add(hostPath)
                }
            }
            add("-b"); add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b"); add("${bridge.absolutePath}:/pocket-bridge")
            add("-w"); add(guestWorkspacePath)
            add("/bin/bash"); add("--login")
        }
        val env = arrayOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "TERM=xterm-256color",
            "LD_LIBRARY_PATH=${appContext.applicationInfo.nativeLibraryDir}",
            "PROOT_NO_SECCOMP=1",
            "PROOT_TMP_DIR=${prootTemp.absolutePath}",
            "PROOT_LOADER=${File(appContext.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath}",
            "GLIBC_TUNABLES=glibc.pthread.rseq=0",
        )
        session = TerminalSession(
            installed.proot.absolutePath,
            appContext.filesDir.absolutePath,
            argv.toTypedArray(),
            env,
            500,
            PtySessionClient(),
        )
    }

    fun write(text: String) {
        session.write(text)
    }

    fun close() {
        runCatching { session.finishIfRunning() }
    }
}

private class PtySessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

/**
 * TerminalView care isi recapata focusul (si implicit IME-ul) ori de cate ori
 * fereastra revine in prim-plan. Fara asta, dupa ce aplicatia trece prin
 * background/recents, tastatura ramane indreptata spre alta fereastra si
 * inputul nu mai ajunge in terminal (outputul se vede, tastele nu).
 */
private class FocusAwareTerminalView(
    context: Context,
    attrs: AttributeSet?,
) : TerminalView(context, attrs) {
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeWasActive = ime.isActive
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            post {
                requestFocus()
                if (imeWasActive) ime.showSoftInput(this, 0)
            }
        }
    }
}

private class PtyViewClient(
    private val state: PtyViewState,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    // Modificatorii extra (CTRL/ALT/SHIFT) sunt cititi de TerminalView din
    // readControlKey()/readAltKey()/readShiftKey() la fiecare key event.
    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = state.controlDown
    override fun readAltKey(): Boolean = state.altDown
    override fun readShiftKey(): Boolean = state.shiftDown
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}
