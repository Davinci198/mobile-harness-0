package com.jarves.mh.runtime

import java.io.File

/**
 * Owns the loopback endpoint that gives guest agents a shell on the device.
 *
 * The server is only useful when the debug access level is the one in use and Shizuku has
 * actually granted access, so this starts and stops it with those two conditions and
 * leaves a small script in the guest that agents can call.
 */
object ShizukuExecHost {
    private const val GUEST_SCRIPT = "usr/local/bin/mh-shizuku"
    private const val GUEST_PARSER = "root/.mh-shizuku-parse.py"
    private const val GUEST_PORT_FILE = "root/.mh-shizuku-port"

    /** Name this shell is filed under in the per-tool permission settings. */
    const val TOOL: String = "ShizukuExec"

    @Volatile private var server: ShizukuExecServer? = null
    @Volatile private var forbidden: Boolean = false

    /** Mirrors the per-tool permission override so FORBID really stops the shell. */
    fun setForbidden(value: Boolean) {
        forbidden = value
    }

    /**
     * Starts the endpoint when the conditions are met and stops it otherwise, so the
     * guest script can never reach a shell the user did not enable.
     */
    @Synchronized
    fun sync(installer: RuntimeInstaller, shouldRun: Boolean) {
        if (!shouldRun || forbidden) {
            stop()
            clearGuestFiles(installer)
            return
        }
        if (server != null) return

        val started = ShizukuExecServer(enabled = { !forbidden }).start()
        server = started
        if (!writeGuestFiles(installer, started.port)) {
            // No guest to serve, so do not hold a listener open.
            stop()
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun isRunning(): Boolean = server != null

    private fun writeGuestFiles(installer: RuntimeInstaller, port: Int): Boolean = runCatching {
        val script = installer.guestFile(GUEST_SCRIPT)
        script.parentFile?.mkdirs()
        script.writeText(guestScript())
        script.setExecutable(true, false)
        installer.guestFile(GUEST_PARSER).writeText(guestParser())
        installer.guestFile(GUEST_PORT_FILE).writeText(port.toString())
        true
    }.getOrDefault(false)

    private fun clearGuestFiles(installer: RuntimeInstaller) {
        listOf(GUEST_SCRIPT, GUEST_PARSER, GUEST_PORT_FILE).forEach { path ->
            runCatching { File(installer.guestFile(path).absolutePath).delete() }
        }
    }

    /**
     * Runs one command on the Android host through the app's Shizuku bridge.
     *
     * The port is read from a file rather than baked in, because the listener binds an
     * ephemeral port and a hardcoded one would break on the next app start. The command
     * travels as a JSON-encoded argv, so quotes in it cannot break the payload.
     */
    internal fun guestScript(): String = """
        #!/bin/sh
        # Runs one command on the Android host through the harness app's Shizuku bridge.
        # Prints stdout, then stderr, and exits with the command's own code.
        set -u
        PORT_FILE=/root/.mh-shizuku-port
        PARSER=/root/.mh-shizuku-parse.py
        if [ ! -f "${'$'}PORT_FILE" ]; then
          echo "Shizuku bridge is not enabled. In the app: Settings -> Aspect -> Access level -> Debug (Shizuku)." >&2
          exit 127
        fi
        if [ "${'$'}#" -eq 0 ]; then
          echo "usage: mh-shizuku <command>" >&2
          exit 2
        fi
        PORT=$(cat "${'$'}PORT_FILE")
        BODY=$(python3 -c 'import json,sys; print(json.dumps({"command": sys.argv[1]}))' "${'$'}*")
        RESPONSE=$(printf '%s' "${'$'}BODY" | curl -s --max-time 120 -X POST \
          -H 'Content-Type: application/json' --data-binary @- \
          "http://127.0.0.1:${'$'}PORT/exec" 2>/dev/null)
        if [ -z "${'$'}RESPONSE" ]; then
          echo "no response from the harness app" >&2
          exit 70
        fi
        printf '%s' "${'$'}RESPONSE" | python3 "${'$'}PARSER"
    """.trimIndent() + "\n"

    /**
     * Kept in its own file: the response has to be decoded with real multi-line Python,
     * and Python refuses to run a block that starts indented.
     */
    internal fun guestParser(): String = """
        import json
        import sys

        try:
            data = json.load(sys.stdin)
        except ValueError:
            sys.stderr.write("could not read the response from the harness app\n")
            sys.exit(70)

        out = data.get("stdout") or ""
        err = data.get("stderr") or ""
        code = data.get("exitCode")

        if out:
            sys.stdout.write(out if out.endswith("\n") else out + "\n")
        if err:
            sys.stderr.write(err if err.endswith("\n") else err + "\n")
        if not data.get("ok") and not out and not err:
            sys.stderr.write("the command was refused\n")
        sys.exit(0 if data.get("ok") else (code or 1))
    """.trimIndent() + "\n"
}
