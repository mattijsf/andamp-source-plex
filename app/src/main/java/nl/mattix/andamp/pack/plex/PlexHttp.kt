// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One request to plex.tv or to a server. An interface, so the library, the
 * sign-in and the settings actions are tested against recorded answers with
 * no network.
 *
 * The URL is whole, built by the caller from `Plex.tv` or `PlexServer.url`,
 * so one client serves both hosts. The token travels in a header.
 */
interface PlexHttp {
    /** `GET <url>`, with this phone's headers, [token] when there is one, and [headers] such as the paging ones. */
    suspend fun get(
        url: HttpUrl?,
        token: String = "",
        headers: Map<String, String> = emptyMap(),
    ): PlexReply

    /** `POST <url>`, with [body] as JSON when there is one. */
    suspend fun post(
        url: HttpUrl?,
        token: String = "",
        body: JSONObject? = null,
    ): PlexReply

    /** `DELETE <url>`. */
    suspend fun delete(
        url: HttpUrl?,
        token: String = "",
    ): PlexReply
}

/**
 * What came back: an answer, or one of three kinds of failure.
 *
 * Plex reports failure with the HTTP status, so the body of a failure is not
 * read.
 */
sealed interface PlexReply {
    /**
     * A success. [body] is a `JSONObject` for most calls, a `JSONArray` for
     * plex.tv's resources, and an empty `JSONObject` for an empty body such
     * as a 204.
     */
    data class Answered(
        val body: Any,
    ) : PlexReply

    /**
     * Nothing readable came back: no route, a timeout, or a body that is not
     * JSON, such as a captive portal's login page or the XML a server answers
     * when a proxy dropped the `Accept` header.
     */
    data class Unreachable(
        val why: String,
    ) : PlexReply

    /**
     * HTTP 401: the token is not valid. On a server it was revoked under the
     * account's devices, or the account lost access to the server.
     * `PlexLibrary` then signs the listener out.
     */
    data object Unauthorized : PlexReply

    /** Any other status that is not success; a 404 for a code is one that expired. */
    data class Rejected(
        val status: Int,
    ) : PlexReply

    /** The object in an answer, or null when the answer is not one. */
    val obj: JSONObject? get() = (this as? Answered)?.body as? JSONObject

    companion object {
        /** A status and a body as one of the four replies. */
        fun read(
            status: Int,
            body: String,
        ): PlexReply {
            if (status == UNAUTHORIZED) return Unauthorized
            if (status !in SUCCESS) return Rejected(status)
            if (body.isBlank()) return Answered(JSONObject())
            val value = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            // JSONTokener reads an unquoted word as a string, so an HTML page
            // parses as "<html>" and XML as "<MediaContainer"; only an object
            // or an array counts as an answer
            return if (value is JSONObject || value is JSONArray) Answered(value) else Unreachable("the answer was not JSON")
        }

        private const val UNAUTHORIZED = 401
        private val SUCCESS = 200..299
    }
}

/**
 * [PlexHttp] over OkHttp, with [device]'s headers on every call.
 *
 * The call is enqueued, so no thread is held while the server answers, and
 * canceling the coroutine cancels the call. An `IOException` becomes
 * [PlexReply.Unreachable]; nothing here throws.
 *
 * Requests are not logged, because they carry the token in a header.
 */
class OkHttpPlex(
    private val device: PlexDevice,
    private val client: OkHttpClient = PlexOkHttp.shared,
) : PlexHttp {
    override suspend fun get(
        url: HttpUrl?,
        token: String,
        headers: Map<String, String>,
    ): PlexReply = sent(url, token, headers) { get() }

    override suspend fun post(
        url: HttpUrl?,
        token: String,
        body: JSONObject?,
    ): PlexReply = sent(url, token) { post((body?.toString() ?: "").toRequestBody(JSON)) }

    override suspend fun delete(
        url: HttpUrl?,
        token: String,
    ): PlexReply = sent(url, token) { delete() }

    private suspend fun sent(
        url: HttpUrl?,
        token: String,
        headers: Map<String, String> = emptyMap(),
        method: Request.Builder.() -> Request.Builder,
    ): PlexReply {
        if (url == null) return PlexReply.Unreachable("no server address")
        val request = Request.Builder().url(url)
        for ((name, value) in device.headers(token) + headers) request.header(name, value)
        return suspendCancellableCoroutine { waiting ->
            val call = client.newCall(request.method().build())
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        waiting.resume(PlexReply.Unreachable(e.message ?: e.javaClass.simpleName))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        waiting.resume(response.use(::reply))
                    }
                },
            )
        }
    }

    /** One response as a reply. A body that cannot be read to the end is [PlexReply.Unreachable]. */
    private fun reply(response: Response): PlexReply {
        if (!response.isSuccessful) return PlexReply.read(response.code, "")
        val body = runCatching { response.body.string() }.getOrNull()
        return body?.let { PlexReply.read(response.code, it) } ?: PlexReply.Unreachable("the answer stopped part way")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** The one HTTP client this source uses, with timeouts. */
object PlexOkHttp {
    val shared: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            // for a server name with both address families on a network that
            // routes only one: the other is tried without waiting for the
            // connect timeout
            .fastFallback(true)
            .build()
    }

    private const val TIMEOUT_S = 15L

    /** Long enough for a page of five hundred tracks with their media. */
    private const val CALL_TIMEOUT_S = 60L
}
