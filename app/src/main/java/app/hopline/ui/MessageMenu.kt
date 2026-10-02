package app.hopline.ui

import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap
import app.hopline.R

/**
 * Long-press a message: the chat dims, the message stays lit, a reaction bar springs up from it
 * and the actions sit right beside it — the gesture every chat app has taught thumbs.
 *
 * It lives in the activity's own window (not a dialog), so an open keyboard stays exactly where
 * it is and nothing under the scrim moves while the menu is up. Back, a tap on the scrim, or any
 * choice closes it. While it is up, TalkBack only sees the menu.
 */
class MessageMenu(
    private val activity: AppCompatActivity,
    /** The whole list row — drawn above the scrim so the message itself stays readable. */
    private val row: View,
    /** The bubble — what the bar and the menu line up with. */
    private val bubble: View,
    /** My reaction on this message right now, or null. */
    private val current: String?,
    /** False for messages that can't take reactions (internet answers). */
    private val reactions: Boolean,
    private val actions: List<Action>,
    private val onReact: (String) -> Unit,
    private val onMoreReactions: () -> Unit,
    /** Runs once the overlay is gone, however it closed. */
    private val onClosed: (() -> Unit)? = null,
) {
    class Action(val icon: Int, val label: String, val danger: Boolean = false, val run: () -> Unit)

    private var overlay: FrameLayout? = null
    private var closing = false
    /** The chat's own accessibility setting, put back when the menu closes. */
    private var hiddenContent: View? = null
    private var hiddenWas = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { dismiss() }
    }

    val isShowing: Boolean get() = overlay != null && !closing

    fun show() {
        if (activity.isFinishing || activity.isDestroyed || !bubble.isAttachedToWindow) return
        if (!reactions && actions.isEmpty()) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val root = FrameLayout(ctx).apply {
            isClickable = true; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        // TalkBack announces the menu as it appears (a pane), without yanking focus around.
        ViewCompat.setAccessibilityPaneTitle(root, ctx.getString(R.string.chat_menu_title))
        val scrim = View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.scrim))
            alpha = 0f
            isClickable = true
            contentDescription = ctx.getString(R.string.close_menu)
            setOnClickListener { dismiss() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The message, lifted above the scrim. A bubble taller than the screen isn't worth a
        // bitmap that size — it simply stays under the dim layer.
        val rowLoc = IntArray(2).also { row.getLocationInWindow(it) }
        val snapshot = if (row.width > 0 && row.height in 1..decor.height) {
            try { row.drawToBitmap() } catch (e: Exception) { null }
        } else null
        val lifted = snapshot?.let { bmp ->
            ImageView(ctx).apply {
                setImageBitmap(bmp)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = true   // a tap on the message itself shouldn't close the menu by accident
                x = rowLoc[0].toFloat(); y = rowLoc[1].toFloat()
            }.also { root.addView(it, FrameLayout.LayoutParams(bmp.width, bmp.height, ABSOLUTE)) }
        }

        val bar = if (reactions) buildBar() else null
        val menu = if (actions.isNotEmpty()) buildMenu() else null
        // Invisible until placed: the first frame must not show them parked at the corner.
        bar?.let { it.alpha = 0f; root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, ABSOLUTE)) }
        menu?.let { it.alpha = 0f; root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, ABSOLUTE)) }

        decor.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = root
        activity.onBackPressedDispatcher.addCallback(activity, back)
        // The dimmed chat is not part of the menu: TalkBack must not wander into it.
        activity.findViewById<View>(android.R.id.content)?.let {
            hiddenContent = it
            hiddenWas = it.importantForAccessibility
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        // Place once everything is measured (before the first frame is drawn), then animate in.
        OneShotPreDrawListener.add(root) {
            if (overlay !== root) return@add
            val bounds = safeBounds(decor, dp(8f))
            val bubbleLoc = IntArray(2).also { bubble.getLocationInWindow(it) }
            // Down to the bottom of the row, so the actions never cover the reactions under the bubble.
            val bottom = maxOf(bubbleLoc[1] + bubble.height, rowLoc[1] + row.height - row.paddingBottom)
            val anchor = Rect(bubbleLoc[0], bubbleLoc[1], bubbleLoc[0] + bubble.width, bottom)
            // Line up with whichever edge the bubble hugs — mine or theirs, left-to-right or RTL.
            val alignEnd = anchor.centerX() > (bounds.left + bounds.right) / 2
            place(bar, menu, anchor, bounds, dp(8f), alignEnd)

            scrim.animate().alpha(1f).setDuration(160).start()
            lifted?.let {
                it.pivotX = (anchor.centerX() - rowLoc[0]).toFloat(); it.pivotY = (anchor.centerY() - rowLoc[1]).toFloat()
                it.animate().scaleX(1.02f).scaleY(1.02f).setDuration(160).start()
            }
            bar?.let { b ->
                b.pivotX = if (alignEnd) b.width.toFloat() else 0f
                b.pivotY = if (b.y < anchor.top) b.height.toFloat() else 0f
                b.scaleX = 0.6f; b.scaleY = 0.6f
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(1.4f)).start()
                val strip = (b as ViewGroup).getChildAt(0) as ViewGroup
                for (i in 0 until strip.childCount) {
                    val c = strip.getChildAt(i)
                    c.alpha = 0f; c.translationY = dp(10f).toFloat()
                    c.animate().alpha(1f).translationY(0f).setStartDelay(40L + i * 22L).setDuration(200)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            menu?.let { m ->
                m.pivotX = if (alignEnd) m.width.toFloat() else 0f
                m.pivotY = if (m.y < anchor.top) m.height.toFloat() else 0f
                m.scaleX = 0.92f; m.scaleY = 0.92f
                m.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    /** Close with a short fade; [then] runs after the overlay is gone (so sheets open cleanly). */
    fun dismiss(then: (() -> Unit)? = null) {
        val root = overlay ?: run { then?.invoke(); return }
        if (closing) return
        closing = true
        back.remove()
        root.isClickable = false
        root.animate().alpha(0f).setDuration(120).withEndAction {
            (root.parent as? ViewGroup)?.removeView(root)
            overlay = null
            closing = false
            restoreAccessibility()
            then?.invoke()
            onClosed?.invoke()
        }.start()
    }

    /** Drop it instantly (the activity is going away, or the chat changed under it). */
    fun dismissNow() {
        val root = overlay ?: return
        back.remove()
        root.animate().cancel()
        (root.parent as? ViewGroup)?.removeView(root)
        overlay = null
        closing = false
        restoreAccessibility()
        onClosed?.invoke()
    }

    private fun restoreAccessibility() {
        hiddenContent?.importantForAccessibility = hiddenWas
        hiddenContent = null
    }

    // ------------------------------------------------------------------ building

    private fun buildBar(): View {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        // My current reaction always shows lit in the bar, even if I picked it from the full set.
        val quick = QUICK_REACTIONS.toMutableList()
        if (!current.isNullOrEmpty() && current !in quick) quick[quick.size - 1] = current

        // Fit 6 emoji + "more" on any phone: shrink the cells on a very narrow screen.
        val screenW = ctx.resources.displayMetrics.widthPixels
        val cell = minOf(dp(46f), (screenW - dp(16f) - dp(12f)) / (quick.size + 1))

        val outer = FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_reaction_bar)
            elevation = 8 * density
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            isClickable = true
        }
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        outer.addView(strip)
        for (emoji in quick) {
            val selected = emoji == current
            val v = TextView(ctx).apply {
                text = emoji
                textSize = if (cell < dp(42f)) 23f else 26f
                gravity = Gravity.CENTER
                includeFontPadding = false
                if (selected) background = ContextCompat.getDrawable(ctx, R.drawable.bg_emoji_selected)
                contentDescription = if (selected) ctx.getString(R.string.react_remove_desc, emoji) else ctx.getString(R.string.react_with_desc, emoji)
                setOnClickListener { view ->
                    if (closing) return@setOnClickListener
                    haptic(view)
                    view.animate().scaleX(1.35f).scaleY(1.35f).setDuration(90).withEndAction {
                        dismiss { onReact(if (selected) "" else emoji) }
                    }.start()
                }
            }
            strip.addView(v, LinearLayout.LayoutParams(cell, cell))
        }
        val more = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_add_reaction)
            scaleType = ImageView.ScaleType.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_emoji_more)
            contentDescription = ctx.getString(R.string.more_reactions)
            setOnClickListener { view -> if (!closing) { haptic(view); dismiss { onMoreReactions() } } }
        }
        strip.addView(more, LinearLayout.LayoutParams(cell, cell).apply { marginStart = dp(2f) })
        return outer
    }

    private fun buildMenu(): View {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_menu_card)
            elevation = 8 * density
            setPadding(0, dp(6f), 0, dp(6f))
            minimumWidth = dp(208f)
            isClickable = true
            clipToOutline = true
        }
        for (a in actions) {
            val color = ctx.getColor(if (a.danger) R.color.danger else R.color.text)
            val rowView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48f)
                setPaddingRelative(dp(18f), 0, dp(22f), 0)
                background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { ta ->
                    ta.getDrawable(0).also { ta.recycle() }
                }
                contentDescription = a.label
                setOnClickListener { if (!closing) dismiss { a.run() } }
            }
            rowView.addView(ImageView(ctx).apply {
                setImageResource(a.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22f), dp(22f)))
            rowView.addView(TextView(ctx).apply {
                text = a.label
                textSize = 16f
                setTextColor(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16f) })
            card.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return card
    }

    // ------------------------------------------------------------------ layout

    /** The window area not covered by status/navigation bars or the keyboard, inset by a margin. */
    private fun safeBounds(decor: View, margin: Int): Rect {
        val insets = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
        val l = (insets?.left ?: 0) + margin
        val t = (insets?.top ?: 0) + margin
        val r = decor.width - (insets?.right ?: 0) - margin
        val b = decor.height - (insets?.bottom ?: 0) - margin
        return Rect(l, t, maxOf(l + 1, r), maxOf(t + 1, b))
    }

    /**
     * Prefer the iMessage/Telegram arrangement — reactions just above the message, actions just
     * below it. Messages near the bottom (the newest ones) get both above; messages near the top
     * get both below; a message taller than the screen gets the bar pinned top, actions bottom.
     */
    private fun place(bar: View?, menu: View?, anchor: Rect, bounds: Rect, gap: Int, alignEnd: Boolean) {
        val barH = bar?.height ?: 0
        val menuH = menu?.height ?: 0
        val above = anchor.top - bounds.top
        val below = bounds.bottom - anchor.bottom
        fun x(v: View): Float {
            val want = if (alignEnd) anchor.right - v.width else anchor.left
            return want.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - v.width)).toFloat()
        }
        bar?.x = bar?.let { x(it) } ?: 0f
        menu?.x = menu?.let { x(it) } ?: 0f
        val barSlot = if (bar != null) barH + gap else 0
        val menuSlot = if (menu != null) menuH + gap else 0
        when {
            above >= barSlot && below >= menuSlot -> {
                bar?.y = (anchor.top - gap - barH).toFloat()
                menu?.y = (anchor.bottom + gap).toFloat()
            }
            above >= barSlot + menuSlot -> {
                menu?.y = (anchor.top - gap - menuH).toFloat()
                bar?.y = (anchor.top - menuSlot - gap - barH).toFloat()
            }
            below >= barSlot + menuSlot -> {
                bar?.y = (anchor.bottom + gap).toFloat()
                menu?.y = (anchor.bottom + barSlot + gap).toFloat()
            }
            else -> {
                bar?.y = bounds.top.toFloat()
                menu?.y = (bounds.bottom - menuH).toFloat().coerceAtLeast(bounds.top + barSlot.toFloat())
            }
        }
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        /** Everything in the overlay is placed by window coordinates, so it starts at the top-left
         *  corner in either text direction (START would be the right edge in RTL). */
        @android.annotation.SuppressLint("RtlHardcoded")
        private const val ABSOLUTE = Gravity.TOP or Gravity.LEFT

        /** WhatsApp's classic six — familiar thumbs land without thinking. */
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
        /** Double-tap a bubble to send this one (Instagram/Telegram muscle memory). */
        const val DOUBLE_TAP_REACTION = "❤️"
    }
}
