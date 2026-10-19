package app.nearaudio

import app.nearaudio.session.RecipientState.*
import app.nearaudio.session.RecipientTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientTableTest {
    private fun table(max: Int = 2) = RecipientTable(max).also {
        it.peerSeen("a", "Alice")
        it.peerSeen("b", "Bob")
        it.peerSeen("c", "Carol")
    }

    @Test fun happyPath() {
        val t = table()
        assertEquals(DISCOVERED, t.get("a")!!.state)
        assertTrue(t.setSelected("a", true))
        assertTrue(t.beginConnect("a"))
        assertEquals(CONNECTING, t.get("a")!!.state)
        assertTrue(t.setVerification("a", "123 456"))
        assertTrue(t.connected("a"))
        assertTrue(t.receiving("a"))
        assertTrue(t.receiving("a")) // idempotent
        assertEquals(RECEIVING, t.get("a")!!.state)
        assertEquals(listOf("a"), t.connectedIds())
        assertTrue(t.disconnected("a", "left"))
        assertEquals(DISCONNECTED, t.get("a")!!.state)
        assertEquals("left", t.get("a")!!.detail)
        assertNull(t.get("a")!!.verificationCode)
    }

    @Test fun illegalTransitionsChangeNothing() {
        val t = table()
        assertFalse(t.connected("a"))
        assertFalse(t.receiving("a"))
        assertFalse(t.disconnected("a"))
        assertFalse(t.failed("a", "x"))
        assertFalse(t.setVerification("a", "1"))
        assertFalse(t.setSelected("a", false)) // not selected
        assertFalse(t.setSelected("zzz", true)) // unknown
        assertEquals(DISCOVERED, t.get("a")!!.state)
        t.setSelected("a", true)
        t.beginConnect("a")
        t.connected("a")
        assertFalse(t.beginConnect("a")) // already live
        assertFalse(t.setSelected("a", true) && t.get("a")!!.state == SELECTED)
    }

    @Test fun maxRecipientsEnforced() {
        val t = table(2)
        assertTrue(t.setSelected("a", true))
        assertTrue(t.setSelected("b", true))
        assertFalse(t.setSelected("c", true))
        assertTrue(t.setSelected("a", false))
        assertTrue(t.setSelected("c", true))
    }

    @Test fun oneFailureDoesNotAffectOthers() {
        val t = table()
        for (id in listOf("a", "b")) { t.setSelected(id, true); t.beginConnect(id) }
        t.connected("b")
        assertTrue(t.failed("a", "timeout"))
        assertEquals(FAILED, t.get("a")!!.state)
        assertEquals("timeout", t.get("a")!!.detail)
        assertEquals(CONNECTED, t.get("b")!!.state)
        // failed peer may retry
        assertTrue(t.beginConnect("a"))
    }

    @Test fun peerLostKeepsLiveConnectionsOnly() {
        val t = table()
        t.setSelected("a", true); t.beginConnect("a"); t.connected("a")
        t.peerLost("a")
        t.peerLost("b")
        assertEquals(CONNECTED, t.get("a")!!.state)
        assertNull(t.get("b"))
    }

    @Test fun endAllAndDropUnconnected() {
        val t = table(3)
        t.setSelected("a", true); t.beginConnect("a"); t.connected("a"); t.receiving("a")
        t.setSelected("b", true); t.beginConnect("b")
        t.endAll()
        assertEquals(DISCONNECTED, t.get("a")!!.state)
        assertEquals(DISCONNECTED, t.get("b")!!.state)
        assertEquals(DISCOVERED, t.get("c")!!.state)
        t.dropUnconnected()
        assertTrue(t.snapshot().isEmpty())
    }

    @Test fun duplicateAliasesKeepSeparateEntries() {
        val t = RecipientTable()
        t.peerSeen("1111", "Phone")
        t.peerSeen("2222", "Phone")
        t.peerSeen("1111", "Renamed")
        assertEquals(2, t.snapshot().size)
        assertEquals("Renamed", t.get("1111")!!.alias)
    }

    @Test fun changesFlowReflectsState() {
        val t = table()
        t.setSelected("a", true)
        assertEquals(SELECTED, t.changes.value.first { it.peerId == "a" }.state)
    }
}
