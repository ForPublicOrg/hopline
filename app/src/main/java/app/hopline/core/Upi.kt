package app.hopline.core

/**
 * Paying by UPI when there is no internet. Every UPI app needs data; the one way to pay any UPI
 * ID over plain phone signal is NPCI's *99# menu, and Android gives an app no way to answer a
 * USSD menu (an accessibility service could, and that alone gets a sideloaded app blocked by Play
 * Protect). The *99# box asks for the UPI ID and the amount itself, so Hopline asks for neither:
 * it explains the steps and opens the Phone app on one of two fixed codes. The person presses
 * call, types the UPI ID, sees the payee's name as their bank has it, and types the amount and
 * then their UPI PIN — into the carrier's box, never into Hopline.
 *
 * Nothing but [CODE_MENU] and [CODE_SEND] is ever dialled ([dialable]). No Android in here, so the
 * JVM tests can pin it down.
 */
object Upi {
    const val CODE_MENU = "*99#"        // the full menu, and first-time setup
    const val CODE_SEND = "*99*1*3#"    // straight to Send Money > UPI ID

    /**
     * May [code] be put in the Phone app? Only the two codes above, character for character: a
     * code that slipped in "*401*<number>#" would forward this phone's calls to a stranger.
     */
    fun dialable(code: String): Boolean = code == CODE_MENU || code == CODE_SEND

    // ------------------------------------------------------------------ which phones it works on

    // Reliance Jio's network codes (MCC 405): 840, and 854 to 874.
    private val JIO = setOf("405840") + (854..874).map { "405$it" }

    /**
     * Is this SIM on Jio, where *99# doesn't work at all? By the operator's name, or by its network
     * code ([mccMnc], as Android gives it: "405857"). Not knowing is not Jio — the person can still
     * try, and the carrier will say if it can't.
     */
    fun jio(operatorName: String?, mccMnc: String?): Boolean =
        operatorName?.lowercase()?.contains("jio") == true || mccMnc != null && mccMnc.trim() in JIO

    /**
     * Should Pay without internet be offered at all? *99# is Indian. The SIM's country decides
     * when the SIM says one ("in", any case); when it doesn't, it is offered unless the phone is
     * plainly on a network abroad.
     */
    fun offered(simCountry: String?, networkCountry: String?): Boolean {
        val sim = simCountry?.trim()?.lowercase().orEmpty()
        val net = networkCountry?.trim()?.lowercase().orEmpty()
        return if (sim.isNotEmpty()) sim == "in" else net.isEmpty() || net == "in"
    }
}
