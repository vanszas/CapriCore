package com.capri.mobile

/**
 * Timers and alarms you ask her for ("set a timer for 5 minutes", "wake me up at 7:30", in her eight languages).
 * She hands them to the phone's own clock app, so they ring even if she's closed.
 */
/**
 * A case-insensitive Regex whose \b and \w know every alphabet (Cyrillic too). Android's regex engine (ICU) already
 * works that way and rejects the (?U) flag; plain Java (the unit tests) needs it.
 */
fun rx(pattern: String, vararg opts: RegexOption): Regex =
    Regex(if (System.getProperty("java.vm.name") == "Dalvik") pattern else "(?U)$pattern", setOf(RegexOption.IGNORE_CASE) + opts)

object Clock {
    sealed class Ask {
        data class Timer(val seconds: Int, val label: String) : Ask()
        data class Alarm(val hour: Int, val minute: Int) : Ask()
        object Unclear : Ask()  // they asked for one, but the time didn't make sense
    }

    private val O = RegexOption.IGNORE_CASE
    private val TIMER = rx("\\b(timers?|countdown|remind me in|in \\d+ ?(?:min|minutes|hours?)|temporizador|recu[eé]rdame en|" +
        "cron[oô]metro|lembra-me em|me lembra em|minuteur|rappelle-moi dans|countdown|ricordami tra|timer|erinnere mich in|" +
        "minutnik|przypomnij mi za|таймер|напомни через)\\b", O)
    private val ALARM = rx("\\b(alarms?|wake me|alarma|despi[eé]rtame|despertador|me acorda|alarme|r[eé]veil|r[eé]veille-moi|" +
        "sveglia|svegliami|wecker|weck mich|budzik|obud[zź] mnie|будильник|разбуди)", O)

    private val SEC = "s|sec|secs|seconds?|segundos?|secondes?|secondi|secondo|sekunden?|sekund[ay]?|секунд[уы]?"
    private val MIN = "m|min|mins|minutes?|minutos?|minuti|minuto|minuten?|minut[ya]?|минут[уы]?"
    private val HOUR = "h|hr|hrs|hours?|horas?|heures?|ore|ora|stunden?|godzin[ay]?|godzinę|час(?:а|ов)?"
    private val PART = rx("(\\d+(?:[.,]\\d+)?|an?|one|half an?|una?|um|uma|une?|un'|ein(?:e|en)?|jed(?:en|n[aą])|одн[уа]|один|полчаса)\\s*($HOUR|$MIN|$SEC)\\b", O)
    private val CLOCK = rx("\\b(\\d{1,2})(?:[:h.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|uhr|h\\b)?", O)
    private val PM = rx("\\b(tonight|this evening|in the evening|de la tarde|de la noche|da tarde|da noite|du soir|de l'apr[eè]s-midi|" +
        "di sera|del pomeriggio|abends|nachmittags|wieczorem|po południu|вечера|дня)\\b", O)

    fun parse(text: String): Ask? {
        val t = text.lowercase()
        val wantsAlarm = ALARM.containsMatchIn(t)
        val wantsTimer = !wantsAlarm && (TIMER.containsMatchIn(t) || (PART.containsMatchIn(t) && rx("\\b(set|start|pon|inicia|mets|lance|imposta|stell|ustaw|поставь|запусти)\\b", O).containsMatchIn(t)))
        if (wantsTimer) {
            var secs = 0.0
            for (m in PART.findAll(t)) {
                val n = when (val q = m.groupValues[1].lowercase()) {
                    "half a", "half an", "полчаса" -> 0.5
                    else -> q.replace(',', '.').toDoubleOrNull() ?: 1.0
                }
                val unit = m.groupValues[2].lowercase()
                secs += n * when {
                    rx("^($HOUR)$", O).matches(unit) -> 3600
                    rx("^($MIN)$", O).matches(unit) -> 60
                    else -> 1
                }
            }
            if ("полчаса" in t && secs == 0.0) secs = 1800.0
            if (secs < 1 || secs > 24 * 3600) return Ask.Unclear
            val label = rx("\\b(?:for|to|para|pour|per|für|zum|na|чтобы)\\s+(?!\\d)(.{2,40})$", O).find(t)?.groupValues?.get(1)
                ?.takeIf { !PART.containsMatchIn(it) }.orEmpty()
            return Ask.Timer(secs.toInt(), label)
        }
        if (wantsAlarm) {
            val m = CLOCK.findAll(t).firstOrNull { it.groupValues[1].toInt() in 0..23 } ?: return Ask.Unclear
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3].lowercase().replace(".", "")
            if (min > 59) return Ask.Unclear
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            if (ampm.isEmpty() && h in 1..11 && PM.containsMatchIn(t)) h += 12
            return Ask.Alarm(h, min)
        }
        return null
    }

    /** 90 -> "1 min 30 s", 5400 -> "1 h 30 min". */
    fun human(secs: Int): String {
        val h = secs / 3600
        val m = secs % 3600 / 60
        val s = secs % 60
        return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null, if (s > 0) "$s s" else null)
            .joinToString(" ").ifEmpty { "0 s" }
    }
}
