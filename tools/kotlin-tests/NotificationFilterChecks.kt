package net.oxog.phonebox.deskpanel

import java.util.concurrent.atomic.AtomicInteger

// JVM checks for the phone's notification rules: NotificationFilter.kt, DeliveryQueue.kt, BridgeConfigCache.kt and the
// nested JSON reader in BridgeClip.kt. Run with: python3 tools/test-bridge-checks.py
//
// The generic call checks use the documented Android model (a CallStyle notification of type "incoming", or an answer
// action); the Samsung in-call UI checks use what was recorded on a Galaxy S25 Ultra. Other makers' dialers are not recorded.

private var passed = 0
private fun check(name: String, ok: Boolean) {
    if (!ok) throw AssertionError("FAIL: $name")
    passed++
    println("PASS: $name")
}

private const val OWN = "net.oxog.phonebox.deskpanel"

private fun info(
    pkg: String = "com.msg", key: String = "k", ongoing: Boolean = false, groupSummary: Boolean = false,
    category: String? = null, callStyleIncoming: Boolean = false, hasAnswerAction: Boolean = false,
    title: String = "Anna", text: String = "hi", callStyle: Boolean = false,
) = NotificationInfo(pkg, key, ongoing, groupSummary, category, callStyleIncoming, hasAnswerAction, title, text, 1L, callStyle)

private fun filterChecks() {
    val allowed = mutableSetOf("com.msg", "com.dialer", OWN)
    val f = NotificationFilter({ allowed }, OWN)
    check("a plain message from an allowed app is sent", f.decide(info()) == Decision.SEND)
    check("an app that is not on the list is skipped", f.decide(info(pkg = "com.shop")) == Decision.SKIP)
    check("PhoneBox's own notifications are skipped even if listed", f.decide(info(pkg = OWN)) == Decision.SKIP)
    check("an ongoing notification (media, download) is skipped", f.decide(info(ongoing = true)) == Decision.SKIP)
    check("a group summary is skipped", f.decide(info(groupSummary = true)) == Decision.SKIP)
    check("a notification with no title and no text is skipped", f.decide(info(title = " ", text = "")) == Decision.SKIP)
    check("a title alone is enough", f.decide(info(title = "Anna", text = "")) == Decision.SEND)
    check("a text alone is enough", f.decide(info(title = "", text = "hello")) == Decision.SEND)

    check("an incoming call is sent although dialers post it as ongoing",
        f.decide(info(pkg = "com.dialer", category = "call", ongoing = true, callStyleIncoming = true, title = "Maria", text = "")) == Decision.SEND)
    check("a call that offers an answer action is sent",
        f.decide(info(pkg = "com.dialer", category = "call", ongoing = true, hasAnswerAction = true)) == Decision.SEND)
    check("a call already in progress (no incoming style, no answer action) is skipped",
        f.decide(info(pkg = "com.dialer", category = "call", ongoing = true)) == Decision.SKIP)
    check("a call from a dialer that is not on the list is skipped",
        f.decide(info(pkg = "com.otherdialer", category = "call", callStyleIncoming = true)) == Decision.SKIP)
    check("a missed call is sent as a missed call",
        f.decide(info(pkg = "com.dialer", category = "missed_call", title = "Maria", text = "Missed call")) == Decision.SEND_AS_MISSED_CALL)
    check("a missed call from an unlisted app is skipped", f.decide(info(pkg = "com.x", category = "missed_call")) == Decision.SKIP)

    // Recorded on a Galaxy S25 Ultra: the ringing call comes from com.samsung.android.incallui as an ongoing CallStyle notification
    // (type 2) with only a Hang up button; the default dialer is com.samsung.android.dialer.
    var dialer: String? = "com.dialer"
    val samsung = NotificationFilter({ allowed }, OWN, { dialer })
    val ring = info(pkg = "com.samsung.android.incallui", category = "call", ongoing = true, callStyle = true, title = "Maria", text = "")
    val voip = info(pkg = "com.whatsapp", category = "call", ongoing = true, callStyle = true, title = "Maria", text = "")
    check("another app's CallStyle call is NOT let through on the dialer's say", samsung.decide(voip) == Decision.SKIP)
    check("not even when it is of type incoming, with the dialer listed and the app not", samsung.decide(voip.copy(callStyleIncoming = true)) == Decision.SKIP)
    check("an in-call UI call without CallStyle follows the list, which the in-call UI is not on", samsung.decide(ring.copy(callStyle = false)) == Decision.SKIP)
    allowed.add("com.whatsapp")
    check("a listed app's incoming CallStyle call follows the list rule", samsung.decide(voip.copy(callStyleIncoming = true)) == Decision.SEND)
    check("a listed app's ongoing call (answered or placed by the owner) is not sent", samsung.decide(voip) == Decision.SKIP)
    allowed.remove("com.whatsapp")
    check("Samsung's in-call UI call is sent when the default dialer is on the list", samsung.decide(ring) == Decision.SEND)
    check("it is also sent once it says Incoming call", samsung.decide(ring.copy(text = "Incoming call")) == Decision.SEND)
    check("it is skipped when the default dialer is not on the list", NotificationFilter({ allowed - "com.dialer" }, OWN, { dialer }).decide(ring) == Decision.SKIP)
    check("it is skipped when the default dialer is unknown", NotificationFilter({ allowed }, OWN, { null }).decide(ring) == Decision.SKIP)
    check("an ongoing CallStyle notification without a call category is skipped", samsung.decide(ring.copy(category = null)) == Decision.SKIP)
    check("a call with nothing to show (no caller) is skipped", samsung.decide(ring.copy(title = "", text = "")) == Decision.SKIP)
    check("a call from the filter without a dialer function still follows the list", f.decide(ring) == Decision.SKIP)
    check("a message from another unlisted app is still skipped with a dialer set", samsung.decide(info(pkg = "com.shop")) == Decision.SKIP)

    var everything = false
    val all = NotificationFilter({ allowed }, OWN, { dialer }) { everything }
    val shop = info(pkg = "com.shop")
    check("an app that is not on the list is skipped while all apps is off", all.decide(shop) == Decision.SKIP)
    everything = true
    check("with all apps on, an app that is not on the list is sent", all.decide(shop) == Decision.SEND)
    check("with all apps on, PhoneBox's own notifications are still skipped", all.decide(info(pkg = OWN)) == Decision.SKIP)
    check("with all apps on, an ongoing notification is still skipped", all.decide(info(pkg = "com.shop", ongoing = true)) == Decision.SKIP)
    check("with all apps on, a group summary is still skipped", all.decide(info(pkg = "com.shop", groupSummary = true)) == Decision.SKIP)
    check("with all apps on, an empty notification is still skipped", all.decide(info(pkg = "com.shop", title = "", text = "")) == Decision.SKIP)
    check("with all apps on, a ringing call is sent even when no dialer is known", NotificationFilter({ emptySet() }, OWN, { null }) { true }.decide(ring) == Decision.SEND)
    // the send-time gate checks what the filter let through against the same rule, for the package the Mac is shown
    for (on in listOf(true, false)) for (n in listOf(shop, info(), ring, voip.copy(callStyleIncoming = true))) {
        for (dial in listOf("com.dialer", null)) {
            everything = on; dialer = dial
            val sent = all.decide(n) != Decision.SKIP
            check("what the filter sends passes the send-time gate (all apps $on, ${n.pkg}, dialer $dial)",
                !sent || appAllowed(everything, allowed, gatePackage(dial, n.pkg)))
        }
    }
    dialer = "com.dialer"
    everything = false
    check("all apps off: the send-time gate follows the list", appAllowed(false, allowed, "com.msg") && !appAllowed(false, allowed, "com.shop"))
    check("all apps on: the send-time gate lets any app through", appAllowed(true, emptySet(), "com.shop"))

    allowed.remove("com.msg")
    check("the list is read on every decision (removing an app stops it)", f.decide(info()) == Decision.SKIP)
    allowed.add("com.msg")
    check("and adding it back starts it again", f.decide(info()) == Decision.SEND)
}

private fun callerNameChecks() {
    check("a plain number is number-like", CallerName.isNumberLike("6940931040") && CallerName.isNumberLike("+30 694 093 1040") && CallerName.isNumberLike("(210) 555-1234"))
    check("a name is not", !CallerName.isNumberLike("Maria Papadopoulou") && !CallerName.isNumberLike("Έρωτας Μου") && !CallerName.isNumberLike(""))
    check("a name with a few digits is not a number", !CallerName.isNumberLike("Room 12") && !CallerName.isNumberLike("Pizza 24"))
    check("a name already in the title is kept", CallerName.pick("Maria", "Someone", { "Contact" }) == "Maria")
    check("a number is replaced by the name the notification carries", CallerName.pick("6940931040", "Maria", { "Contact" }) == "Maria")
    check("a person that is only a number again is not a name", CallerName.pick("6940931040", "+30 694 093 1040", { "Contact" }) == "Contact")
    check("a number is replaced by the contact when the notification has no name", CallerName.pick("6940931040", null, { "Έρωτας Μου" }) == "Έρωτας Μου")
    check("an unknown number stays a number", CallerName.pick("6940931040", null, { null }) == "6940931040" && CallerName.pick("6940931040", " ", { " " }) == "6940931040")
    check("the contacts are asked with the number as it was shown", run { var asked = ""; CallerName.pick("+30 694 093 1040", null) { asked = it; null }; asked == "+30 694 093 1040" })
    check("the contacts are not asked when the title is a name", run { var asked = false; CallerName.pick("Maria", null) { asked = true; null }; !asked })
    // an Arabic or Hebrew phone wraps the number in direction marks; Germany writes 030/1234567; Arabic-Indic digits are digits
    val wrapped = "‪+30 694 093 1040‬"
    check("a number in direction marks is number-like", CallerName.isNumberLike(wrapped) && CallerName.isNumberLike("⁨030/1234567⁩") && CallerName.isNumberLike("٠١٢٣٤٥٦٧٨٩"))
    check("and the contacts are asked without the marks", run { var asked = ""; CallerName.pick(wrapped, null) { asked = it; "Maria" } == "Maria" && asked == "+30 694 093 1040" })
    check("an unknown number in marks is shown as it came", CallerName.pick(wrapped, null) { null } == wrapped)
    check("a short code is a number, two digits are not", CallerName.isNumberLike("112") && CallerName.isNumberLike("*100#") && !CallerName.isNumberLike("12"))
    check("a Hebrew or Arabic name is not a number", !CallerName.isNumberLike("‏מריה") && !CallerName.isNumberLike("ماريا 24"))

    var lookups = 0
    val once = LookupOnce { lookups++; if (it == "6940931040") "Maria" else null }
    check("the contacts are asked once for the number of a call", once.get("6940931040") == "Maria" && once.get("6940931040") == "Maria" && lookups == 1)
    check("an unknown number is not looked up again at every update", once.get("2105551234") == null && once.get("2105551234") == null && lookups == 2)
    once.forget()
    check("after the call ended the next call is looked up again", once.get("2105551234") == null && lookups == 3)

    val calls = CallLifetimes()
    calls.begin("c", 1000L, "6940931040")
    check("the same title is not sent again", calls.renamed("c", "6940931040") == null)
    check("a name that arrives later is sent again, for the same call start", calls.renamed("c", "Maria") == 1000L)
    check("and only once", calls.renamed("c", "Maria") == null)
    check("a call that is not known is never renamed", calls.renamed("other", "Maria") == null)
    calls.ended("c")
    check("after the call ended nothing is renamed", calls.renamed("c", "Anna") == null)

    // the listener reconnects while a call is posted: the Mac may show it already, under the start time it had then
    calls.running("r", 2000L)
    check("a call already running at reconnect is not sent at its next update", calls.begin("r", 2500L, "6940931040") == null)
    check("nor when its title differs from anything sent", calls.renamed("r", "6940931040") == null && calls.renamed("r", "Maria") == null)
    calls.ended("r")
    check("the next call under that key is sent again", calls.begin("r", 9000L, "Anna") == 9000L)
    calls.begin("s", 100L, "Maria")
    calls.running("s", 200L)
    check("a reconnect does not reset a call this listener knows", calls.renamed("s", "Anna") == 100L)
}

private fun callLifetimeChecks() {
    check("a call from the in-call UI is gated by the default dialer", gatePackage("com.dialer", "com.samsung.android.incallui") == "com.dialer")
    check("with no default dialer known it falls back to the posting app", gatePackage(null, "com.samsung.android.incallui") == "com.samsung.android.incallui")
    check("any other notification is gated by its own app", gatePackage("com.dialer", "com.msg") == "com.msg")
    check("a VoIP call is gated by its own app, not by the dialer", gatePackage("com.dialer", "com.whatsapp") == "com.whatsapp")
    val calls = CallLifetimes()
    check("the first update of a call is sent, with the time the call began", calls.begin("incallui|1", 1000L) == 1000L)
    check("later updates of the same call are not", calls.begin("incallui|1", 1500L) == null)
    calls.ended("incallui|1")
    check("the next call, after the notification went away, is sent, with its own time", calls.begin("incallui|1", 9000L) == 9000L)
    calls.clear()
    check("after the listener was cut off the next one is sent", calls.begin("incallui|1", 9500L) == 9500L)
}

private fun callKeyChecks() {
    val keys = CallKeys(max = 3)
    check("the first time a call key is seen is true", keys.firstTime("k1"))
    check("the same call key again is false (later updates of one call are ignored)", !keys.firstTime("k1"))
    check("another key is a new call", keys.firstTime("k2"))
    keys.firstTime("k3")
    keys.firstTime("k4") // evicts k1, the oldest
    check("the oldest key is forgotten beyond the cap", keys.firstTime("k1"))
    check("a recent key is still remembered", !keys.firstTime("k3"))
}

private fun queueChecks() {
    var now = 1_000_000L
    val q = DeliveryQueue<String>(max = 20, ttlMs = 120_000) { now }
    for (i in 1..21) q.add("n$i")
    var taken = q.take().map { it.item }
    check("the queue keeps the newest 20", taken.size == 20 && taken.first() == "n2" && taken.last() == "n21")
    check("taking empties the queue", q.take().isEmpty())

    q.add("old")
    now += 119_000
    q.add("fresh")
    now += 2_000 // "old" is now 121 s old, "fresh" 2 s
    taken = q.take().map { it.item }
    check("an item older than two minutes is dropped, a newer one is kept", taken == listOf("fresh"))

    q.add("kept-time")
    val first = q.take().single()
    now += 100_000
    q.add(first.item, first.at) // a failed retry goes back with its original time
    now += 30_000
    check("a retried item keeps its original time and expires on schedule", q.take().isEmpty())

    // restore: unsent entries go back ahead of newer ones, and the cap then drops the oldest
    val r = DeliveryQueue<String>(max = 3, ttlMs = 120_000) { now }
    r.add("a"); r.add("b")
    val (mark, batch) = r.takeBatch()
    r.add("new1"); r.add("new2")              // posted while the batch was out
    check("a batch taken before any clear can be put back", r.restore(batch, mark))
    check("put-back entries come before newer ones and the cap drops the oldest", r.take().map { it.item } == listOf("b", "new1", "new2"))
    r.add("x")
    val (mark2, batch2) = r.takeBatch()
    r.clear()
    check("a batch taken before a clear is no longer current", !r.isCurrent(mark2))
    check("and cannot be put back", !r.restore(batch2, mark2) && r.isEmpty())

    val late = DeliveryQueue<String>(max = 2, ttlMs = 120_000) { now }
    late.add("newer", now - 40)
    late.add("newest", now - 10)
    late.addIfCurrent("oldest", late.mark(), now - 90)
    check("a late insertion evicts the entry with the earliest timestamp",
        late.take().map { it.item } == listOf("newer", "newest"))
    late.add("in flight", now - 30)
    val (pendingMark, inFlight) = late.takeBatch()
    late.add("arrived late", now - 40)
    check("restoring a batch still orders by timestamp",
        late.restore(inFlight, pendingMark) && late.take().map { it.item } == listOf("arrived late", "in flight"))

    val beforeLookup = q.mark()
    q.clear()
    q.add("new computer")
    check("a lookup started before a clear cannot admit a call to the new queue",
        !q.addIfCurrent("old call", beforeLookup) && q.take().single().item == "new computer")
    val foundAt = now
    val validLookup = q.mark()
    now += 119_000
    check("the current generation accepts a call with its original time",
        q.addIfCurrent("waiting for a name", validLookup, foundAt))
    now += 2_000
    check("a late lookup does not extend the notification lifetime", q.take().isEmpty())
}

private fun notificationBodyChecks() {
    val hostile = "He said \"hi\" & \\ back\nline2\ttab \u0001 </script> Καλημέρα 🌍 {\"key\":\"evil\"}"
    val body = BridgeClip.notificationBody("0|com.msg|7|null|10042", "Μηνύματα", "com.msg", hostile, hostile + "!", "call", 1_700_000_000_123L)
    val m = BridgeClip.parseObject(body)
    check("the body is one JSON object the agent can read", m != null)
    check("the key survives", m!!["key"] == "0|com.msg|7|null|10042")
    check("the app name survives in Greek", m["app"] == "Μηνύματα")
    check("the package survives", m["package"] == "com.msg")
    check("a hostile title survives byte for byte", m["title"] == hostile)
    check("a hostile text survives byte for byte", m["text"] == hostile + "!")
    check("the category survives", m["category"] == "call")
    check("the time is a number", (m["time"] as Double).toLong() == 1_700_000_000_123L)
    check("the body has no raw control character", body.none { it < ' ' })
    check("the body names exactly the seven fields the agent reads", m.keys == setOf("key", "app", "package", "title", "text", "category", "time"))

    val withIcon = BridgeClip.notificationBody("k", "Facebook", "com.facebook.katana", "Anna", "hi", "message", 1L, "iVBORw0KGgo+/=")
    val n = BridgeClip.parseObject(withIcon)
    check("an icon travels as the icon field", n != null && n["icon"] == "iVBORw0KGgo+/=")
    check("with an icon the body still reads as one object with the same fields", n!!.keys == setOf("key", "app", "package", "title", "text", "category", "time", "icon"))
    check("the other fields are unchanged by the icon", n["title"] == "Anna" && n["package"] == "com.facebook.katana" && (n["time"] as Double).toLong() == 1L)
    check("no icon means no icon field", BridgeClip.parseObject(BridgeClip.notificationBody("k", "a", "p", "t", "x", "message", 1L, null))!!.containsKey("icon").not())
    // the largest icon AppIcons sends (30 KiB of PNG, 40 KB of base64) with text that is all six-byte escapes and a long key
    val bigIcon = "A".repeat(40_960)
    val escapes = "\u0001".repeat(2_000)
    val fits = BridgeClip.notificationBody("k", "a", "p", "t", escapes, "message", 1L, bigIcon)
    check("a full-size icon with the longest escaped text still fits the agent's 64 KiB", fits.toByteArray().size <= BridgeClip.MAX_NOTIFICATION_BYTES && BridgeClip.parseObject(fits)!!["icon"] == bigIcon)
    val over = BridgeClip.notificationBody("k".repeat(20_000), "a", "p", "t", escapes, "message", 1L, bigIcon)
    val overMap = BridgeClip.parseObject(over)
    check("a body that the icon would push past 64 KiB is sent without the icon, not refused whole",
        over.toByteArray().size <= BridgeClip.MAX_NOTIFICATION_BYTES && overMap != null && !overMap.containsKey("icon") && overMap["text"] == escapes)
}

/** The agent's real /api/config reply: {"version": "...", "config": { ...the public config... }}. */
private fun cfg(inner: String) = """{"version":"1","config":$inner}"""

private fun jsonChecks() {
    check("notifications true is read", BridgeClip.notificationsOn(cfg("""{"bridge":{"clipboard":false,"notifications":true}}""")) == true)
    check("notifications false is read", BridgeClip.notificationsOn(cfg("""{"bridge":{"notifications":false}}""")) == false)
    check("an agent without a bridge section is off", BridgeClip.notificationsOn(cfg("""{"pages":[]}""")) == false)
    check("a null bridge section is off", BridgeClip.notificationsOn(cfg("""{"bridge":null}""")) == false)
    check("a text 'true' is not on", BridgeClip.notificationsOn(cfg("""{"bridge":{"notifications":"true"}}""")) == false)
    check("nested arrays and objects before it are skipped over",
        BridgeClip.notificationsOn(cfg("""{"pages":[{"a":[1,2,{"b":"x\"y"}],"c":{"d":[true,false,null]}}],"bridge":{"notifications":true}}""")) == true)
    check("a reply without the config wrapper is unknown, not off (the old top-level shape was a bug)",
        BridgeClip.notificationsOn("""{"bridge":{"notifications":true}}""") == null)
    check("a config that is not an object is unknown", BridgeClip.notificationsOn("""{"version":"1","config":5}""") == null)
    check("garbage is unknown (null), not off", BridgeClip.notificationsOn("not json") == null)
    check("an unterminated reply is unknown", BridgeClip.notificationsOn("""{"version":"1","config":{"bridge":{"notifications":true""") == null)
    check("an array at the top is unknown", BridgeClip.notificationsOn("""[{"config":{"bridge":{"notifications":true}}}]""") == null)
    val deep = "[".repeat(200) + "]".repeat(200)
    check("absurdly deep nesting is refused, not a stack overflow", BridgeClip.notificationsOn(cfg("""{"x":$deep}""")) == null)
}

private fun configCacheChecks() {
    var now = 5_000_000L
    val fetches = AtomicInteger()
    var body: String? = cfg("""{"bridge":{"notifications":true}}""")
    val cache = BridgeConfigCache { now }
    fun on() = cache.notificationsOn { fetches.incrementAndGet(); body }
    check("the first read asks the agent and finds it on", on())
    check("a second read inside a minute asks nothing", on() && fetches.get() == 1)
    body = cfg("""{"bridge":{"notifications":false}}""")
    now += 59_000
    check("still cached just under a minute", on() && fetches.get() == 1)
    now += 2_000
    check("after a minute it asks again and follows the change", !on() && fetches.get() == 2)

    cache.markOn()
    check("markOn forgets an old 'off' at once", on())

    // an agent that cannot be reached: unknown counts as on (try to send, the queue holds it), asked again after 10 s
    now += 500_000
    val unreachable = AtomicInteger()
    val c2 = BridgeConfigCache { now }
    fun readUnreachable() = c2.notificationsOn { unreachable.incrementAndGet(); null }
    check("when the agent cannot be asked, sending is tried", readUnreachable())
    now += 9_000
    readUnreachable()
    check("and it is not asked again before 10 s", unreachable.get() == 1)
    now += 2_000
    readUnreachable()
    check("but it is asked again after 10 s, not a minute", unreachable.get() == 2)

    // a known 'off' survives an unreachable agent
    now += 500_000
    var phase = 0
    val c3 = BridgeConfigCache { now }
    fun readOff() = c3.notificationsOn { if (phase++ == 0) cfg("""{"bridge":{"notifications":false}}""") else null }
    check("an agent that says off is off", !readOff())
    now += 70_000
    check("and stays off when the next ask fails", !readOff())

    // markOff: a 403 from the agent turns it off at once, and it asks again after a minute
    now += 500_000
    val c4 = BridgeConfigCache { now }
    fun readOn() = c4.notificationsOn { cfg("""{"bridge":{"notifications":true}}""") }
    check("on before a 403", readOn())
    c4.markOff()
    check("a 403 (markOff) turns it off at once", !readOn())
    now += 61_000
    check("and a minute later it asks again and finds it on", readOn())

    // a body that is not JSON counts as a failed ask
    now += 500_000
    val c5 = BridgeConfigCache { now }
    check("an answer that is not JSON is unknown, so sending is tried", c5.notificationsOn { "<html>not the agent</html>" })
}

private fun cutChecks() {
    check("short text is not cut", BridgeClip.cut("hello", 10) == "hello")
    check("a limit of zero or less gives no text instead of an error", BridgeClip.cut("abc", 0) == "" && BridgeClip.cut("abc", -3) == "" && BridgeClip.cut("", 0) == "")
    check("a limit of one is just the ellipsis", BridgeClip.cut("abc", 1) == "…")
    check("text at the limit is not cut", BridgeClip.cut("a".repeat(200), 200) == "a".repeat(200))
    check("text over the limit is cut to the limit with an ellipsis", BridgeClip.cut("a".repeat(201), 200).let { it.length == 200 && it.endsWith("…") })
    val emoji = "a".repeat(198) + "🌍🌍"     // the cut would fall inside the first emoji
    val cutEmoji = BridgeClip.cut(emoji, 200)
    check("a cut never leaves half an emoji", cutEmoji.length <= 200 && cutEmoji.endsWith("…") && !cutEmoji.dropLast(1).last().isHighSurrogate())
}

private class Rig(val clock: () -> Long) {
    val queue = DeliveryQueue<NotificationDelivery.Pending>(clock = clock)
    var phoneOn = true
    var macOn = true
    val allowedApps = mutableSetOf("com.msg", "com.dialer")
    val sent = mutableListOf<String>()
    val answers = ArrayDeque<SendResult>()
    var outcome = NotificationOutcome.NONE
    var macMarkedOff = 0
    var macMarkedOn = 0
    val delivery = NotificationDelivery(queue, { phoneOn }, { it in allowedApps }, { macOn },
        { body -> sent.add(body); answers.removeFirstOrNull() ?: SendResult.OK },
        { outcome = it }, { macMarkedOff++ }, { macMarkedOn++ })
    fun add(pkg: String, body: String, at: Long = clock()) = queue.add(NotificationDelivery.Pending(pkg, body), at)
}

private fun deliveryChecks() {
    var now = 1_000_000L
    var r = Rig { now }
    r.add("com.msg", "one"); r.add("com.msg", "two"); r.add("com.dialer", "three")
    check("a batch is sent in order and reports ok", !r.delivery.drain() && r.sent == listOf("one", "two", "three") && r.outcome == NotificationOutcome.OK)
    check("a good send tells the config cache the Mac is on", r.macMarkedOn == 3)
    check("nothing is left afterwards", r.queue.isEmpty())

    r = Rig { now }
    r.add("com.msg", "one"); r.phoneOn = false
    check("with the phone's switch off nothing is sent", !r.delivery.drain() && r.sent.isEmpty())
    r.phoneOn = true
    check("and what was waiting is gone, not sent when the switch comes back", !r.delivery.drain() && r.sent.isEmpty() && r.queue.isEmpty())

    r = Rig { now }
    r.add("com.msg", "one"); r.macOn = false
    check("with the Mac's switch known off nothing is sent and the screen says so", !r.delivery.drain() && r.sent.isEmpty() && r.outcome == NotificationOutcome.MAC_OFF)
    check("and the queue is emptied", r.queue.isEmpty())

    r = Rig { now }
    r.add("com.msg", "one", now); r.add("com.msg", "two", now)
    val slow = Rig { now }
    slow.add("com.msg", "slow-first", now); slow.add("com.msg", "second", now)
    val slowDelivery = NotificationDelivery(slow.queue, { slow.phoneOn }, { it in slow.allowedApps }, { slow.macOn },
        { body -> slow.sent.add(body); now += 121_000; SendResult.OK }, { }, { }, { })   // the first send takes 121 s
    slowDelivery.drain()
    check("a notification that grew too old while the batch was being sent is not sent", slow.sent == listOf("slow-first"))

    val switching = Rig { now }
    switching.add("com.msg", "first", now); switching.add("com.msg", "second", now)
    val switchingDelivery = NotificationDelivery(switching.queue, { switching.phoneOn }, { it in switching.allowedApps }, { switching.macOn },
        { body -> switching.sent.add(body); switching.phoneOn = false; SendResult.OK }, { }, { }, { })   // the owner turns the switch off during the first send
    switchingDelivery.drain()
    check("a switch turned off during a batch stops it at once and empties the queue", switching.sent == listOf("first") && switching.queue.isEmpty())

    // off and on again while one request is out: clearPending ran, so the rest of the batch must not go
    val flipping = Rig { now }
    flipping.add("com.msg", "first", now); flipping.add("com.msg", "second", now)
    val flippingDelivery = NotificationDelivery(flipping.queue, { flipping.phoneOn }, { it in flipping.allowedApps }, { flipping.macOn },
        { body -> flipping.sent.add(body); flipping.queue.clear(); flipping.queue.add(NotificationDelivery.Pending("com.msg", "posted-after"), now); SendResult.OK },
        { }, { }, { })
    flippingDelivery.drain()
    check("a switch turned off and on again during a send voids the rest of the batch",
        flipping.sent == listOf("first") && flipping.queue.take().map { it.item.body } == listOf("posted-after"))

    for (duringConfig in listOf(true, false)) {
        val changed = Rig { now }
        changed.add("com.msg", "old")
        fun switchComputer() { changed.queue.clear(); changed.add("com.msg", "new") }
        var notifiedOff = false
        val delivery = NotificationDelivery(changed.queue, { true }, { true },
            { if (duringConfig) switchComputer(); !duringConfig },
            { switchComputer(); SendResult.OFF },
            { notifiedOff = it == NotificationOutcome.MAC_OFF }, { }, { })
        delivery.drain()
        check("the old computer's OFF response keeps the new queue and status (config=$duringConfig)",
            !notifiedOff && changed.queue.take().map { it.item.body } == listOf("new"))
    }

    // the switch goes off while a request that fails is out: nothing is put back
    val offDuringFailure = Rig { now }
    offDuringFailure.add("com.msg", "first", now); offDuringFailure.add("com.msg", "second", now)
    val offDelivery = NotificationDelivery(offDuringFailure.queue, { offDuringFailure.phoneOn }, { it in offDuringFailure.allowedApps }, { offDuringFailure.macOn },
        { _ -> offDuringFailure.queue.clear(); SendResult.UNREACHABLE }, { }, { }, { })
    check("a failed send after the queue was cleared puts nothing back and asks for no retry", !offDelivery.drain() && offDuringFailure.queue.isEmpty())

    // an unreachable Mac while new notifications arrive: the unsent ones stay ahead of them, the cap drops the oldest
    val capped = DeliveryQueue<NotificationDelivery.Pending>(max = 3, clock = { now })
    capped.add(NotificationDelivery.Pending("com.msg", "a"), now); capped.add(NotificationDelivery.Pending("com.msg", "b"), now)
    val cappedDelivery = NotificationDelivery(capped, { true }, { true }, { true },
        { _ -> capped.add(NotificationDelivery.Pending("com.msg", "new1"), now); capped.add(NotificationDelivery.Pending("com.msg", "new2"), now); SendResult.UNREACHABLE },
        { }, { }, { })
    check("an unreachable Mac asks for a retry", cappedDelivery.drain())
    check("the unsent come back before the newer ones and the oldest is the one dropped",
        capped.take().map { it.item.body } == listOf("b", "new1", "new2"))

    // "all apps" let an unlisted app's notification into the queue; the owner turns it off before it is sent
    val everyApp = Rig { now }
    var allAppsOn = true
    everyApp.add("com.shop", "unlisted", now); everyApp.add("com.msg", "listed", now)
    NotificationDelivery(everyApp.queue, { everyApp.phoneOn }, { appAllowed(allAppsOn, everyApp.allowedApps, it) }, { everyApp.macOn },
        { body -> everyApp.sent.add(body); SendResult.OK }, { }, { }, { }).also { allAppsOn = false }.drain()
    check("what all apps queued is not sent once it is turned off, but a listed app still is", everyApp.sent == listOf("listed"))

    r = Rig { now }
    r.add("com.msg", "one"); r.add("com.msg", "two"); r.add("com.msg", "three")
    r.answers.addAll(listOf(SendResult.OK, SendResult.OFF))
    val retry403 = r.delivery.drain()
    check("a 403 stops the batch: the third is never sent", r.sent == listOf("one", "two") && !retry403)
    check("a 403 empties the queue and marks the Mac off", r.queue.isEmpty() && r.macMarkedOff == 1 && r.outcome == NotificationOutcome.MAC_OFF)

    r = Rig { now }
    r.add("com.msg", "one", now); r.add("com.msg", "two", now); r.add("com.msg", "three", now)
    r.answers.addAll(listOf(SendResult.OK, SendResult.UNREACHABLE))
    check("an unreachable Mac stops the batch and asks to be retried", r.delivery.drain() && r.sent == listOf("one", "two") && r.outcome == NotificationOutcome.UNREACHABLE)
    now += 60_000
    check("the failed one and the rest are kept, in order", r.queue.take().map { it.item.body } == listOf("two", "three"))

    now = 2_000_000L
    r = Rig { now }
    r.add("com.msg", "old", now)
    now += 121_000
    r.add("com.msg", "fresh", now)
    check("a notification older than two minutes is never sent, the fresh one is", !r.delivery.drain() && r.sent == listOf("fresh"))

    now = 3_000_000L
    r = Rig { now }
    r.add("com.msg", "kept-time", now)
    r.answers.add(SendResult.UNREACHABLE)
    r.delivery.drain()                       // unreachable: it goes back with its original time
    now += 119_000
    check("a retried notification is still sent just before two minutes", r.queue.take().size == 1)
    now = 4_000_000L
    r = Rig { now }
    r.add("com.msg", "kept-time", now)
    r.answers.add(SendResult.UNREACHABLE)
    r.delivery.drain()
    now += 121_000
    check("and is dropped just after: the failed attempt did not renew it", r.queue.take().isEmpty())

    r = Rig { now }
    r.add("com.msg", "kept"); r.add("com.shop", "turned-off-meanwhile"); r.add("com.msg", "also kept")
    check("an app the owner turned off meanwhile is skipped, the others sent", !r.delivery.drain() && r.sent == listOf("kept", "also kept"))

    r = Rig { now }
    r.add("com.msg", "huge"); r.add("com.msg", "next")
    r.answers.add(SendResult.TOO_LARGE)
    check("a notification the agent calls too large is dropped and the batch goes on", !r.delivery.drain() && r.sent == listOf("huge", "next"))
}

fun main() {
    filterChecks()
    callKeyChecks()
    callLifetimeChecks()
    callerNameChecks()
    queueChecks()
    notificationBodyChecks()
    jsonChecks()
    cutChecks()
    deliveryChecks()
    configCacheChecks()
    println("ALL $passed CHECKS PASSED")
}
