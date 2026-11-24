package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Memo
import io.lampyr.glasses.data.uploadLabel
import io.lampyr.glasses.ui.Ui
import kotlinx.serialization.json.*

/**
 * Voice memos: start/stop/toggle recording (the memo screen's L tap and the memo shortcut), list, read a transcript.
 * Starting records the room, so it is CALL risk (confirmed outside the glasses conversation, like an assistant voice call);
 * stopping never asks.
 */
object MemoOps : OpProvider {
    private fun startBlocked(ui: Ui) = when {
        ui.data.memos.snapshot.recording -> "A voice memo is already recording"
        // A call owns the microphone (same rule as the memo shortcut).
        ui.data.calls.snapshot.calls.isNotEmpty() -> "Microphone reserved for phone call"
        else -> null
    }

    private fun memoJson(m: Memo, transcript: Boolean = false) = buildJsonObject {
        put("id", m.id); put("title", m.title); put("at", m.at); put("durationSec", m.durationSec)
        put("state", m.uploadLabel)
        if (transcript) put("transcript", clip(m.transcript))
        else if (m.transcript.isNotBlank()) put("transcriptChars", m.transcript.length)
    }

    override fun ops() = listOf(
        OpSpec(
            "memo.list", "List voice memos",
            "Whether a voice memo is recording (and for how long) plus recent memos: id, title, time, length and upload/transcription state.",
            params = listOf(Param.integer("limit", "How many (default 10)", min = 1, max = MAX_ITEMS)),
            risk = Risk.READ,
        ) {
            ui.data.memos.refresh()
            val s = ui.data.memos.snapshot
            ok(data = buildJsonObject {
                put("recording", s.recording)
                if (s.recording) put("recordingSec", (ui.nowS() - s.since).toLong().coerceAtLeast(0))
                s.stoppedReason?.takeIf { !s.recording }?.let { put("stoppedReason", it) }
                put("memos", JsonArray(s.list.take(int("limit", 10)).map { memoJson(it) }))
            })
        },

        OpSpec(
            "memo.read", "Read a voice memo",
            "One memo's transcript (id or title from memo_list). Empty until the gateway has transcribed it.",
            params = listOf(Param.string("memo", "Memo id or title", required = true, maxLength = 300)),
            risk = Risk.READ, tool = false,
        ) {
            val memo = resolve(str("memo"), ui.data.memos.snapshot.list, "memo", "memo_list", { it.id }, { it.title })
            ok(if (memo.transcript.isBlank()) "No transcript yet (${memo.uploadLabel})" else null, memoJson(memo, transcript = true))
        },

        OpSpec(
            "memo.start", "Start a voice memo",
            "Start recording a voice memo with the glasses microphone (as tapping L on the memo screen). Stop it with memo_stop.",
            risk = Risk.CALL, available = ::startBlocked,
        ) {
            val result = ui.data.memos.toggle()
            if (result.ok && !ui.data.memos.snapshot.recording) fail(Codes.FAILED, result.message ?: "The memo did not start")
            done(result, "Memo started")
        },

        OpSpec(
            "memo.stop", "Stop the voice memo",
            "Stop the voice memo that is recording; it is saved and sent for transcription.",
            risk = Risk.ACT, confirm = Confirm.NEVER,
            available = { ui -> if (!ui.data.memos.snapshot.recording) "No voice memo is recording" else null },
        ) { done(ui.data.memos.toggle(), "Memo stopped") },

        OpSpec(
            "memo.toggle", "Start or stop a voice memo",
            "Start a voice memo, or stop the one recording (the memo shortcut).",
            risk = Risk.CALL, tool = false,
            available = { ui -> if (ui.data.memos.snapshot.recording) null else startBlocked(ui) },
        ) {
            val wasRecording = ui.data.memos.snapshot.recording
            done(ui.data.memos.toggle(), if (wasRecording) "Memo stopped" else "Memo started")
        },
    )
}
