package app.hopline.data

import org.json.JSONException

/**
 * The plain rules around a group's state file — the one file that holds a whole chat — for the
 * moments it can be lost by doing things in the wrong order, or by giving up on it too soon. No
 * Android in here, so the JVM tests can pin them down; Store and Core supply the files and threads.
 */
object StateRules {
    enum class Failed { RETRY, ASIDE }

    /**
     * A state file would not read ([earlier] = how many tries failed before this one). Setting it
     * aside is for good — nothing in the app reads a file back once it is set aside — so it takes
     * proof that the file itself is bad, and only its content can prove that: text that isn't
     * JSON. Anything else (the storage answered with an error, the phone ran out of memory half
     * way) may be gone a moment later, and gets one more try before the file is given up on.
     */
    fun onReadFailure(t: Throwable, earlier: Int): Failed =
        if (t is JSONException || earlier >= 1) Failed.ASIDE else Failed.RETRY

    /**
     * The same for a state that read fine but would not restore into a router. An exception there
     * is the content's doing and will happen again; an error (out of memory) may not.
     */
    fun onRestoreFailure(t: Throwable, earlier: Int): Failed =
        if (t is Exception || earlier >= 1) Failed.ASIDE else Failed.RETRY

    /**
     * Leaving the group on the radio. Its router holds the only complete copy of the chat — what
     * was said since the last save is nowhere else — and leaving lets that router go. So the whole
     * chat goes to disk FIRST ([saveWhole] says whether it got there), and only then is anything
     * let go ([letGo]). If it didn't get there, nothing happens at all: the phone stays in the
     * group, its router keeps trying to save, and the person is told. False when the leave was
     * refused.
     */
    fun leave(saveWhole: () -> Boolean, letGo: () -> Unit): Boolean {
        if (!saveWhole()) return false
        letGo()
        return true
    }

    /**
     * "Delete for me" in the kept chat of a group that was left. The state without the messages is
     * written first; [saved] says whether it reached the disk. Only then do their files and their
     * copies in the history go ([cleanUp]) — those can't be brought back. If it didn't, the state
     * on disk still has the messages, so nothing of theirs is deleted and the chat is shown again
     * as the disk has it ([putBack]): a message must not come back later without its photo.
     */
    fun deleteKept(saved: Boolean, cleanUp: () -> Unit, putBack: () -> Unit) {
        if (saved) cleanUp() else putBack()
    }
}
