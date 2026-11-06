package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.core.InstanceColors
import com.feerlessleadr.dockge.core.InstanceStore
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The saved instances. Every change is written to disk before it returns. */
class InstanceRepository(
    private val store: InstanceStore,
    private val vault: TokenVault,
    private val allowCleartext: Boolean = false,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _instances = MutableStateFlow(store.load())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    fun get(id: String): Instance? = _instances.value.firstOrNull { it.id == id }

    fun add(name: String, address: String, color: Long): Result<Instance> {
        val checked = validate(name, address, color).getOrElse { return Result.failure(it) }
        val instance = Instance(newId(), checked.name, color, checked.url.value)
        persist(_instances.value + instance)
        return Result.success(instance)
    }

    fun update(id: String, name: String, address: String, color: Long): Result<Instance> {
        val old = get(id) ?: return Result.failure(IllegalArgumentException("That instance no longer exists."))
        val checked = validate(name, address, color).getOrElse { return Result.failure(it) }
        val updated = old.copy(name = checked.name, color = color, baseUrl = checked.url.value)
        // A token must never be sent to a different server than the one that issued it.
        if (updated.baseUrl != old.baseUrl) vault.remove(id)
        persist(_instances.value.map { if (it.id == id) updated else it })
        return Result.success(updated)
    }

    fun delete(id: String) {
        vault.remove(id)
        persist(_instances.value.filterNot { it.id == id })
    }

    private class Checked(val name: String, val url: BaseUrl)

    private fun validate(name: String, address: String, color: Long): Result<Checked> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fail("Enter a name.")
        if (trimmed.length > MAX_NAME) return fail("The name can have at most $MAX_NAME characters.")
        if (color !in InstanceColors.palette) return fail("Pick one of the colors.")
        val url = BaseUrl.parse(address, allowCleartext).getOrElse { return Result.failure(it) }
        return Result.success(Checked(trimmed, url))
    }

    private fun fail(message: String): Result<Checked> = Result.failure(IllegalArgumentException(message))

    private fun persist(list: List<Instance>) {
        store.save(list)
        _instances.value = list
    }

    companion object {
        const val MAX_NAME = 40
    }
}
