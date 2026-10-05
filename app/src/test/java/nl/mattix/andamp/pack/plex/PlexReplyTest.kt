// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [PlexReply.read], and the requests [OkHttpPlex] sends. The client tests
 * run [OkHttpPlex] over an OkHttp client whose interceptor answers in place
 * of the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexReplyTest {
    @Test
    fun `a 401 is its own answer, whatever page is in the body`() {
        assertEquals(PlexReply.Unauthorized, PlexReply.read(401, fixture("unauthorized.html")))
    }

    @Test
    fun `any other failing status is rejected and its body is never read`() {
        assertEquals(PlexReply.Rejected(404), PlexReply.read(404, fixture("unauthorized.html")))
        assertEquals(PlexReply.Rejected(500), PlexReply.read(500, "{\"MediaContainer\":{}}"))
    }

    @Test
    fun `a success that is not JSON is not the server talking`() {
        // what a captive portal answers for every URL
        assertTrue(PlexReply.read(200, fixture("unauthorized.html")) is PlexReply.Unreachable)
        // what a server answers when the Accept header did not reach it
        assertTrue(PlexReply.read(200, fixture("identity.xml")) is PlexReply.Unreachable)
    }

    @Test
    fun `an array is an answer, as plex tv lists the account's devices`() {
        val reply = recorded("resources.json")

        assertTrue((reply as PlexReply.Answered).body is JSONArray)
        assertNull("an array has no object", reply.obj)
    }

    @Test
    fun `a 201 is a success, as a new code is answered`() {
        assertTrue(PlexReply.read(201, fixture("pins-new.json")) is PlexReply.Answered)
    }

    @Test
    fun `a 204 has nothing to say and still succeeded`() {
        val reply = PlexReply.read(204, "")

        assertTrue(reply is PlexReply.Answered)
        assertEquals(0, checkNotNull(reply.obj).length())
    }

    @Test
    fun `every request carries this phone's headers, the token among them and nowhere else`() =
        runTest {
            val sent = mutableListOf<Request>()
            val http = OkHttpPlex(DEVICE, client(sent) { respond(it, 200, fixture("artists.json")) })

            val reply =
                http.get(
                    SERVER.url(Plex.sectionAll(MUSIC), mapOf("type" to "8")),
                    SERVER_TOKEN,
                    mapOf("X-Plex-Container-Size" to "3"),
                )

            assertTrue(reply is PlexReply.Answered)
            val request = sent.single()
            assertEquals("GET", request.method)
            assertEquals("/library/sections/1/all", request.url.encodedPath)
            assertEquals("8", request.url.queryParameter("type"))
            assertEquals(SERVER_TOKEN, request.header("X-Plex-Token"))
            assertEquals(DEVICE.id, request.header("X-Plex-Client-Identifier"))
            assertEquals("Andamp", request.header("X-Plex-Product"))
            assertEquals("0.1.0", request.header("X-Plex-Version"))
            assertEquals("Android", request.header("X-Plex-Platform"))
            assertEquals("Pixel 9", request.header("X-Plex-Device"))
            assertEquals("Pixel 9", request.header("X-Plex-Device-Name"))
            assertEquals("application/json", request.header("Accept"))
            assertEquals("3", request.header("X-Plex-Container-Size"))
            assertFalse(request.url.toString(), request.url.toString().contains(SERVER_TOKEN))
        }

    @Test
    fun `before a sign-in no token header is sent`() =
        runTest {
            val sent = mutableListOf<Request>()
            val http = OkHttpPlex(DEVICE, client(sent) { respond(it, 201, fixture("pins-new.json")) })

            http.post(Plex.tv(Plex.PINS, mapOf("strong" to "false")))

            val request = sent.single()
            assertEquals("POST", request.method)
            assertEquals("plex.tv", request.url.host)
            assertNull(request.header("X-Plex-Token"))
            assertEquals("false", request.url.queryParameter("strong"))
        }

    @Test
    fun `a sign-out is a DELETE with the token`() =
        runTest {
            val sent = mutableListOf<Request>()
            val http = OkHttpPlex(DEVICE, client(sent) { respond(it, 204, "") })

            assertTrue(http.delete(Plex.tv(Plex.SIGN_OUT), RECORDED_TOKEN) is PlexReply.Answered)
            assertEquals("DELETE", sent.single().method)
            assertEquals(RECORDED_TOKEN, sent.single().header("X-Plex-Token"))
        }

    @Test
    fun `the real client reads a 401 off the status`() =
        runTest {
            val http = OkHttpPlex(DEVICE, client(mutableListOf()) { respond(it, 401, fixture("unauthorized.html")) })

            assertEquals(PlexReply.Unauthorized, http.get(SERVER.url(Plex.SECTIONS), SERVER_TOKEN))
        }

    @Test
    fun `a network that is not there is an answer and not an exception`() =
        runTest {
            val http = OkHttpPlex(DEVICE, client(mutableListOf()) { throw IOException("no route to host") })

            assertEquals(PlexReply.Unreachable("no route to host"), http.get(SERVER.url(Plex.SECTIONS), SERVER_TOKEN))
        }

    @Test
    fun `no URL is unreachable without a call`() =
        runTest {
            val sent = mutableListOf<Request>()
            val http = OkHttpPlex(DEVICE, client(sent) { respond(it, 200, "{}") })

            assertTrue(http.get(PlexServer("", DEVICE).url(Plex.SECTIONS)) is PlexReply.Unreachable)
            assertEquals(emptyList<Request>(), sent)
        }

    @Test
    fun `a device name that is not ASCII cannot break a header`() {
        val headers = PlexDevice("Mattijs’s Pixel", "id", "0.1.0").headers()

        assertEquals("Mattijs?s Pixel", headers["X-Plex-Device"])
        assertEquals(JSONObject().length(), 0)
    }

    /** An OkHttp client that answers with [answer] and adds every request to [sent]. */
    private fun client(
        sent: MutableList<Request>,
        answer: (Request) -> Response,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    answer(chain.request())
                },
            ).build()

    private fun respond(
        request: Request,
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("recorded")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
