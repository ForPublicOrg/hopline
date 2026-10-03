package app.hopline.ui

import app.hopline.data.SavedGroup

/**
 * Which screen a phone belongs on, as plain rules — no Android in here, so the JVM tests can pin
 * them down. The launch screen and Home's own guard both ask here, because the two must agree: if
 * Home sent a phone to the launch screen and the launch screen sent it straight back, the app
 * would spin between them.
 *
 * What the rules lean on: a phone can be IN a group (one is on the radio, or will be once it is
 * allowed), or only HAVE groups — ones it left, whose chats are kept to read. Reading needs no
 * radio and no permission, so such a phone belongs on Home, never on the join screen (its chats
 * would be hidden behind it) and never on the permissions screen (Android takes permissions back
 * from an app left unopened for months — exactly the phone that returns to look at an old chat).
 */
object ScreenRules {
    enum class Screen { WELCOME, PERMISSIONS, JOIN, HOME }

    /**
     * Where the launch screen sends a phone. [named]: it has a name. [inGroup]: a group is set for
     * the radio. [anySaved]: any group is saved at all, left ones included. [radioReady]: the
     * permissions are granted and their screen was gone through.
     */
    fun launch(named: Boolean, inGroup: Boolean, anySaved: Boolean, radioReady: Boolean): Screen = when {
        !named -> Screen.WELCOME
        inGroup -> if (radioReady) Screen.HOME else Screen.PERMISSIONS
        anySaved -> Screen.HOME
        else -> if (radioReady) Screen.JOIN else Screen.PERMISSIONS
    }

    /**
     * Must Home hand over to the launch screen? With no group saved at all it has nothing to show;
     * with a group to run and no permission to run it, the permissions must be asked for. A phone
     * that only has groups it left stays, granted or not.
     */
    fun homeNeedsLaunch(inGroup: Boolean, anySaved: Boolean, granted: Boolean): Boolean = !anySaved || (inGroup && !granted)

    enum class Radio { GO, ASK_PERMISSIONS }

    /**
     * A group is about to be put on the radio — rejoined, started or joined — from a screen a
     * phone can reach without the radio's permissions (Home, an old chat, the join screen, on a
     * phone that only has groups it left). [granted]: the permissions are there. If they are not,
     * they are asked for BEFORE the group is saved, never after: once a group is set for the radio
     * with no permission to run it, Home hands over to the permissions screen every time, and
     * every kept chat on the phone is stuck behind a question that has only one way out.
     */
    fun beforeRadio(granted: Boolean): Radio = if (granted) Radio.GO else Radio.ASK_PERMISSIONS

    /**
     * "Reply privately" has just opened the private chat: does the reply still have to be pointed
     * at the message that was [picked]? Opening the chat points it there itself only when the
     * router can look the message up — and brings back the chat's saved draft, reply and all,
     * first. So the bar may show nothing ([current] null), or the draft's old target: anything
     * but the picked message means it is set now. The person chose that message, a moment ago.
     */
    fun replyStillToSet(current: String?, picked: String): Boolean = current != picked

    enum class Entered { NEW, OPEN, SWITCH, REJOIN }

    /**
     * What a group code means on this phone when it is typed, scanned or tapped as a link. [saved]
     * is the saved group with that code, if there is one; [activeCode] the group on the radio.
     * A code never joins twice — and a group that was LEFT is never walked back into as if it
     * were new, or woken like a paused one: rejoining is asked about, by name.
     */
    fun codeEntered(saved: SavedGroup?, activeCode: String?): Entered = when {
        saved == null -> Entered.NEW
        saved.left -> Entered.REJOIN
        saved.code == activeCode -> Entered.OPEN
        else -> Entered.SWITCH
    }

    enum class After { STAY, HOME, LAUNCH }

    /**
     * Where to go once a left group has been deleted. Home ([onHome]) only listed it, and redraws
     * without it; any other screen was showing the group itself — its info, its chat — and gives
     * way to a fresh Home. With no group saved any more there is nothing for Home either: the
     * launch screen leads to "Start or join".
     */
    fun afterDelete(onHome: Boolean, anySaved: Boolean): After = when {
        !anySaved -> After.LAUNCH
        onHome -> After.STAY
        else -> After.HOME
    }
}
