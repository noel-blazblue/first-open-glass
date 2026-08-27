package com.glass.dining.phone

/**
 * 对话一轮的世代号。新用户句会作废旧 Agent / 旧 TTS。
 */
class TalkTurn {
    @Volatile var seq: Int = 0
        private set
    @Volatile var asking: Boolean = false
        private set

    fun begin(): Int {
        seq += 1
        asking = true
        return seq
    }

    fun isCurrent(id: Int): Boolean = id == seq

    fun finishIfCurrent(id: Int) {
        if (id == seq) {
            asking = false
        }
    }

    fun finishAsking() {
        asking = false
    }

    fun cancel() {
        seq += 1
        asking = false
    }
}
