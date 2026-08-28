package com.glass.dining.phone

/**
 * 听写定稿先完整上屏，再交给 Agent。NLS 末字常只出现在 SentenceEnd，立刻切「思考中」会看不见。
 */
class HeardAskGate(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancelDelayed: (Runnable) -> Unit,
    private val showHeard: (String) -> Unit,
    private val commit: (String) -> Unit,
    private val holdMs: Long = HOLD_MS,
) {
    @Volatile private var pending: String = ""
    private val fire = Runnable {
        val text = pending
        pending = ""
        if (text.isNotBlank()) commit(text)
    }

    fun onPartial(text: String) {
        val heard = text.trim()
        if (heard.isBlank()) return
        showHeard(heard)
    }

    fun onFinal(text: String) {
        val heard = text.trim()
        if (heard.isBlank()) return
        pending = heard
        showHeard(heard)
        cancelDelayed(fire)
        postDelayed(fire, holdMs)
    }

    fun cancel() {
        pending = ""
        cancelDelayed(fire)
    }

    companion object {
        const val HOLD_MS = 500L
    }
}
