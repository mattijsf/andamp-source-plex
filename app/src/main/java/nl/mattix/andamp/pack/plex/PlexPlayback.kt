// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.common.stream.StreamRequest
import java.util.UUID

/**
 * Builds the playback backend for a Plex server.
 *
 * Playback is the SDK's [StreamPlayback]. This object supplies where a row's
 * audio is: the file itself or the transcoder, with the token in a header;
 * see [PlexStream].
 */
internal object PlexPlayback {
    /**
     * A backend over [server]. [server] is called each time a row is opened,
     * so a token or an address replaced since is used from the next open.
     * Each open gets a transcode session of its own.
     */
    fun backend(
        server: () -> PlexServer?,
        tracks: List<Track>,
        startIndex: Int,
        scope: CoroutineScope,
        out: AudioOut?,
        network: NetworkWatch,
    ): PlaybackBackend =
        StreamPlayback.backend(
            tracks = tracks,
            startIndex = startIndex,
            scope = scope,
            out = out,
            network = network,
            locate = { track, positionMs -> request(server(), track, positionMs, UUID.randomUUID().toString()) },
        )

    /**
     * Where one row's audio is. Null when nobody is signed in or when the row's
     * address is not one of this source's.
     */
    fun request(
        server: PlexServer?,
        track: Track,
        positionMs: Long = 0,
        session: String = "",
    ): StreamRequest? {
        val signedIn = server ?: return null
        val address = track.uri ?: return null
        return PlexStream.request(signedIn, address, track.defaultName, positionMs, session)
    }
}
