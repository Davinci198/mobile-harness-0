package com.jarves.mh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jarves.mh.R
import com.jarves.mh.runtime.KeepAliveTracker
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
    val viewState = remember { PtyViewState() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    // Toggle ↕ (ca in Termux): taste extra verticale pe latura dreapta,
    // pentru a lasa terminalul pe tot latimea (TUI / vim / ncmpcpp).
    var verticalKeys by rememberSaveable { mutableStateOf(false) }
    val setVerticalKeys: (Boolean) -> Unit = { verticalKeys = it }

    // Prima sesiune pentru acest proiect se creaza la deschiderea tabului.
    // Sesiunile traiesc in registry (obiect de top-level) -> la iesirea din tab
    // procesele (opencode/freebuff) raman deschise.
    LaunchedEffect(projectSlug) {
        if (PtyTerminalRegistry.sessions(projectSlug).isEmpty() && error == null) {
            try {
                PtyTerminalRegistry.newSession(installer, context, projectSlug)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
    }
    val backend = PtyTerminalRegistry.active(projectSlug)
    val backendSessions = PtyTerminalRegistry.sessions(projectSlug)

    // Legarea onTextChanged -> onScreenUpdated se face acum prin backend (vezi
    // PtySessionClient.onTextChanged); actualizam callback-ul la fiecare
    // recompozitie fiindca TerminalView-ul se recreate la revenirea in tab.
    backend?.onScreenUpdate = { terminalView?.onScreenUpdated() }

    // NU inchidem sesiunea la iesirea din compozitie: ramane activa in fundal.
    // Instructiuni de inchidere cand se intra pe alt proiect: vezi mai sus.

    // Cand aplicatia revine in prim-plan, TerminalView trebuie sa isi reia
    // focusul, altfel IME-ul ramane indreptat spre alta fereastra si inputul
    // nu mai ajunge in terminal (outputul se vede, tastele nu).
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusContext = context
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val view = terminalView ?: return@LifecycleEventObserver
                val ime = focusContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val imeWasActive = ime.isActive
                view.requestFocus()
                if (imeWasActive) ime.showSoftInput(view, 0)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // App-ul e edge-to-edge (enableEdgeToEdge) => adjustResize nu ridica
    // singur continutul; fara imePadding randul de taste extra (si ultimele
    // linii din terminal) raman sub tastatura, deci butoanele "nu functioneaza"
    // (tap-ul ajunge in tastatura).
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        error?.let {
            Text(
                text = stringResource(R.string.pty_error, it),
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
                    onClick = { backend?.write("$cmd\r") },
                    modifier = Modifier.padding(end = 4.dp),
                ) { Text(cmd, maxLines = 1) }
            }
        }
        // Tab-uri de sesiuni (ca in Termux): numar = sesiune, cea activa e
        // marcat; apasare scurta comuta, lunga inchide; + deschide sesiune noua.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((i, b) in backendSessions.withIndex()) {
                val isActive = b === backend
                SessionChip(
                    label = "${i + 1}",
                    active = isActive,
                    onClick = { PtyTerminalRegistry.select(b) },
                    onLongClick = {
                        if (backendSessions.size > 1) PtyTerminalRegistry.kill(b)
                    },
                )
            }
            SessionChip(label = "+", active = false, onClick = {
                runCatching { PtyTerminalRegistry.newSession(installer, context, projectSlug) }
                    .onFailure { error = it.message ?: it.toString() }
            }, onLongClick = {})
        }
        backend?.let { backend ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        // setTextSize() din TerminalView primeste pixeli (nu sp),
                        // desi documentatia zice altfel; fara scalarea cu densitatea
                        // textul iese minuscul (14px ~= 5.7sp pe 390dpi).
                        viewState.density = ctx.resources.displayMetrics.density
                        TerminalView(ctx, null).apply {
                            // Obligatoriu inainte de layout: mRenderer se creeaza
                            // doar aici; altfel onSizeChanged -> updateSize() da
                            // NullPointerException (mRenderer null).
                            setTextSize(viewState.textSizePx)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTerminalViewClient(PtyViewClient(viewState))
                            viewState.terminalView = this
                            terminalView = this
                            attachSession(backend.session)
                            requestFocus()
                        }
                    },
                    // La comutarea intre sesiuni AndroidView-ul NU se recreeaza
                    // (factory ruleaza o singura data), deci re-atacham sesiunea
                    // activa din `update`.
                    update = { v ->
                        if (v.mTermSession !== backend.session) {
                            v.attachSession(backend.session)
                        }
                    },
                    onRelease = {
                        viewState.terminalView = null
                        terminalView = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                if (verticalKeys) {
                    PtyVerticalExtraKeys(
                        view = terminalView,
                        session = backend.session,
                        state = viewState,
                        context = context,
                        onHorizontal = { setVerticalKeys(false) },
                        onNewSession = { runCatching { PtyTerminalRegistry.newSession(installer, context, projectSlug) }.onFailure { error = it.message ?: it.toString() } },
                        sessions = backendSessions,
                        activeSession = backend,
                        onSelectSession = { PtyTerminalRegistry.select(it) },
                    )
                }
            }
        }
        if (!verticalKeys) {
            PtyExtraKeys(
                view = terminalView,
                session = backend?.session,
                state = viewState,
                context = context,
                onVertical = { setVerticalKeys(true) },
                onNewSession = { runCatching { PtyTerminalRegistry.newSession(installer, context, projectSlug) }.onFailure { error = it.message ?: it.toString() } },
                sessions = backendSessions,
                activeSession = backend,
                onSelectSession = { PtyTerminalRegistry.select(it) },
            )
        }
    }
}

private const val MIN_TEXT_SIZE_SP = 8f
private const val MAX_TEXT_SIZE_SP = 32f
private const val DEFAULT_TEXT_SIZE_SP = 16f

/**
 * Sesiunile PTY traiesc pe toata durata vietii procesului, nu doar a ecranului:
 * iesirea din tab nu opreste procesele din guest (opencode/freebuff). Sustinem
 * mai multe sesiuni per proiect (ca tab-urile din Termux); una e activa, se
 * poate comuta si inchide. Listele sunt snapshot-state Compose, deci modificarile
 * declansate din tastatura (≡ -> New session) recompun interfata.
 */
/** Marker for KeepAliveTracker: at least one PTY session exists. */
private const val PTY_SESSION_HOLD = "pty"

private object PtyTerminalRegistry {
    val backends = mutableStateListOf<PtyTerminalBackend>()
    private val activeByProject = mutableStateMapOf<String, Int>()

    private fun globalIndexOf(backend: PtyTerminalBackend): Int =
        backends.indexOfFirst { it === backend }

    fun sessions(projectSlug: String): List<PtyTerminalBackend> =
        backends.filter { it.projectSlug == projectSlug }

    /** Sesiunea activa pentru un proiect (sau ultima creata, daca proiectul nu
     *  are una marcata). */
    fun active(projectSlug: String): PtyTerminalBackend? {
        val idx = activeByProject[projectSlug]
            ?: backends.indices.lastOrNull { backends[it].projectSlug == projectSlug }
            ?: return null
        return backends.getOrNull(idx)?.takeIf { it.projectSlug == projectSlug }
    }

    fun newSession(installer: RuntimeInstaller, context: android.content.Context, projectSlug: String): PtyTerminalBackend {
        val backend = PtyTerminalBackend(installer, context, projectSlug)
        backends.add(backend)
        activeByProject[projectSlug] = backends.lastIndex
        KeepAliveTracker.acquire(PTY_SESSION_HOLD)
        return backend
    }

    fun select(backend: PtyTerminalBackend) {
        val idx = globalIndexOf(backend)
        if (idx >= 0) activeByProject[backend.projectSlug] = idx
    }

    fun kill(backend: PtyTerminalBackend) {
        val idx = globalIndexOf(backend)
        if (idx < 0) return
        backend.close()
        backends.removeAt(idx)
        for ((project, v) in activeByProject.toMap()) {
            when {
                v == idx -> activeByProject.remove(project)
                v > idx -> activeByProject[project] = v - 1
            }
        }
        if (backends.isEmpty()) KeepAliveTracker.release(PTY_SESSION_HOLD)
    }
}

/** Stare partajata intre clientul TerminalView si randul de taste extra. */
private class PtyViewState {
    var controlDown by mutableStateOf(false)
    var altDown by mutableStateOf(false)

    /** Marimea fontului in sp, pastrata intre recrearile view-ului. */
    var textSizeSp: Float = DEFAULT_TEXT_SIZE_SP
    var density: Float = 1f

    /** TerminalView-ul activ, pentru zoom (onScale -> setTextSize). */
    var terminalView: TerminalView? = null

    /** setTextSize() asteapta pixeli; convertim din sp. */
    val textSizePx: Int get() = (textSizeSp * density).toInt().coerceAtLeast(6)
}

/** Tastatura compacta neagra 2x7 ca in Termux (text alb). */
@Composable
private fun PtyExtraKeys(
    view: TerminalView?,
    session: TerminalSession?,
    state: PtyViewState,
    context: Context,
    onVertical: () -> Unit,
    onNewSession: () -> Unit,
    sessions: List<PtyTerminalBackend>,
    activeSession: PtyTerminalBackend?,
    onSelectSession: (PtyTerminalBackend) -> Unit,
) {
    // Comportament identic cu TerminalExtraKeys din Termux: KeyEvent ACTION_UP
    // trimis in TerminalView.onKeyDown; KeyHandler le mapeaza in secvente VT.
    fun sendKeyCode(keyCode: Int) {
        val v = view ?: return
        val meta = (if (state.controlDown) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (state.altDown) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0)
        v.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    fun write(raw: String) {
        session?.write(raw)
    }

    fun pasteClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) write(text)
    }

    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ExtraKeyButton("ESC", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
            Box(modifier = Modifier.weight(1f)) {
                ExtraKeyButton("≡", modifier = Modifier.fillMaxWidth()) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pty_new_session)) }, onClick = {
                        menuOpen = false
                        onNewSession()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pty_paste)) }, onClick = {
                        menuOpen = false
                        pasteClipboard()
                    })
                    DropdownMenuItem(text = { Text("→ vertical") }, onClick = {
                        menuOpen = false
                        onVertical()
                    })
                    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    DropdownMenuItem(text = { Text(if (keyboardVisible) stringResource(R.string.pty_hide_keyboard) else stringResource(R.string.pty_show_keyboard)) }, onClick = {
                        menuOpen = false
                        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        if (keyboardVisible) ime.hideSoftInputFromWindow(view?.windowToken, 0)
                        else if (view != null) ime.showSoftInput(view, 0)
                    })
                    for ((i, b) in sessions.withIndex()) {
                        val active = b === activeSession
                        DropdownMenuItem(
                            text = { Text(if (active) "• ${i + 1}  ${b.projectSlug}" else "${i + 1}  ${b.projectSlug}") },
                            onClick = {
                                menuOpen = false
                                onSelectSession(b)
                            },
                        )
                    }
                }
            }
            ExtraKeyButton("↕", modifier = Modifier.weight(1f)) { onVertical() }
            ExtraKeyButton("HOME", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
            ExtraKeyButton("↑", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
            ExtraKeyButton("END", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_MOVE_END) }
            ExtraKeyButton("PGUP", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // ⇄: switch la urmatorul IME daca exista mai multe; altfel TAB.
            ExtraKeyButton("⇄", modifier = Modifier.weight(1f)) {
                val v = view
                val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val switched = v != null && ime.switchToNextInputMethod(v.windowToken, false)
                if (!switched) sendKeyCode(KeyEvent.KEYCODE_TAB)
            }
            ExtraKeyButton("CTRL", active = state.controlDown, modifier = Modifier.weight(1f)) { state.controlDown = !state.controlDown }
            ExtraKeyButton("ALT", active = state.altDown, modifier = Modifier.weight(1f)) { state.altDown = !state.altDown }
            ExtraKeyButton("←", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
            ExtraKeyButton("↓", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
            ExtraKeyButton("→", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ExtraKeyButton("PGDN", modifier = Modifier.weight(1f)) { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        }
    }
}

/** Tastele extra in mod vertical (↕): panel pe latura dreapta, 2 coloane x 7. */
@Composable
private fun PtyVerticalExtraKeys(
    view: TerminalView?,
    session: TerminalSession?,
    state: PtyViewState,
    context: Context,
    onHorizontal: () -> Unit,
    onNewSession: () -> Unit,
    sessions: List<PtyTerminalBackend>,
    activeSession: PtyTerminalBackend?,
    onSelectSession: (PtyTerminalBackend) -> Unit,
) {
    fun sendKeyCode(keyCode: Int) {
        val v = view ?: return
        val meta = (if (state.controlDown) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (state.altDown) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0)
        v.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }
    fun write(raw: String) { session?.write(raw) }
    fun pasteClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) write(text)
    }

    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(100.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ExtraKeyButton("↕", compact = true, modifier = Modifier.fillMaxWidth()) { onHorizontal() }
        ExtraKeyButton("ESC", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
        ExtraKeyButton("⇄", compact = true, modifier = Modifier.fillMaxWidth()) {
            val v = view
            val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (v == null || !ime.switchToNextInputMethod(v.windowToken, false)) sendKeyCode(KeyEvent.KEYCODE_TAB)
        }
        ExtraKeyButton("CTRL", active = state.controlDown, compact = true, modifier = Modifier.fillMaxWidth()) { state.controlDown = !state.controlDown }
        ExtraKeyButton("ALT", active = state.altDown, compact = true, modifier = Modifier.fillMaxWidth()) { state.altDown = !state.altDown }
        ExtraKeyButton("HOME", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
        ExtraKeyButton("END", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_MOVE_END) }
        ExtraKeyButton("PGUP", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        ExtraKeyButton("PGDN", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        ExtraKeyButton("↑", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
        ExtraKeyButton("←", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        ExtraKeyButton("↓", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        ExtraKeyButton("→", compact = true, modifier = Modifier.fillMaxWidth()) { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Box(modifier = Modifier.fillMaxWidth()) {
            ExtraKeyButton("≡", compact = true, modifier = Modifier.fillMaxWidth()) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.pty_new_session)) }, onClick = {
                    menuOpen = false
                    onNewSession()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.pty_paste)) }, onClick = {
                    menuOpen = false
                    pasteClipboard()
                })
                val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                DropdownMenuItem(text = { Text(if (keyboardVisible) stringResource(R.string.pty_hide_keyboard) else stringResource(R.string.pty_show_keyboard)) }, onClick = {
                    menuOpen = false
                    val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (keyboardVisible) ime.hideSoftInputFromWindow(view?.windowToken, 0)
                    else if (view != null) ime.showSoftInput(view, 0)
                })
                for ((i, b) in sessions.withIndex()) {
                    val active = b === activeSession
                    DropdownMenuItem(
                        text = { Text(if (active) "• ${i + 1}  ${b.projectSlug}" else "${i + 1}  ${b.projectSlug}") },
                        onClick = {
                            menuOpen = false
                            onSelectSession(b)
                        },
                    )
                }
            }
        }
    }
}

/** Buton compact, inchis la culoare, text alb (stil Termux). */
@Composable
private fun ExtraKeyButton(
    label: String,
    active: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val height = if (compact) 38.dp else 44.dp
    val bg = if (active) Color(0xFF3A3A3A) else Color(0xFF1B1B1B)
    val fg = if (active) Color(0xFFFFFFFF) else Color(0xFFE6E6E6)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
        )
    }
}

/** Tab-ul unei sesiuni: apasare scurta = comuta, lunga = inchide. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (active) Color(0xFF3A3A3A) else Color(0xFF1B1B1B)
    val fg = if (active) Color(0xFFFFFFFF) else Color(0xFF9E9E9E)
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** Construieste TerminalSession cu shell = proot spre Ubuntu guest. */
private class PtyTerminalBackend(
    installer: RuntimeInstaller,
    context: android.content.Context,
    val projectSlug: String,
) {
    val session: TerminalSession
    private val appContext = context.applicationContext

    /** Setat de ecran la fiecare recompozitie: creeaza legatura output -> redraw.
     *  Mutabil fiindca TerminalView-ul se recreate la revenirea in tab. */
    var onScreenUpdate: () -> Unit = {}

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
            PtySessionClient(appContext).also { client ->
                client.onScreenUpdate = { onScreenUpdate() }
            },
        )
    }

    fun write(text: String) {
        session.write(text)
    }

    fun close() {
        runCatching { session.finishIfRunning() }
    }
}

private class PtySessionClient(
    private val context: Context,
) : TerminalSessionClient {
    /** Apelat pe main thread din TerminalSession.MainThreadHandler la fiecare
     *  chunk de output; invalideaza TerminalView (randare imediata a echo-ului). */
    var onScreenUpdate: () -> Unit = {}

    override fun onTextChanged(changedSession: TerminalSession) {
        onScreenUpdate()
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}

    // Copy/paste: toolbar-ul de selectie din TerminalView apeleaza metodele astea
    // pe client; daca sunt goale, Copy nu pune nimic in clipboard si Paste nu
    // citeste nimic (de aici "copy/paste nu merge").
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Mobile Harness", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) session?.write(text)
    }
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

private class PtyViewClient(
    private val state: PtyViewState,
) : TerminalViewClient {
    // TerminalView acumuleaza mScaleFactor si ne cheama aici. Peste pragurile
    // 0.9/1.1 schimbam marimea fontului cu 2sp si resetam acumulatorul (1f);
    // sub prag intoarcem valoarea acumulata ca sa se adune in continuare.
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val view = state.terminalView ?: return 1f
            val next = state.textSizeSp + if (scale > 1f) 2f else -2f
            val clamped = next.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
            if (clamped != state.textSizeSp) {
                state.textSizeSp = clamped
                view.setTextSize(state.textSizePx)
            }
            return 1f
        }
        return scale
    }
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
    override fun readShiftKey(): Boolean = false
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
