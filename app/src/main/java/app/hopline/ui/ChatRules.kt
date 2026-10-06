package app.hopline.ui

import app.hopline.core.Crypto
import app.hopline.mesh.Attachment

/**
 * What a chat screen shows at its top and in place of its composer, as plain rules — no Android
 * in here, so the JVM tests can pin them down.
 *
 * Since 2.4 a private chat is sealed for the other phone's own key. A chat whose other phone this
 * one has no key for can't be written in at all — nothing would go out — so the screen never
 * pretends it can: no composer, no attach, no voice, no place, no reactions, no "Send again",
 * just one line saying why. Three reasons, three lines:
 *  - the chat is from before the update: its other person had an 8-letter id, which no phone has
 *    any more (they came back with a new one, and nothing proves which), so it is history only;
 *  - the person told the group they left it: their phone no longer hears the group, so nothing
 *    reaches them — if they join again, their hello brings the composer back;
 *  - the person is from after the update, but their key never reached this phone (they haven't
 *    been heard from since this phone learned of them) — once their phone has been in range, it
 *    can be written in.
 */
object ChatRules {
    enum class Chip { GROUP, PRIVATE, NONE }

    /**
     * The one line at the top of a chat, never saved and never a message: the group chat is
     * sealed with the group's key, a private chat for the two phones. A left group's kept chat gets
     * none (it may be all from before the update, and nothing new will be added to it), and nor does
     * a private chat from before the update ([peer] not a 2.4 id): those messages were never
     * sealed for the two of them.
     */
    fun chip(readOnly: Boolean, peer: String?): Chip = when {
        readOnly -> Chip.NONE
        peer == null -> Chip.GROUP
        Crypto.isNodeId(peer) -> Chip.PRIVATE
        else -> Chip.NONE
    }

    /**
     * [LEFT]: this phone left the group (its kept chat). [PEER_LEFT]: the other person of a private chat did.
     * [ADMINS_ONLY]: the group chat, while only admins can send and this phone isn't one.
     */
    enum class Bottom { COMPOSER, LEFT, FROM_BEFORE, NOT_SEEN, PEER_LEFT, ADMINS_ONLY }

    /**
     * What sits where the composer would be. [canWrite]: the router can seal for [peer]
     * (Router.canWriteTo); for the group chat it is not asked. [peerLeft]: [peer] told the group
     * they left it — said in so many words, not as "hasn't been seen", whatever [canWrite] says.
     * [canPost]: may this phone post in the group chat now (Router.mayPost). Asked only for the
     * group chat: private chats stay open to someone who may not post in the group, which is the
     * point of them. No default — every caller decides.
     */
    fun bottom(readOnly: Boolean, peer: String?, canWrite: Boolean, peerLeft: Boolean, canPost: Boolean): Bottom = when {
        readOnly -> Bottom.LEFT
        peer == null -> if (canPost) Bottom.COMPOSER else Bottom.ADMINS_ONLY
        !Crypto.isNodeId(peer) -> Bottom.FROM_BEFORE
        peerLeft -> Bottom.PEER_LEFT
        canWrite -> Bottom.COMPOSER
        else -> Bottom.NOT_SEEN
    }

    /**
     * May a message of mine that stopped trying ([gaveUp]) be offered "Send again"? Not when it was
     * private ([to]) and nothing can be sealed for that person ([canWrite]), nor when it was for the
     * group and this phone may not post there now ([canPost]): either would only fail.
     */
    fun sendAgain(gaveUp: Boolean, to: String?, canWrite: Boolean, canPost: Boolean): Boolean =
        gaveUp && (if (to == null) canPost else canWrite)

    /**
     * What the composer holds once its chat's saved draft changed while the screen showed the chat: [stored] is the
     * draft now, [seen] the draft as the screen last read or wrote it, [typed] what the composer holds. A reply typed
     * into a notification that couldn't go is put after the draft (Core.keepAsDraft, while the group was starting):
     * it joins what is typed, after it, the way keepAsDraft puts it. Null when nothing was added that way — then the
     * composer stays as it is, and what is typed is never traded for the older words on disk.
     */
    fun withKeptWords(typed: String, seen: String, stored: String): String? {
        if (stored == seen) return null
        val added = when {
            seen.isBlank() -> stored
            stored.startsWith("$seen\n") -> stored.substring(seen.length + 1)
            else -> return null
        }
        return if (added.isBlank()) null else listOfNotNull(typed.ifBlank { null }, added).joinToString("\n")
    }

    /**
     * Is "Reply privately" worth offering on a group message from [from]? Only for a 2.4 id, and
     * not once they left the group ([left]): either would open a private chat that can't be written in.
     */
    fun replyPrivately(from: String, left: Boolean): Boolean = Crypto.isNodeId(from) && !left

    /** Who belongs in a list of people to pick (People, the members, the @ picker): 2.4 ids only. */
    fun listed(id: String): Boolean = Crypto.isNodeId(id)

    /** Who can be called out with @: someone [listed] who hasn't left the group ([left]) — nobody there would hear it. */
    fun mentionable(id: String, left: Boolean): Boolean = listed(id) && !left

    /**
     * A file from before the update that isn't on this phone: it has no key of its own (every file
     * since has one, Attachment.sealable), its pieces went with the old format, and no phone sends
     * those any more. It will never arrive — to be said at once, not after two days of
     * "Receiving… 0 of N".
     */
    fun lostInUpdate(att: Attachment): Boolean = !Attachment.sealable(att)
}
