package net.oxog.phonebox.deskpanel

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable

/** The colours of the Phone bridge screens, which are drawn in code: chosen for the system's light or dark mode. */
class BridgeTheme(context: Context) {
    private val density = context.resources.displayMetrics.density
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    val page = if (dark) 0xFF101114.toInt() else 0xFFF3F4F6.toInt()
    val card = if (dark) 0xFF1B1D22.toInt() else 0xFFFFFFFF.toInt()
    val cardStroke = if (dark) 0xFF2A2D34.toInt() else 0xFFE4E6EA.toInt()
    val text = if (dark) 0xFFF1F2F4.toInt() else 0xFF15171A.toInt()
    val muted = if (dark) 0xFF9BA1AB.toInt() else 0xFF5C6370.toInt()
    val accent = 0xFF2F6FED.toInt()
    /** The accent as text on a card: the blue reads at only 3.7:1 on the dark card, a lighter one at 7:1 (4.5:1 is the minimum). */
    val accentText = if (dark) 0xFF7EA6FF.toInt() else accent
    val good = 0xFF2E9B57.toInt()
    val wait = 0xFFE0A100.toInt()
    val bad = 0xFFD9534F.toInt()
    val grey = 0xFF8A8F98.toInt()

    fun dp(v: Int) = (v * density).toInt()

    /**
     * Keeps [view]'s content clear of the status bar, the camera cut-out and the navigation bar (Android 15 draws apps edge to edge),
     * on top of the padding it already has, so nothing sticks to the system's own notifications or buttons.
     */
    @Suppress("DEPRECATION")
    fun keepClearOfSystemBars(view: android.view.View) {
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop, right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
            insets
        }
        view.requestApplyInsets()
    }

    fun roundRect(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    /** A button's shape with the platform's touch ripple on it: a plain shape as background gives no feedback when pressed or focused. */
    fun pressable(shape: android.graphics.drawable.Drawable) =
        android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000), shape, null)

    fun dot(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
}
