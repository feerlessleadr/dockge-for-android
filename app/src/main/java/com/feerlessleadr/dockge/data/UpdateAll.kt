package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.api.SelfUpdatePolicy
import com.feerlessleadr.dockge.api.Stack

/** One stack to update in an "update all" run. [endpoint] is "" for the instance itself. */
data class UpdateTarget(val endpoint: String, val stackName: String, val title: String)

data class UpdateAllResult(val target: UpdateTarget, val outcome: ActionOutcome)

/**
 * Progress of one "update all" run on one instance. [current] is the stack being updated now; [results] holds every
 * finished stack in order. [stoppedBecause] is set when the run ended early (the connection was lost).
 */
data class UpdateAllState(
    val total: Int,
    val current: UpdateTarget?,
    val results: List<UpdateAllResult>,
    val running: Boolean,
    val stoppedBecause: String? = null,
) {
    val done: Int get() = results.size
    val failed: Int get() = results.count { it.outcome is ActionOutcome.Failed }
}

object UpdateAll {
    /** The Dockge image the app falls back to when the self-update status is not known yet. */
    const val DEFAULT_DOCKGE_REPO = "aerya/dockge-enhanced"

    /**
     * The stacks "update all" touches: managed stacks with an update the server found, on servers that are online.
     * Dockge's own stacks are always left out, on every server, because updating them is the safe self-update only.
     */
    fun plan(servers: List<ServerStacks>, updates: Map<String, ServerUpdates>, selfRepo: String?): List<UpdateTarget> {
        val repo = selfRepo?.takeIf { it.isNotBlank() } ?: DEFAULT_DOCKGE_REPO
        return servers.filter { it.status != "offline" }.flatMap { server ->
            val serverUpdates = updates[server.endpoint]
            server.stacks.filter { stack -> isUpdatable(stack, serverUpdates, repo) }
                .map { UpdateTarget(server.endpoint, it.name, it.title) }
        }
    }

    private fun isUpdatable(stack: Stack, serverUpdates: ServerUpdates?, repo: String): Boolean {
        if (!stack.isManaged) return false
        if (serverUpdates == null || serverUpdates.availableFor(stack.name).isEmpty()) return false
        val dockge = serverUpdates.forStack(stack.name).any { SelfUpdatePolicy.isDockgeImage(it.image, repo) }
        return !dockge
    }

    /**
     * Runs [runOne] for each target, one after another, and reports every change through [onChange]. A stack that
     * fails does not stop the run. The run stops early when [isConnected] turns false, because the rest would fail too.
     */
    suspend fun run(
        targets: List<UpdateTarget>,
        isConnected: () -> Boolean,
        runOne: suspend (UpdateTarget) -> ActionOutcome,
        onChange: (UpdateAllState) -> Unit,
    ): UpdateAllState {
        val results = mutableListOf<UpdateAllResult>()
        var state = UpdateAllState(total = targets.size, current = null, results = emptyList(), running = true)
        onChange(state)
        var stopped: String? = null
        for ((index, target) in targets.withIndex()) {
            if (!isConnected()) {
                stopped = "The connection was lost. ${targets.size - index} stack(s) were not updated: check them, then run update all again."
                break
            }
            state = state.copy(current = target)
            onChange(state)
            results += UpdateAllResult(target, runOne(target))
            state = state.copy(current = null, results = results.toList())
            onChange(state)
        }
        state = state.copy(running = false, current = null, stoppedBecause = stopped)
        onChange(state)
        return state
    }
}
