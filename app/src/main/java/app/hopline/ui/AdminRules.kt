package app.hopline.ui

import app.hopline.mesh.Message
import app.hopline.mesh.RoleChange
import app.hopline.mesh.RoleOp

/**
 * What the screens offer and say about group admins, as plain rules — no Android in here, so the
 * JVM tests can pin them down. Above all: a member is never offered what only an admin can do,
 * nobody is offered something that can only fail, and every change is worded for whoever reads it
 * ("You…" for the one who made it or the one it is about).
 */
object AdminRules {
    enum class MemberAction { MESSAGE, MAKE_ADMIN, DISMISS_ADMIN, VERIFY }

    /**
     * A tap on [target]'s row opens the options only for an admin ([viewerAdmin]); anyone else's
     * tap is the private chat, as always. Never on my own row ([me]), and never for someone from
     * before the update ([ChatRules.listed]).
     */
    fun tapOpensOptions(viewerAdmin: Boolean, target: String, me: String): Boolean =
        viewerAdmin && target != me && ChatRules.listed(target)

    /**
     * In WhatsApp's order: message, the one admin action that applies (if any), verify. Nobody is
     * made admin once they left ([targetLeft]); the one who started the group ([targetFounder]) is
     * never dismissed. An admin who left can be — they still are one, and would be again on rejoin.
     */
    fun memberActions(viewerAdmin: Boolean, targetAdmin: Boolean, targetFounder: Boolean, targetLeft: Boolean): List<MemberAction> = buildList {
        add(MemberAction.MESSAGE)
        if (viewerAdmin) when {
            targetAdmin -> if (!targetFounder) add(MemberAction.DISMISS_ADMIN)
            !targetLeft -> add(MemberAction.MAKE_ADMIN)
        }
        add(MemberAction.VERIFY)
    }

    enum class RoleArea { NONE, PERMISSIONS, ONLY_ADMINS, ONLY_ADMINS_NOBODY }

    /**
     * Group info's row about who can send: an admin's way to change it ([RoleArea.PERMISSIONS]); anyone
     * else sees it only while only admins can send — and is told when no admin has been around
     * ([adminAround]). Nothing while the group is still starting ([ready]).
     */
    fun roleArea(ready: Boolean, meAdmin: Boolean, onlyAdmins: Boolean, adminAround: Boolean): RoleArea = when {
        !ready -> RoleArea.NONE
        meAdmin -> RoleArea.PERMISSIONS
        !onlyAdmins -> RoleArea.NONE
        adminAround -> RoleArea.ONLY_ADMINS
        else -> RoleArea.ONLY_ADMINS_NOBODY
    }

    /**
     * "This phone doesn't know of an admin…": no founder known ([founderKnown]), once someone else
     * is ([othersKnown]) — a new joiner hears the founder within seconds of linking, and a phone
     * alone in its group has nobody to hear it from yet.
     */
    fun noAdminsNote(ready: Boolean, founderKnown: Boolean, othersKnown: Boolean): Boolean = ready && !founderKnown && othersKnown

    enum class Line { YOU_STARTED, YOU_MADE, MADE_YOU, MADE, YOU_STEPPED_DOWN, STEPPED_DOWN, YOU_DISMISSED, DISMISSED_YOU, DISMISSED,
        YOU_ONLY_ADMINS, ONLY_ADMINS, YOU_EVERYONE, EVERYONE, UNSEEN }

    /**
     * The words a role line gets on the phone of [me]: [kind] is its Message kind, [actor] its from
     * (whoever made the change), [text] whom it is about or what was set. Null: not shown — a line
     * this phone never makes for anyone else, or a setting this version has no words for.
     */
    fun line(kind: String, actor: String, text: String, me: String): Line? = when (kind) {
        Message.ROLE_STARTED -> if (actor == me) Line.YOU_STARTED else null
        Message.ROLE_ADMIN -> when { actor == me -> Line.YOU_MADE; text == me -> Line.MADE_YOU; else -> Line.MADE }
        Message.ROLE_DISMISSED -> when {
            actor == text -> if (actor == me) Line.YOU_STEPPED_DOWN else Line.STEPPED_DOWN
            actor == me -> Line.YOU_DISMISSED
            text == me -> Line.DISMISSED_YOU
            else -> Line.DISMISSED
        }
        Message.ROLE_SEND -> when (text) {
            RoleOp.ADMINS -> if (actor == me) Line.YOU_ONLY_ADMINS else Line.ONLY_ADMINS
            RoleOp.ALL -> if (actor == me) Line.YOU_EVERYONE else Line.EVERYONE
            else -> null
        }
        Message.ROLE_UNSEEN -> if (actor == me) Line.UNSEEN else null
        else -> null
    }

    enum class Told { SILENT, NOT_READY, NOT_ADMIN, CREATOR, LEFT, FULL }

    /**
     * What a refused admin change says. Done, already so, or about another group: nothing. No
     * else: a new reason must be decided here.
     */
    fun told(why: RoleChange): Told = when (why) {
        RoleChange.DONE, RoleChange.UNCHANGED, RoleChange.ELSEWHERE -> Told.SILENT
        RoleChange.STARTING -> Told.NOT_READY
        RoleChange.NOT_ADMIN -> Told.NOT_ADMIN
        RoleChange.CREATOR -> Told.CREATOR
        RoleChange.NOT_MEMBER -> Told.LEFT
        RoleChange.FULL -> Told.FULL
    }
}
