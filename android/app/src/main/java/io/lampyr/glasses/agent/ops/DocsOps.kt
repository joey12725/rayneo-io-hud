package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Document
import io.lampyr.glasses.ui.ReaderView
import io.lampyr.glasses.ui.screens.Docs
import kotlinx.serialization.json.*

/** Docs (documents the assistant pushed with glasses_push_doc): list, read, open in the glasses reader, page through it. */
object DocsOps : OpProvider {
    private fun AgentCall.doc(): Document =
        resolve(str("doc"), ui.data.docs.documents, "document", "docs_list", { it.id }, { it.title })

    /** The open document reader (Docs > row), if it is on top. */
    private fun reader(ui: io.lampyr.glasses.ui.Ui) = (ui.top as? ReaderView)?.takeIf { it.id == "doc" }

    override fun ops() = listOf(
        OpSpec(
            "docs.list", "List glasses docs", "Documents in the glasses Docs app, newest first: id, title, created time and length.",
            risk = Risk.READ,
        ) {
            ui.data.docs.refresh()
            ok(data = JsonArray(ui.data.docs.documents.take(MAX_ITEMS).map { d ->
                buildJsonObject { put("id", d.id); put("title", d.title); putText("createdAt", d.createdAt); put("chars", d.text.length) }
            }))
        },

        OpSpec(
            "docs.read", "Read a glasses doc", "The text of one document (id or title).",
            params = listOf(Param.string("doc", "Document id or title", required = true, maxLength = 300)),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            val d = doc()
            ui.data.docs.refreshDocument(d.id)
            val text = ui.data.docs.documents.find { it.id == d.id }?.text ?: d.text
            ok(data = buildJsonObject { put("id", d.id); put("title", d.title); put("text", clip(text)); if (text.length > AgentScreen.MAX_TEXT) put("truncated", true) })
        },

        OpSpec(
            "docs.open", "Open a glasses doc", "Open one document in the glasses reader (first page) so the user can read it.",
            params = listOf(Param.string("doc", "Document id or title", required = true, maxLength = 300)),
            risk = Risk.NAVIGATE, available = ::lockedReason,
        ) {
            val d = doc()
            val list = show(Docs(ui)) as Docs
            val index = ui.data.docs.documents.indexOfFirst { it.id == d.id }
            list.cur = index
            list.rows().getOrNull(index)?.open?.invoke() ?: fail(Codes.FAILED, "Could not open ${d.title}")
            expectTop("\"${d.title}\"") { it.id == "doc" }
            ok("Opened ${d.title}", registry.context())
        },

        OpSpec(
            "docs.scroll", "Page the open doc", "Turn pages in the document open on the glasses (positive = forward, negative = back).",
            params = listOf(Param.integer("pages", "Pages to move, e.g. 1 or -1", required = true, min = -50, max = 50)),
            risk = Risk.NAVIGATE, tool = false,
            available = { ui -> lockedReason(ui) ?: if (reader(ui) == null) "No document is open; call docs_open first" else null },
        ) {
            val r = reader(ui)!!
            val n = int("pages")
            repeat(kotlin.math.abs(n)) { r.key(if (n > 0) "down" else "up") }
            ui.requestRender()
            ok("Page ${r.page + 1}", registry.context())
        },
    )
}
