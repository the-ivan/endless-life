package com.theivan.endlesslife

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy

/**
 * Base class for Glyph Matrix Toys.
 *
 * Handles:
 * - Service binding contract required by Nothing OS
 * - GlyphMatrixManager lifecycle + correct runtime device registration via Common.getDeviceMatrixLength()
 * - Glyph Button events (short press, long press, touch down/up, AOD)
 *
 * Subclasses only need to implement performOnServiceConnected + the button callbacks.
 *
 * IMPORTANT: Always register the device ID that matches what the manager actually detected.
 * Hard-coding DEVICE_23112 on a non-Phone-3 (e.g. A024 / Phone 2a series) produces the
 * "You are targeting A024 as your device" warning and silent failure (no frames ever appear).
 */
abstract class GlyphMatrixService(private val tag: String) : Service() {

    private val LOG_TAG = "GlyphMatrixService"

    private val buttonHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    msg.data?.getString("data")?.let { event ->
                        when (event) {
                            GlyphToy.EVENT_ACTION_DOWN -> onGlyphTouchDown()
                            GlyphToy.EVENT_ACTION_UP -> onGlyphTouchUp()
                            GlyphToy.EVENT_CHANGE -> onGlyphLongPress()
                            GlyphToy.EVENT_AOD -> onAODEvent()
                        }
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(buttonHandler)

    var glyphMatrixManager: GlyphMatrixManager? = null
        private set

    /**
     * Detected at runtime via Common.getDeviceMatrixLength().
     * 25 = Phone (3)  DEVICE_23112
     * 13 = Phone (4a) Pro DEVICE_25111p
     * Other values = unsupported for this toy.
     */
    var detectedMatrixLength: Int = 25
        protected set

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(component: ComponentName?) {
            val mgr = glyphMatrixManager ?: return

            // Query runtime device matrix length (SDK + community pattern).
            val length = try {
                Common.getDeviceMatrixLength()
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "$tag: Common.getDeviceMatrixLength() failed, assuming 25", t)
                25
            }
            detectedMatrixLength = length

            val deviceConstant = when (length) {
                25 -> Glyph.DEVICE_23112      // Nothing Phone (3)
                13 -> Glyph.DEVICE_25111p     // Nothing Phone (4a) Pro
                else -> Glyph.DEVICE_23112
            }

            mgr.register(deviceConstant)

            if (length != 25) {
                Log.w(LOG_TAG, "$tag: This toy (Endless Life) is built for the 25×25 Glyph Matrix on Phone (3). " +
                        "Current device reports length=$length. Rendering will be disabled.")
            }

            performOnServiceConnected(applicationContext, mgr)
        }

        override fun onServiceDisconnected(component: ComponentName?) {}
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.e(LOG_TAG, ">>> $tag: onBind CALLED <<<")
        GlyphMatrixManager.getInstance(applicationContext)?.let { gmm ->
            glyphMatrixManager = gmm
            gmm.init(callback)
        }
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.e(LOG_TAG, ">>> $tag: onUnbind CALLED <<<")
        performOnServiceDisconnected(applicationContext)
        glyphMatrixManager?.turnOff()
        glyphMatrixManager?.unInit()
        glyphMatrixManager = null
        return false
    }

    /** Called after the GlyphMatrixManager is ready. Start your rendering here. */
    open fun performOnServiceConnected(context: Context, manager: GlyphMatrixManager) {}

    /** Called when the toy is being deactivated. */
    open fun performOnServiceDisconnected(context: Context) {}

    /** User pressed the Glyph Button (short). */
    open fun onGlyphTouchDown() {}

    /** User released the Glyph Button. */
    open fun onGlyphTouchUp() {}

    /** User long-pressed the Glyph Button. */
    open fun onGlyphLongPress() {}

    /**
     * Called roughly every minute while this toy is the active Always-on Glyph Toy
     * and the face-down (Flip to Glyph) conditions are met.
     */
    open fun onAODEvent() {}
}

/** Top-level safe update helper for use in animation objects and other contexts. */
fun safeMatrixUpdate(manager: GlyphMatrixManager?, action: (GlyphMatrixManager) -> Unit) {
    manager ?: return
    try {
        action(manager)
    } catch (e: Exception) {
        Log.w("EndlessLife", "Matrix update failed", e)
    }
}
