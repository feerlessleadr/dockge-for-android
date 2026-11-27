package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.SelfUpdateStatus

/**
 * What the app knows about the Dockge-Enhanced update of one instance. [status] is the last answer; it is kept while
 * Dockge is down (during its own update), so the card can show the progress of the run.
 */
data class SelfUpdateState(
    val status: SelfUpdateStatus? = null,
    /** False when the last status request failed, for example while Dockge restarts. */
    val reachable: Boolean = false,
    /** True from the moment the app asked for an update until the server reports its end (or the watch gives up). */
    val watching: Boolean = false,
    /** Why the last request or start was refused or failed, shown on the card. */
    val error: String? = null,
)
