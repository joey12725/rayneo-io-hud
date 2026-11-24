package io.lampyr.glasses.clock

/**
 * Local parser for spoken and typed clock requests ("10 minutes", "an hour and a half", "7:30 am",
 * "weekdays at 6"). Runs on the phone so the Clock app works without the gateway's cleanup step.
 */
object ClockParse {
    sealed interface Command {
        data class Timer(val ms: Long, val label: String = "") : Command
        data class Alarm(val hour: Int, val minute: Int, val days: Set<Int> = emptySet(), val label: String = "") : Command
    }

    private val ONES = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val DAY_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** Lowercase, spoken numbers to digits ("twenty five" -> "25", "a half" stays), punctuation to spaces. */
    fun normalize(text: String): String {
        val words = text.lowercase()
            .replace(Regex("(\\d)\\s*([ap])\\.?\\s*m\\b\\.?"), "$1 $2m")
            .replace(Regex("\\b([ap])\\.\\s*m\\.?"), "$1m")
            .replace(Regex("[^a-z0-9:.+\\- ]"), " ")
            .replace("-", " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val tens = TENS[w]
            if (tens != null) {
                val next = words.getOrNull(i + 1)?.let { ONES[it] }?.takeIf { it in 1..9 && words[i + 1] !in setOf("a", "an") }
                if (next != null) { out += (tens + next).toString(); i += 2; continue }
                out += tens.toString(); i++; continue
            }
            val one = ONES[w]
            // "a"/"an" only count before a unit ("an hour", "a minute"), never as a label word.
            if (one != null && (w !in setOf("a", "an") || words.getOrNull(i + 1)?.let { unitMs(it) != null } == true)) { out += one.toString(); i++; continue }
            out += w; i++
        }
        return out.joinToString(" ")
    }

    private fun unitMs(word: String): Long? = when (word.trimEnd('.')) {
        "h", "hr", "hrs", "hour", "hours" -> 3_600_000L
        "m", "min", "mins", "minute", "minutes" -> 60_000L
        "s", "sec", "secs", "second", "seconds" -> 1_000L
        else -> null
    }

    /**
     * A duration in ms, or null. Accepts "10 minutes", "1h30m", "1 hour and a half", "half an hour", "90 s",
     * "2.5 min", "1:30" (m:ss), "1:02:03" (h:mm:ss) and a bare number (minutes).
     */
    fun duration(text: String): Long? {
        val s = normalize(text)
        Regex("\\b(\\d{1,2}):(\\d{2}):(\\d{2})\\b").find(s)?.let { m ->
            val (h, mi, se) = m.destructured
            return (h.toLong() * 3600 + mi.toLong() * 60 + se.toLong()) * 1000
        }
        Regex("\\b(\\d{1,3}):(\\d{2})\\b").find(s)?.let { m ->
            val (mi, se) = m.destructured
            return (mi.toLong() * 60 + se.toLong()) * 1000
        }
        if (Regex("\\bhalf (an|1) hour\\b").containsMatchIn(s)) return 1_800_000L + duration(s.replace(Regex("\\bhalf (an|1) hour\\b"), "")).let { it ?: 0 }
        if (Regex("\\b(quarter|1 quarter) (of )?(an |1 )?hour\\b").containsMatchIn(s)) return 900_000L
        var total = 0L
        var found = false
        val tokens = Regex("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)?").findAll(s).toList()
        tokens.forEachIndexed { i, m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return@forEachIndexed
            var unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unitMs)
            // "1h30" / "1 hour 30": a bare number after hours means minutes, after minutes means seconds.
            if (unit == null && i > 0) unit = when (tokens[i - 1].groupValues[2].let(::unitMs)) { 3_600_000L -> 60_000L; 60_000L -> 1_000L; else -> null }
            if (unit == null && tokens.size == 1 && m.groupValues[2].isEmpty()) unit = 60_000L
            if (unit != null) {
                total += (n * unit).toLong(); found = true
                // "an hour and a half", "2 and a half minutes"
                val tail = s.substring(m.range.last + 1).trimStart()
                if (Regex("^and (a |1 )?half\\b").containsMatchIn(tail)) total += unit / 2
            }
        }
        if (!found) {
            // "2 and a half minutes"
            Regex("\\b(\\d+) and (a |1 )?half ([a-z]+)").find(s)?.let { m ->
                val unit = unitMs(m.groupValues[3]) ?: return null
                return m.groupValues[1].toLong() * unit + unit / 2
            }
            return null
        }
        if (Regex("\\b(\\d+) and (a |1 )?half ([a-z]+)").containsMatchIn(s)) {
            val m = Regex("\\b(\\d+) and (a |1 )?half ([a-z]+)").find(s)!!
            unitMs(m.groupValues[3])?.let { total += it / 2 }
        }
        return total.takeIf { it > 0 }
    }

    /**
     * A time of day as (hour 0..23, minute), or null. Accepts "7:30 am", "7:30", "19:45", "7 pm", "730am",
     * "7.30 pm", "7 30 pm", "noon", "midnight", "half past 7", "quarter to 8".
     */
    fun time(text: String): Pair<Int, Int>? {
        val s = normalize(text)
        if (Regex("\\bnoon\\b").containsMatchIn(s)) return 12 to 0
        if (Regex("\\bmidnight\\b").containsMatchIn(s)) return 0 to 0
        val pm = Regex("\\b(pm|evening|tonight|afternoon)\\b").containsMatchIn(s)
        val am = Regex("\\b(am|morning)\\b").containsMatchIn(s)
        fun hour(h: Int): Int? = when {
            h !in 0..23 -> null
            h == 12 && am -> 0
            h in 1..11 && pm -> h + 12
            else -> h
        }
        Regex("\\bhalf past (\\d{1,2})\\b").find(s)?.let { return hour(it.groupValues[1].toInt())?.let { h -> h to 30 } }
        Regex("\\bquarter past (\\d{1,2})\\b").find(s)?.let { return hour(it.groupValues[1].toInt())?.let { h -> h to 15 } }
        Regex("\\bquarter (to|till) (\\d{1,2})\\b").find(s)?.let { return hour(it.groupValues[2].toInt())?.let { h -> (h + 23) % 24 to 45 } }
        Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(s)?.let { m ->
            val mi = m.groupValues[2].toInt().takeIf { it < 60 } ?: return null
            return hour(m.groupValues[1].toInt())?.let { it to mi }
        }
        Regex("\\b(\\d{1,2})(\\d{2}) ?(am|pm)\\b").find(s)?.let { m ->
            val mi = m.groupValues[2].toInt().takeIf { it < 60 } ?: return null
            return hour(m.groupValues[1].toInt())?.let { it to mi }
        }
        Regex("\\b(\\d{1,2}) (\\d{2}) ?(am|pm)?\\b").find(s)?.let { m ->
            val mi = m.groupValues[2].toInt().takeIf { it < 60 } ?: return null
            return hour(m.groupValues[1].toInt())?.let { it to mi }
        }
        Regex("\\b(\\d{1,2}) ?(am|pm|o clock|oclock)\\b").find(s)?.let { m -> return hour(m.groupValues[1].toInt())?.let { it to 0 } }
        Regex("\\b(?:at|for) (\\d{1,2})\\b").find(s)?.let { m -> return hour(m.groupValues[1].toInt())?.let { it to 0 } }
        Regex("^\\s*(\\d{1,2})\\s*$").find(s)?.let { m -> return hour(m.groupValues[1].toInt())?.let { it to 0 } }
        return null
    }

    /** ISO days for "daily", "weekdays", "weekends", "once", "mon wed fri", "monday and friday"; null = not stated. */
    fun days(text: String): Set<Int>? {
        val s = text.lowercase()
        if (Regex("\\b(daily|every ?day|each day)\\b").containsMatchIn(s)) return (1..7).toSet()
        if (Regex("\\bweekdays?\\b|\\bwork ?days?\\b").containsMatchIn(s)) return (1..5).toSet()
        if (Regex("\\bweekends?\\b").containsMatchIn(s)) return setOf(6, 7)
        if (Regex("\\b(once|one time|no repeat|never)\\b").containsMatchIn(s)) return emptySet()
        val found = Regex("\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b").findAll(s).map { DAY_NAMES.indexOf(it.groupValues[1]) + 1 }.toSortedSet()
        return found.takeIf { it.isNotEmpty() }
    }

    private val FILLER = Regex("\\b(set|start|make|create|add|me|please|a|an|the|timer|timers|alarm|alarms|for|at|in|on|to|of|and|wake|up|every|each|minutes?|mins?|hours?|hrs?|seconds?|secs?|am|pm|half|quarter|past|till|daily|weekdays?|weekends?|once|morning|evening|tonight|afternoon|noon|midnight|o|clock|oclock|mon\\w*|tue\\w*|wed\\w*|thu\\w*|fri\\w*|sat\\w*|sun\\w*|h|m|s)\\b")

    private fun label(s: String): String =
        normalize(s).replace(Regex("[0-9:.]+"), " ").replace(FILLER, " ").replace(Regex("\\s+"), " ").trim()
            .take(30).replaceFirstChar { it.uppercase() }

    /**
     * What a spoken request asks for. [prefer] breaks ties when the words fit both ("timer" or "alarm" screen).
     * "alarm", am/pm or "at 7" mean an alarm; a duration means a timer.
     */
    fun command(text: String, prefer: String = "timer"): Command? {
        val s = normalize(text)
        val alarmWords = Regex("\\b(alarm|wake)\\b").containsMatchIn(s) || Regex("\\b(am|pm|noon|midnight|o clock)\\b").containsMatchIn(s) ||
            Regex("\\bat \\d").containsMatchIn(s)
        val timerWords = Regex("\\b(timer|in \\d|for \\d)").containsMatchIn(s) || Regex("\\d\\s*(h|hr|hrs|hours?|m|min|mins|minutes?|s|sec|secs|seconds?)\\b").containsMatchIn(s)
        val wantAlarm = alarmWords && !Regex("\\btimer\\b").containsMatchIn(s) || (!timerWords && prefer == "alarm")
        if (wantAlarm) time(s)?.let { (h, m) -> return Command.Alarm(h, m, days(s) ?: emptySet(), label(s)) }
        duration(s)?.let { return Command.Timer(it, label(s)) }
        if (!wantAlarm) time(s)?.takeIf { alarmWords }?.let { (h, m) -> return Command.Alarm(h, m, days(s) ?: emptySet(), label(s)) }
        return null
    }
}
