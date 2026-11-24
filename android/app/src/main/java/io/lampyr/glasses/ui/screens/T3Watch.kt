package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.*

/**
 * X in a T3 thread: thread history, and "Watch this thread", which registers the thread on the gateway so the glasses
 * notify about it (needs you = pop-up, finished = list). Unwatched threads never notify (requirement 3.2).
 */
class T3ThreadActions(ui: Ui, private val key: ThreadKey) :
    ListView(
        ui,
        "t3act",
        items = {
            val watching = ui.data.t3.isWatched(key.machine, key.id)
            listOf(
                Row("Thread history", icon = "docs", open = { ui.replaceTop(T3History(ui, key)) }),
                Row(
                    if (watching) "Stop watching" else "Watch this thread",
                    icon = if (watching) "cross" else "bell",
                    open = {
                        val title = ui.data.t3.detail(key.machine, key.id)?.thread?.title ?: ui.data.t3.threads.find { it.machine == key.machine && it.id == key.id }?.title.orEmpty()
                        ui.pop()
                        ui.perform { ui.data.t3.watch(key.machine, key.id, title, !watching) }
                    },
                ),
            )
        },
        chrome = { t -> frame(ui, "Thread", "t3", t) },
        kind = "t3-actions",
    ) {
    init {
        textTitle = { "Thread" }
        textHint = { "Y pick B back" }
    }
}
