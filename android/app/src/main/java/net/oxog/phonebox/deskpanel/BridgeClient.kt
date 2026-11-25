package net.oxog.phonebox.deskpanel

/** Talks to the agent's /api/bridge endpoints over whichever route is up (USB, or Wi-Fi after pairing). */
class BridgeClient(private val link: AgentLink) {
    /** Sends one notification (the JSON from [BridgeClip.notificationBody]). Never throws: a failure is [SendResult.UNREACHABLE]. */
    internal fun sendNotification(target: AgentLink.NotificationTarget, body: String): SendResult = try {
        val reply = link.requestNotification(target, body.toByteArray(Charsets.UTF_8))
        try { reply.body.close() } catch (_: Exception) {} // the agent already answered
        BridgeClip.resultFor(reply.code)
    } catch (_: Exception) {
        SendResult.UNREACHABLE
    }

    /** The agent's public config as text, or null when it cannot be reached. */
    internal fun fetchConfig(target: AgentLink.NotificationTarget): String? = try {
        val reply = link.requestNotification(target, null)
        if (reply.code != 200) { reply.body.close(); null } else reply.body.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) {
        null
    }

    /** Sends [text] to the computer's clipboard. Never throws: a failure is [SendResult.UNREACHABLE]. */
    fun sendClipboard(text: String): SendResult = try {
        val reply = link.request("POST", "/api/bridge/clipboard", BridgeClip.requestBody(text).toByteArray(Charsets.UTF_8), "application/json")
        try { reply.body.close() } catch (_: Exception) {} // a failed close is not a failed send: the agent already answered
        BridgeClip.resultFor(reply.code)
    } catch (_: Exception) {
        SendResult.UNREACHABLE
    }
}
