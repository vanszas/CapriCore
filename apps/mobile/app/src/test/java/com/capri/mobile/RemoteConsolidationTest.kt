package com.capri.mobile

import com.capri.mobile.remote.PairingPayload
import com.capri.mobile.remote.PcProfile
import com.capri.mobile.remote.QuotaWindow
import com.capri.mobile.remote.RemoteStreamEvent
import com.capri.mobile.remote.parseStreamLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class RemoteConsolidationTest {
    @Test
    fun pairingPayloadParsesValidUriAndRejectsInvalid() {
        val json = """{"v":1,"endpoint":"http://100.94.231.68:9120","token":"tok_synthetic_0123456789","name":"Capri-Host"}"""
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val parsed = PairingPayload.parse("agentremote://pair?data=$b64")
        assertNotNull(parsed)
        assertEquals("http://100.94.231.68:9120", parsed?.endpoint)
        assertEquals("tok_synthetic_0123456789", parsed?.token)
        assertEquals("Capri-Host", parsed?.name)

        // Reject short token
        val shortTok = """{"endpoint":"http://127.0.0.1:9120","token":"abc"}"""
        val shortB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(shortTok.toByteArray(StandardCharsets.UTF_8))
        assertNull(PairingPayload.parse("agentremote://pair?data=$shortB64"))

        // Reject wrong scheme
        assertNull(PairingPayload.parse("https://example.com/pair?data=$b64"))
    }

    @Test
    fun truthfulQuotaMathDoesNotFallbackTo45Or100WhenUnused() {
        val unused = QuotaWindow("w1", "9Router Antigravity", null, null, "")
        assertEquals(0.0, unused.effectiveUsedPercent(limitReached = false), 0.001)
        assertEquals(100.0, unused.effectiveUsedPercent(limitReached = true), 0.001)

        val zeroUsed = QuotaWindow("w2", "Claude", 0.0, 100.0, "")
        assertEquals(0.0, zeroUsed.effectiveUsedPercent(limitReached = false), 0.001)

        val partialRemaining = QuotaWindow("w3", "Codex", null, 62.5, "")
        assertEquals(37.5, partialRemaining.effectiveUsedPercent(limitReached = false), 0.001)

        val explicitUsed = QuotaWindow("w4", "Gemini", 42.5, 57.5, "")
        assertEquals(42.5, explicitUsed.effectiveUsedPercent(limitReached = false), 0.001)

        assertEquals(100.0, unused.effectiveRemainingPercent(limitReached = false), 0.001)
        assertEquals(0.0, unused.effectiveRemainingPercent(limitReached = true), 0.001)
        assertEquals(62.5, partialRemaining.effectiveRemainingPercent(limitReached = false), 0.001)
        assertEquals(57.5, explicitUsed.effectiveRemainingPercent(limitReached = false), 0.001)
    }

    @Test
    fun tailscaleNormalizationAndFailoverOrderWork() {
        assertEquals("http://100.94.231.68:9120", PairingPayload.normalizeEndpointInput("100.94.231.68"))
        assertEquals("http://192.168.1.10:9120", PairingPayload.normalizeEndpointInput("http://192.168.1.10:9120/"))
        assertEquals("http://capri-pc.tailnet.ts.net:9120", PairingPayload.normalizeEndpointInput("capri-pc.tailnet.ts.net"))

        assertTrue(PairingPayload.isTailscaleHost("http://100.94.231.68:9120"))
        assertTrue(PairingPayload.isTailscaleHost("http://capri-pc.tailnet.ts.net:9120"))
        assertFalse(PairingPayload.isTailscaleHost("http://192.168.1.10:9120"))

        val lanPreferred = PcProfile(
            id = "pc1",
            name = "Workstation",
            localEndpoint = "http://192.168.1.10:9120",
            tailscaleEndpoint = "http://100.94.231.68:9120",
            preferTailscale = false,
        )
        assertEquals(
            listOf(false to "http://192.168.1.10:9120", true to "http://100.94.231.68:9120"),
            lanPreferred.candidateEndpoints(),
        )

        val tsPreferred = lanPreferred.copy(preferTailscale = true)
        assertEquals(
            listOf(true to "http://100.94.231.68:9120", false to "http://192.168.1.10:9120"),
            tsPreferred.candidateEndpoints(),
        )
    }

    @Test
    fun streamLineDecodesAgentNdjsonEvents() {
        val delta = parseStreamLine("""{"type":"delta","agent_id":"codex","text":"Hello"}""")
        assertTrue(delta is RemoteStreamEvent.Delta)
        assertEquals("codex", (delta as RemoteStreamEvent.Delta).agentId)
        assertEquals("Hello", delta.text)

        val tool = parseStreamLine("""{"type":"tool_started","tool_id":"t1","agent_id":"claude","name":"grep","text":"searching"}""")
        assertTrue(tool is RemoteStreamEvent.ToolStarted)
        assertEquals("grep", (tool as RemoteStreamEvent.ToolStarted).name)

        val approval = parseStreamLine("""{"type":"approval_request","request_id":"r1","title":"Run shell","text":"git status"}""")
        assertTrue(approval is RemoteStreamEvent.ApprovalRequested)
        assertEquals("r1", (approval as RemoteStreamEvent.ApprovalRequested).requestId)

        val done = parseStreamLine("""{"type":"completed","ok":true}""")
        assertTrue(done is RemoteStreamEvent.Completed)
        assertTrue((done as RemoteStreamEvent.Completed).ok)
    }

    @Test
    fun updatesNewerStillComparesSemverCorrectly() {
        assertTrue(Updates.newer("v0.5.0", "0.4.5-beta"))
        assertFalse(Updates.newer("0.5.0", "0.5.0"))
    }

    @Test
    fun phoneControlParsesDirectCommandsAndActionTags() {
        assertTrue(PhoneControl.parseDirectCommand("kembali") is PhoneControl.DirectCmd.Back)
        assertTrue(PhoneControl.parseDirectCommand("scroll ke bawah") is PhoneControl.DirectCmd.ScrollDown)
        val open = PhoneControl.parseDirectCommand("buka WhatsApp") as PhoneControl.DirectCmd.OpenApp
        assertEquals("WhatsApp", open.query)
        val click = PhoneControl.parseDirectCommand("klik Kirim") as PhoneControl.DirectCmd.Click
        assertEquals("Kirim", click.target)

        val tags = PhoneControl.extractActions("Siap, aku buka sekarang. [ACTION: open_app=YouTube] [ACTION: scroll_down]")
        assertEquals(2, tags.size)
        assertEquals("open_app", tags[0].name)
        assertEquals("YouTube", tags[0].arg)
        assertEquals("scroll_down", tags[1].name)

        assertEquals("Siap, aku buka sekarang.", Speaker.cleanForSpeech("Siap, aku buka sekarang. [ACTION: open_app=YouTube]"))
    }
}
