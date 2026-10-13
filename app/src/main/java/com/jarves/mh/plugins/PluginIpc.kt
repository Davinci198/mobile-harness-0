package com.jarves.mh.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** A request on the plugin IPC wire. JSON only — no AIDL, no generated stubs. */
sealed interface PluginIpcRequest {
    /** Ask for the `tools.json` document. */
    data object ListTools : PluginIpcRequest

    /** Run one declared tool with [args]. */
    data class Execute(val name: String, val args: JSONObject) : PluginIpcRequest
}

/** One decoded request, with the id the reply has to carry back. */
data class PluginIpcCall(val id: Long, val request: PluginIpcRequest)

/**
 * One decoded reply.
 *
 * [result] is the payload on success: the `{"tools":[...]}` document for
 * [PluginIpcRequest.ListTools], the tool's own JSON object for
 * [PluginIpcRequest.Execute]. On failure [errorCode] is a stable machine
 * token (`timeout`, `unknown_tool`, `tool_error`, `bad_request`, ...).
 */
data class PluginIpcResponse(
    val id: Long,
    val ok: Boolean,
    val result: Any? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** Typed IPC failure; [code] is a stable machine token, not a sentence. */
class PluginIpcException(val code: String, message: String) : RuntimeException(message)

/**
 * The wire format: JSON strings carried in a [Bundle] over [Messenger].
 *
 * Requests: `{"id":1,"action":"listTools"}` and
 * `{"id":2,"action":"execute","name":"Echo","args":{...}}`.
 * Replies: `{"id":1,"ok":true,"result":...}` and
 * `{"id":2,"ok":false,"error":{"code":"unknown_tool","message":"..."}}`.
 *
 * Everything here is pure JVM: the codec is shared by host and plugin
 * processes and unit-tested without Android.
 */
object PluginIpc {

    /** [Bundle] key holding the JSON payload of a Messenger [Message]. */
    const val KEY_JSON = "com.jarves.mh.plugin.ipc.json"

    /** [Message.what] of a host -> plugin request. */
    const val MSG_REQUEST = 1

    /** [Message.what] of a plugin -> host reply. */
    const val MSG_RESPONSE = 2

    fun encodeRequest(id: Long, request: PluginIpcRequest): String {
        val json = JSONObject().put("id", id)
        when (request) {
            is PluginIpcRequest.ListTools ->
                json.put("action", PluginContract.ACTION_LIST_TOOLS)
            is PluginIpcRequest.Execute ->
                json.put("action", PluginContract.ACTION_EXECUTE)
                    .put("name", request.name)
                    .put("args", request.args)
        }
        return json.toString()
    }

    /** Decode a request; throws [JSONException] on malformed JSON and [PluginIpcException] on bad payloads. */
    fun decodeRequest(raw: String): PluginIpcCall {
        val json = JSONObject(raw)
        val id = json.optLong("id", -1L)
        return when (val action = json.optString("action")) {
            PluginContract.ACTION_LIST_TOOLS -> PluginIpcCall(id, PluginIpcRequest.ListTools)
            PluginContract.ACTION_EXECUTE -> {
                val name = json.optString("name").trim()
                if (name.isEmpty()) {
                    throw PluginIpcException("bad_request", "${PluginContract.ACTION_EXECUTE} needs a tool name")
                }
                PluginIpcCall(
                    id,
                    PluginIpcRequest.Execute(name, json.optJSONObject("args") ?: JSONObject()),
                )
            }
            else -> throw PluginIpcException("bad_request", "unknown action '$action'")
        }
    }

    fun encodeResponse(response: PluginIpcResponse): String {
        val json = JSONObject().put("id", response.id).put("ok", response.ok)
        if (response.ok) {
            json.put("result", response.result ?: JSONObject.NULL)
        } else {
            json.put(
                "error",
                JSONObject()
                    .put("code", response.errorCode ?: "error")
                    .put("message", response.errorMessage ?: ""),
            )
        }
        return json.toString()
    }

    /** Decode a reply; malformed JSON throws [JSONException]. */
    fun decodeResponse(raw: String): PluginIpcResponse {
        val json = JSONObject(raw)
        val id = json.optLong("id", -1L)
        if (json.optBoolean("ok")) {
            val result = json.opt("result")
            return PluginIpcResponse(id, true, result = result?.takeUnless { it === JSONObject.NULL })
        }
        val error = json.optJSONObject("error")
        return PluginIpcResponse(
            id = id,
            ok = false,
            errorCode = error?.optString("code")?.takeIf { it.isNotEmpty() } ?: "error",
            errorMessage = error?.optString("message") ?: "",
        )
    }

    /** Best-effort id recovery for error paths; -1 when the payload carries no usable id. */
    fun peekId(raw: String): Long = try {
        JSONObject(raw).optLong("id", -1L)
    } catch (error: JSONException) {
        -1L
    }

    fun success(id: Long, result: Any): PluginIpcResponse =
        PluginIpcResponse(id = id, ok = true, result = result)

    fun failure(id: Long, code: String, message: String): PluginIpcResponse =
        PluginIpcResponse(id = id, ok = false, errorCode = code, errorMessage = message)
}

/**
 * In-flight requests and their deadlines.
 *
 * Pure JVM so the timeout contract is unit-testable: [register] a call id,
 * [complete] when a reply arrives, [await] up to [timeoutMs]. [await]
 * unregisters the id on every path, so a reply that arrives after the
 * deadline is reported as unknown instead of resurrecting the call.
 */
class PluginPendingCalls {

    private val calls = ConcurrentHashMap<Long, CompletableFuture<String>>()

    val pendingCount: Int get() = calls.size

    fun register(id: Long): CompletableFuture<String> =
        CompletableFuture<String>().also { calls[id] = it }

    /** Deliver a raw reply; false when [id] is unknown or already completed. */
    fun complete(id: Long, rawResponse: String): Boolean {
        val future = calls[id] ?: return false
        return future.complete(rawResponse)
    }

    /**
     * Deliver the failure to every pending call.
     *
     * Ids are completed but not unregistered: each call's own [await] reaps
     * its id, so a caller caught between [register] and [await] still sees
     * the failure instead of waiting out its deadline.
     */
    fun failAll(error: PluginIpcException) {
        for (id in calls.keys.toList()) {
            calls[id]?.completeExceptionally(error)
        }
    }

    /** Drop a call whose [await] will never come (the send itself failed). */
    fun abandon(id: Long) {
        calls.remove(id)
    }

    /**
     * Block up to [timeoutMs] for the reply of [id].
     *
     * Returns null on timeout (or when [id] is unknown); rethrows the
     * [PluginIpcException] a [failAll] delivered. The id is unregistered in
     * every case.
     */
    fun await(id: Long, timeoutMs: Long): String? {
        val future = calls[id] ?: return null
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            null
        } catch (error: CancellationException) {
            null
        } catch (error: ExecutionException) {
            throw error.cause as? PluginIpcException
                ?: PluginIpcException("ipc", error.message ?: "plugin call failed")
        } finally {
            calls.remove(id, future)
        }
    }
}

/**
 * Host-side connection to one plugin package.
 *
 * Binds on demand to the service that handles [PluginContract.ACTION_TOOL_HOST]
 * (explicit component when [serviceName] is given, the action resolved inside
 * [packageName] otherwise), sends one JSON request per [Messenger] message and
 * blocks the calling thread for the reply.
 *
 * Lifecycle: the binding opens on the first call; it is dropped after a
 * request times out with nothing else in flight, when [close] runs (registry
 * cleanup), or when the platform reports the binding dead.
 *
 * [listTools] and [execute] block — never call them from the main thread.
 * A call outliving its deadline fails with `PluginIpcException("timeout")`.
 */
class PluginIpcClient(
    context: Context,
    private val packageName: String,
    private val serviceName: String? = null,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
) {

    private val appContext = context.applicationContext
    private val pending = PluginPendingCalls()
    private val nextId = AtomicLong(1L)
    private val stateLock = Any()

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var bindLatch: CountDownLatch? = null

    private var bound = false

    @Volatile
    private var closed = false

    private val replyMessenger by lazy {
        Messenger(
            Handler(Looper.getMainLooper(), Handler.Callback { message ->
                if (message.what == PluginIpc.MSG_RESPONSE) {
                    val raw = message.peekData()?.getString(PluginIpc.KEY_JSON)
                    if (raw != null) {
                        val id = PluginIpc.peekId(raw)
                        if (id >= 0L) pending.complete(id, raw)
                    }
                }
                true
            }),
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) {
                unbind()
                return
            }
            synchronized(stateLock) { serviceMessenger = Messenger(service) }
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(stateLock) { serviceMessenger = null }
        }

        override fun onBindingDied(name: ComponentName) = unbind()

        override fun onNullBinding(name: ComponentName) {
            synchronized(stateLock) { serviceMessenger = null }
            bindLatch?.countDown()
            unbind()
        }
    }

    /** Tools the plugin declares in `tools.json`. */
    fun listTools(timeoutMs: Long = DEFAULT_LIST_TOOLS_TIMEOUT_MS): List<PluginContract.PluginToolDeclaration> {
        val response = call(PluginIpcRequest.ListTools, timeoutMs)
        val document = response.result as? JSONObject
            ?: throw PluginIpcException(response.errorCode ?: "bad_response", response.errorMessage ?: "listTools failed")
        return PluginContract.parseTools(document.toString())
    }

    /** Run one declared tool; the plugin's JSON result, or a typed [PluginIpcException]. */
    fun execute(
        name: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = DEFAULT_EXECUTE_TIMEOUT_MS,
    ): JSONObject {
        val response = call(PluginIpcRequest.Execute(name, args), timeoutMs)
        return response.result as? JSONObject
            ?: throw PluginIpcException(response.errorCode ?: "bad_response", response.errorMessage ?: "execute failed")
    }

    /** Drop the binding and fail everything still in flight. */
    fun close() {
        synchronized(stateLock) { closed = true }
        unbind()
        pending.failAll(PluginIpcException("closed", "plugin IPC client for $packageName was closed"))
    }

    private fun call(request: PluginIpcRequest, timeoutMs: Long): PluginIpcResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "plugin IPC calls block; they must not run on the main thread"
        }
        val messenger = ensureConnected()
        val id = nextId.getAndIncrement()
        pending.register(id)
        val message = Message.obtain().apply {
            what = PluginIpc.MSG_REQUEST
            data = Bundle().apply { putString(PluginIpc.KEY_JSON, PluginIpc.encodeRequest(id, request)) }
            replyTo = replyMessenger
        }
        try {
            messenger.send(message)
        } catch (error: RemoteException) {
            pending.abandon(id)
            unbind()
            throw PluginIpcException("send_failed", "plugin $packageName is not reachable: ${error.message}")
        }
        val raw = pending.await(id, timeoutMs)
        if (raw == null) {
            if (pending.pendingCount == 0) unbind()
            throw PluginIpcException("timeout", "plugin $packageName did not answer within ${timeoutMs}ms")
        }
        return try {
            PluginIpc.decodeResponse(raw)
        } catch (error: JSONException) {
            throw PluginIpcException("bad_response", "plugin $packageName sent a malformed reply: ${error.message}")
        }
    }

    private fun ensureConnected(): Messenger {
        serviceMessenger?.let { return it }
        var initiate = false
        val latch = synchronized(stateLock) {
            serviceMessenger?.let { return it }
            if (closed) throw PluginIpcException("closed", "plugin IPC client for $packageName is closed")
            bindLatch ?: CountDownLatch(1).also {
                bindLatch = it
                bound = true
                initiate = true
            }
        }
        if (initiate) {
            val accepted = try {
                appContext.bindService(intent(), connection, Context.BIND_AUTO_CREATE)
            } catch (error: RuntimeException) {
                false
            }
            if (!accepted) {
                synchronized(stateLock) {
                    bound = false
                    bindLatch = null
                }
                latch.countDown()
                throw PluginIpcException("bind_failed", "plugin $packageName does not expose ${PluginContract.ACTION_TOOL_HOST}")
            }
        }
        if (!latch.await(bindTimeoutMs, TimeUnit.MILLISECONDS)) {
            unbind()
            throw PluginIpcException("timeout", "timed out binding to $packageName")
        }
        return serviceMessenger
            ?: throw PluginIpcException("bind_failed", "binding to $packageName ended without a service")
    }

    private fun intent(): Intent =
        if (serviceName != null) {
            Intent().setClassName(packageName, serviceName)
        } else {
            Intent(PluginContract.ACTION_TOOL_HOST).setPackage(packageName)
        }

    private fun unbind() {
        val wasBound = synchronized(stateLock) {
            serviceMessenger = null
            bindLatch = null
            bound.also { bound = false }
        }
        if (wasBound) {
            try {
                appContext.unbindService(connection)
            } catch (error: IllegalArgumentException) {
                // Already unbound by the platform (binding died / null binding).
            }
        }
    }

    companion object {
        const val DEFAULT_BIND_TIMEOUT_MS = 5_000L
        const val DEFAULT_LIST_TOOLS_TIMEOUT_MS = 5_000L
        const val DEFAULT_EXECUTE_TIMEOUT_MS = 30_000L
    }
}

/**
 * Host-side pool of [PluginIpcClient]s, one per plugin package.
 *
 * Clients are created and bound on first use; [cleanup] closes every client —
 * call it when the plugin surface is torn down (discovery refresh, shutdown).
 */
class PluginIpcRegistry(
    private val context: Context,
    private val clientFactory: (packageName: String) -> PluginIpcClient = { PluginIpcClient(context, it) },
) {

    private val clients = ConcurrentHashMap<String, PluginIpcClient>()

    fun client(packageName: String): PluginIpcClient =
        clients.computeIfAbsent(packageName, clientFactory)

    fun cleanup() {
        val open = clients.values.toList()
        clients.clear()
        for (client in open) {
            try {
                client.close()
            } catch (error: RuntimeException) {
                // Closing one plugin must never take the cleanup down.
            }
        }
    }
}

/**
 * Server side of the protocol: decode -> dispatch -> encode, reply over
 * [Messenger]. Plugin APKs extend this (usually via [PluginToolHostService])
 * and implement the two commands.
 *
 * [dispatch] is pure and never throws — every failure becomes an error reply —
 * so it is unit-testable and safe on any thread. [asBinder] wires the server
 * to a [Handler] on [looper]; it defaults to the main looper, pass a
 * [android.os.HandlerThread]'s looper when tool execution is slow.
 */
abstract class PluginIpcServer(private val looper: Looper? = null) {

    /** The `{"tools":[...]}` document this plugin declares. */
    protected abstract fun onListTools(): JSONObject

    /** Run one tool; throw [PluginIpcException] for typed failures. */
    protected abstract fun onExecute(name: String, args: JSONObject): JSONObject

    /** Decode one raw request, dispatch it, encode the reply. Never throws. */
    fun dispatch(rawRequest: String): String = try {
        val call = PluginIpc.decodeRequest(rawRequest)
        val result = when (val request = call.request) {
            is PluginIpcRequest.ListTools -> onListTools()
            is PluginIpcRequest.Execute -> onExecute(request.name, request.args)
        }
        PluginIpc.encodeResponse(PluginIpc.success(call.id, result))
    } catch (error: Exception) {
        val code = when (error) {
            is PluginIpcException -> error.code
            is JSONException -> "bad_request"
            else -> "tool_error"
        }
        PluginIpc.encodeResponse(
            PluginIpc.failure(PluginIpc.peekId(rawRequest), code, error.message ?: code),
        )
    }

    /** Binder for [android.app.Service.onBind]; replies travel back over Messenger. */
    fun asBinder(): IBinder = messenger.binder

    private val messenger by lazy {
        val dispatchHandler = Handler(looper ?: Looper.getMainLooper(), Handler.Callback { message ->
            onMessage(message)
            true
        })
        Messenger(dispatchHandler)
    }

    private fun onMessage(message: Message) {
        if (message.what != PluginIpc.MSG_REQUEST) return
        val raw = message.peekData()?.getString(PluginIpc.KEY_JSON) ?: return
        val replyTo = message.replyTo ?: return
        val reply = Message.obtain().apply {
            what = PluginIpc.MSG_RESPONSE
            data = Bundle().apply { putString(PluginIpc.KEY_JSON, dispatch(raw)) }
        }
        try {
            replyTo.send(reply)
        } catch (error: RemoteException) {
            // The host went away; nothing left to answer.
        }
    }
}
