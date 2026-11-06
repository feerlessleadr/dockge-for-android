package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.core.InstanceColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceRepositoryTest {
    private val blue = InstanceColors.palette[0]
    private val green = InstanceColors.palette[1]

    private class Rig {
        val store = MemoryInstanceStore()
        val vault = MemoryVault()
        private var n = 0
        val repo get() = InstanceRepository(store, vault, newId = { "id${++n}" })
    }

    @Test fun addStoresCanonicalAddressAndPersists() {
        val r = Rig(); val repo = r.repo
        val i = repo.add("  Home  ", "Dockge.Example.com/", blue).getOrThrow()
        assertEquals("Home", i.name)
        assertEquals("https://dockge.example.com", i.baseUrl)
        assertEquals(listOf(i), r.store.saved)
        assertEquals(listOf(i), repo.instances.value)
    }

    @Test fun savedInstancesSurviveANewRepository() {
        val r = Rig()
        val first = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        val reopened = InstanceRepository(r.store, r.vault)
        assertEquals(listOf(first), reopened.instances.value)
    }

    @Test fun rejectsBadInput() {
        val repo = Rig().repo
        assertTrue(repo.add("", "https://a.example.com", blue).isFailure)
        assertTrue(repo.add("x".repeat(41), "https://a.example.com", blue).isFailure)
        assertTrue(repo.add("A", "http://a.example.com", blue).isFailure)
        assertTrue(repo.add("A", "https://a.example.com", 0x123456).isFailure)
        assertTrue(repo.instances.value.isEmpty())
    }

    @Test fun cleartextOnlyWhenAllowed() {
        val repo = InstanceRepository(MemoryInstanceStore(), MemoryVault(), allowCleartext = true)
        assertTrue(repo.add("A", "http://a.example.com", blue).isSuccess)
    }

    @Test fun renameKeepsTheTokenAndId() {
        val r = Rig(); val repo = r.repo
        val i = repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(i.id, "T")
        val u = repo.update(i.id, "B", "https://a.example.com", green).getOrThrow()
        assertEquals(i.id, u.id)
        assertEquals("B", u.name)
        assertEquals("T", r.vault.get(i.id))
    }

    @Test fun changingTheAddressDeletesTheToken() {
        val r = Rig(); val repo = r.repo
        val i = repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(i.id, "T")
        repo.update(i.id, "A", "https://other.example.com", blue).getOrThrow()
        assertNull(r.vault.get(i.id))
    }

    @Test fun failedUpdateChangesNothing() {
        val r = Rig(); val repo = r.repo
        val i = repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(i.id, "T")
        assertTrue(repo.update(i.id, "A", "not a url", blue).isFailure)
        assertEquals("T", r.vault.get(i.id))
        assertEquals(i, repo.get(i.id))
    }

    @Test fun updateOfMissingInstanceFails() {
        assertTrue(Rig().repo.update("nope", "A", "https://a.example.com", blue).isFailure)
    }

    @Test fun deleteRemovesInstanceAndToken() {
        val r = Rig(); val repo = r.repo
        val a = repo.add("A", "https://a.example.com", blue).getOrThrow()
        val b = repo.add("B", "https://b.example.com", green).getOrThrow()
        r.vault.put(a.id, "TA"); r.vault.put(b.id, "TB")
        repo.delete(a.id)
        assertEquals(listOf(b), repo.instances.value)
        assertEquals(listOf(b), r.store.saved)
        assertNull(r.vault.get(a.id))
        assertEquals("TB", r.vault.get(b.id))
    }
}
