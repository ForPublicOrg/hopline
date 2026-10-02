@file:Suppress("DEPRECATION")   // PhoneStateListener is the only way to hear about service on Android 8–11

package app.hopline.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Does this phone have mobile service right now — enough to send a text, even with no data?
 * In the hills that is the common case on the one phone that reaches the ridge: a bar of 2G that
 * carries SMS but never loads a page. Listening for service-state changes needs no permission
 * (location details are simply redacted); the SMS app's own send result is the final truth.
 */
object Cell {
    private const val TAG = "Hopline/Cell"
    @Volatile private var state: Int = -1          // last ServiceState.state we heard, -1 = unknown
    private var registered = false
    private var callback: Any? = null
    var onChange: (() -> Unit)? = null

    fun start(ctx: Context) {
        if (registered) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                    override fun onServiceStateChanged(ss: ServiceState) { update(ss.state) }
                }
                val main = Executor { r -> Core.handler.post(r) }
                tm.registerTelephonyCallback(main, cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onServiceStateChanged(ss: ServiceState?) { ss?.let { update(it.state) } }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_SERVICE_STATE)
                callback = l
            }
            registered = true
        } catch (e: Exception) {
            // Some OEM builds guard this; fall back to the SIM/airplane check below.
            Log.w(TAG, "service-state listener unavailable", e)
        }
    }

    private fun update(s: Int) {
        if (s == state) return
        state = s
        Core.handler.post { onChange?.invoke() }
    }

    /** True when a text could plausibly go out: a ready SIM, not in airplane mode, and in service. */
    fun canText(ctx: Context): Boolean {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        if (!ctx.packageManager.hasSystemFeature("android.hardware.telephony")) return false
        val airplane = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (e: Exception) { false }
        if (airplane) return false
        val sim = try { tm.simState == TelephonyManager.SIM_STATE_READY } catch (e: Exception) { false }
        if (!sim) return false
        return when (state) {
            ServiceState.STATE_IN_SERVICE -> true
            -1 -> true    // never heard: a ready SIM outside airplane mode is our best guess
            else -> false
        }
    }
}
