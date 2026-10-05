// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import nl.mattix.andamp.pack.common.stream.StreamRequest

/**
 * Builds the request for a track's audio. It is built when a row is about to
 * play and is not kept on the row; see [PlexRow.track].
 *
 * The token goes in the `X-Plex-Token` header and not in the URL, so it does
 * not end up where URLs are written down, such as a proxy's access log. The
 * decoder sends the headers through `MediaExtractor.setDataSource(url, headers)`.
 */
object PlexStream {
    /**
     * The request for an address this source handed out. Null when the
     * address is not one of its own, the server address is not a URL, or
     * nobody is signed in.
     *
     * When [located] is a part key (see [PlexRow.located]) the file is asked
     * for as it is, and can be sought in by byte range. Otherwise the
     * transcoder sends it as mp3 from [positionMs], and the request is marked
     * not seekable; see [StreamRequest.seekable]. [session] names the
     * transcode and differs per request, because the server ends an older
     * transcode that reuses a session.
     */
    fun request(
        server: PlexServer,
        address: String,
        located: String?,
        positionMs: Long = 0,
        session: String = "",
    ): StreamRequest? {
        val id = PlexRow.trackId(address) ?: return null
        if (!server.signedIn) return null
        val direct = located?.takeIf(::isPartKey)
        val url =
            if (direct != null) {
                server.url(direct.trimStart('/'))
            } else {
                server.url(Plex.TRANSCODE, transcodeParams(id, positionMs, session))
            }
        return url?.let { StreamRequest(it.toString(), server.headers(), seekable = direct != null) }
    }

    /** Whether [located] is a part's key, which starts with the parts path. */
    fun isPartKey(located: String): Boolean = located.startsWith(PARTS)

    /**
     * The transcoder's parameters: the track by its metadata path, its first
     * media and part, mp3 over plain http with no direct play, and the
     * `offset` in seconds when the audio starts part way.
     */
    fun transcodeParams(
        id: String,
        positionMs: Long,
        session: String,
    ): Map<String, String> =
        buildMap {
            put("path", "/library/metadata/$id")
            put("mediaIndex", "0")
            put("partIndex", "0")
            put("protocol", "http")
            put("directPlay", "0")
            put("directStream", "0")
            put("audioCodec", TRANSCODE_CODEC)
            if (positionMs > 0) put("offset", (positionMs / MS_PER_S).toString())
            if (session.isNotEmpty()) put("session", session)
        }

    /**
     * Whether this phone plays a file in [container] holding [codec] as it is:
     * mp3, AAC in an mp4 or m4a, Vorbis or Opus in ogg, PCM in wav, and FLAC
     * from API 27. Below that the platform's extractor does not read FLAC
     * over http reliably, so the server is left to transcode it. An m4a that
     * holds ALAC transcodes, which is why the codec is checked and not the
     * container alone.
     */
    fun playsDirectly(
        container: String,
        codec: String,
        sdk: Int,
    ): Boolean {
        val held = container.lowercase()
        val coded = codec.lowercase()
        return when {
            held.isEmpty() || coded.isEmpty() -> false
            held == FLAC || coded == FLAC -> held == FLAC && coded == FLAC && sdk >= FLAC_OVER_HTTP_SDK
            held == "wav" -> coded.startsWith("pcm")
            else -> "$held/$coded" in DIRECT
        }
    }

    private val DIRECT = setOf("mp3/mp3", "mp4/aac", "m4a/aac", "aac/aac", "ogg/vorbis", "ogg/opus", "opus/opus")

    private const val FLAC = "flac"

    /** The first API level whose `MediaExtractor` reads FLAC over http reliably. */
    private const val FLAC_OVER_HTTP_SDK = 27

    /** Where every part's key starts. */
    private const val PARTS = "/library/parts/"

    /** The codec asked for when the server transcodes. */
    private const val TRANSCODE_CODEC = "mp3"

    private const val MS_PER_S = 1_000L
}
