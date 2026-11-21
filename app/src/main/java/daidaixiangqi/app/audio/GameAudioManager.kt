package daidaixiangqi.app.audio

import android.content.Context

/**
 * 落子 / 吃子音效播放器。
 *
 * 说明：本仓库为「纯净源码版」，不含任何二进制资源（res/raw 下的音效 mp3/wav 未随源码提供），
 * 因此这里不再加载本地音效文件。对外接口与原工程保持一致（静音开关 / 播放 / 释放），
 * 播放为空实现；若需恢复音效，把音效文件放进 res/raw 后，按 TODO 处加载即可。
 */
@Suppress("UNUSED_PARAMETER")
class GameAudioManager(context: Context) {

    private var muted = false

    init {
        // TODO: 补齐 res/raw/move_piece 与 res/raw/capture_piece 后，在此初始化 SoundPool 并加载音效。
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    fun isMuted(): Boolean = muted

    fun playMoveSound() {
        if (muted) return
        // 纯净源码版无音效资源，这里不播放。
    }

    fun playCaptureSound() {
        if (muted) return
        // 纯净源码版无音效资源，这里不播放。
    }

    fun release() {
        // 未持有音频资源，无需释放。
    }
}
