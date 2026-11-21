package daidaixiangqi.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ChessApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 在进程创建阶段就固定为浅色：本应用不提供深色模式。
        // 若放到 Activity.onCreate 内再设置，与系统夜间模式不一致时会触发一次 Activity 重建，
        // 表现为首帧亮起后又闪一段纯黑；Application 级设置在 Activity 创建前生效，不会重建。
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
