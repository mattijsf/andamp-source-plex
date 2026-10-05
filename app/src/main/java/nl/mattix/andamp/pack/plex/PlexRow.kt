// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import nl.mattix.andamp.core.packapi.PackTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Plex track into a [PackTrack].
 *
 * An album's tracks, a playlist's entries and a search all answer the same
 * item object in a `MediaContainer`'s `Metadata` array. Every field except
 * the `ratingKey` is read as optional.
 */
object PlexRow {
    /**
     * This source's scheme, the first word of every address it hands out. The
     * player routes a row by it and saved playlists record it, so it cannot
     * change after a release.
     */
    const val SCHEME = "plex"

    /** A track's `ratingKey` as an address, `plex:track:<ratingKey>`. */
    fun address(id: String): String = TRACK + id

    /** The `ratingKey` inside an address, or null when the address is not one of this source's. */
    fun trackId(address: String): String? = address.removePrefix(TRACK).takeIf { it != address && it.isNotEmpty() }

    /**
     * One item as a row, or null when it has no `ratingKey` or its `type` is
     * not `track`. The type is checked because a playlist can hold video.
     *
     * [PackTrack.uri] is the address and not a stream URL, because the player
     * writes it into saved playlists. [PackTrack.defaultName] carries where
     * the audio is; see [located]. [PlexStream] builds the request when the
     * row is played.
     */
    fun track(
        item: JSONObject,
        server: PlexServer,
        sdk: Int,
    ): PackTrack? {
        val id = item.text("ratingKey").takeIf { it.isNotEmpty() } ?: return null
        val type = item.text("type")
        if (type.isNotEmpty() && type != TRACK_TYPE) return null
        val address = address(id)
        return PackTrack(
            id = address,
            artist = artist(item),
            title = item.text("title"),
            durationMs = item.optLong("duration"),
            uri = address,
            // 0 when the server gives no rate
            bitrateKbps = media(item)?.optInt("bitrate") ?: 0,
            // not in a track listing
            sampleRateKhz = 0,
            isStream = false,
            artworkUri = artwork(item, server),
            defaultName = located(item, sdk),
        )
    }

    /** Every item in an array that reads as a track; other entries are dropped. */
    fun tracks(
        items: JSONArray?,
        server: PlexServer,
        sdk: Int,
    ): List<PackTrack> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { at -> items.optJSONObject(at)?.let { track(it, server, sdk) } }
    }

    /** The track's own artist (`originalTitle`) when it has one, else the album artist (`grandparentTitle`). */
    fun artist(item: JSONObject): String = item.text("originalTitle").ifEmpty { item.text("grandparentTitle") }

    /**
     * The URL of this track's cover: the track's own picture when it has one,
     * otherwise the album's, otherwise the artist's. Null when there is none.
     *
     * The server sends a picture only with a token, and the player fetches
     * covers in its own process from the URL alone, so the token goes on the
     * query. The player writes this URL into saved playlists.
     */
    fun artwork(
        item: JSONObject,
        server: PlexServer,
    ): String? {
        val path = PICTURES.map(item::text).firstOrNull { it.isNotEmpty() } ?: return null
        return server.url(path.trimStart('/'), mapOf(Plex.TOKEN to server.token))?.toString()
    }

    /**
     * Where the audio is, for [PackTrack.defaultName]: the part's key, such
     * as `/library/parts/123/1700000000/file.flac`, when this phone plays the
     * file as it is (see [PlexStream.playsDirectly]), otherwise the file's
     * name, which does not start with a slash. [PlexStream] tells the two
     * apart. Null when the item lists no media.
     */
    fun located(
        item: JSONObject,
        sdk: Int,
    ): String? {
        val media = media(item) ?: return null
        val part = media.objects("Part").firstOrNull()
        val key = part?.text("key").orEmpty()
        val direct = PlexStream.playsDirectly(media.text("container"), media.text("audioCodec"), sdk)
        if (direct && PlexStream.isPartKey(key)) return key
        val file = part?.text("file").orEmpty().substringAfterLast('/')
        return file.ifEmpty { key.substringAfterLast('/') }.takeIf { it.isNotEmpty() }
    }

    /** The first media of an item, which has the codec, the container and the parts. */
    private fun media(item: JSONObject): JSONObject? = item.objects("Media").firstOrNull()

    private const val TRACK = "$SCHEME:track:"
    private const val TRACK_TYPE = "track"

    /** The picture fields, the track's own first. */
    private val PICTURES = listOf("thumb", "parentThumb", "grandparentThumb")
}
