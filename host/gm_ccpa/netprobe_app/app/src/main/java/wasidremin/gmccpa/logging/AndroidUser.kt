package wasidremin.gmccpa.logging

import android.content.Context
import android.os.Process
import android.os.UserManager
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android user id and unlock state for the car-test lines.
 *
 * `UserHandle.myUserId()` is `@hide` in the API 32 jar this app compiles against. The framework
 * method is `uid / PER_USER_RANGE` (100000); the hidden method is called when the runtime exposes
 * it so the logged id is that value.
 */
object AndroidUser {
    private val noted = AtomicBoolean(false)
    @Volatile private var app: Context? = null

    fun id(): Int {
        val reflected = runCatching {
            val m = android.os.UserHandle::class.java.getMethod("myUserId")
            m.invoke(null) as Int
        }.getOrNull()
        return reflected ?: (Process.myUid() / 100_000)
    }

    fun unlocked(ctx: Context): Boolean =
        (ctx.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

    fun unlockedToken(): String {
        val c = app ?: return "unknown"
        return unlocked(c).toString()
    }

    fun line(ctx: Context): String = "user=${id()} unlocked=${unlocked(ctx)}"

    /** Once per process, at the first activity that runs. */
    fun noteProcess(ctx: Context) {
        app = ctx.applicationContext
        if (!noted.compareAndSet(false, true)) return
        ProbeLog.sub("user").i("process start ${line(ctx)}")
    }
}
