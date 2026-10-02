package app.hopline.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * The chat composer. Also says when the cursor moves without the text changing, so the @mention
 * suggestions follow the cursor instead of offering to complete a word it already left.
 */
class ChatInput(ctx: Context, attrs: AttributeSet?) : AppCompatEditText(ctx, attrs) {
    /** Null while the superclass constructor runs (it moves the selection before we are built). */
    var onSelectionMoved: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionMoved?.invoke()
    }
}
