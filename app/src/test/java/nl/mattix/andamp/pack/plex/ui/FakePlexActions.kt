// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import kotlinx.coroutines.CompletableDeferred
import nl.mattix.andamp.pack.plex.PinState
import nl.mattix.andamp.pack.plex.PlexPin

/**
 * Actions that record their calls and answer what the test sets. The answers
 * to a sign-in, a test and a choice are deferreds, so a test can hold the
 * page between the press and the answer. The code's state is a field the
 * test changes.
 */
internal class FakePlexActions(
    private var holds: Kept = Kept(user = "", serverName = "", address = "", libraryTitle = "", signedIn = false),
    /** The code a start answers; null for plex.tv that starts none. */
    var pin: PlexPin? = PlexPin(1234, "ABCD"),
    /** What the server lists when asked for its libraries. */
    var libraries: List<Choice> = emptyList(),
) : PlexActions {
    var state: PinState = PinState.Waiting
    var finished = CompletableDeferred<Tried>()
    var answer = CompletableDeferred<Tried>()
    var tested = CompletableDeferred<Tried>()
    var chosen = CompletableDeferred<Tried>()
    val polled = mutableListOf<Long>()
    val finishedWith = mutableListOf<String>()
    val signedIn = mutableListOf<List<String>>()
    val tests = mutableListOf<List<String>>()
    val picked = mutableListOf<String>()
    var keptTests = 0
    var signedOut = 0

    override fun kept(): Kept = holds

    override suspend fun startLink(): PlexPin? = pin

    override suspend fun linkState(pin: PlexPin): PinState {
        polled += pin.id
        return state
    }

    override suspend fun finishLink(token: String): Tried {
        finishedWith += token
        return finished.await()
    }

    override suspend fun choose(
        what: Pick,
        choice: Choice,
    ): Tried {
        picked += "${what.name.lowercase()}:${choice.id}"
        return chosen.await()
    }

    override suspend fun libraries(): List<Choice> = libraries

    override suspend fun testManual(
        address: String,
        token: String,
    ): Tried {
        tests += listOf(address, token)
        return tested.await()
    }

    override suspend fun signInManual(
        address: String,
        token: String,
    ): Tried {
        signedIn += listOf(address, token)
        return answer.await()
    }

    override suspend fun test(): Tried {
        keptTests++
        return tested.await()
    }

    /** Signs out: what is kept afterwards is the address alone, as the real store keeps it. */
    override suspend fun signOut() {
        signedOut++
        holds = holds.copy(user = "", serverName = "", libraryTitle = "", signedIn = false, manual = false)
    }
}
