package app.hopline.core

import org.junit.Assert.*
import org.junit.Test

/** What Pay without internet may dial — only *99# itself — and which phones it is offered on. */
class UpiTest {
    // ---------------------------------------------------------------- what may be dialled

    @Test fun `only the two fixed codes may ever be dialled`() {
        assertTrue(Upi.dialable(Upi.CODE_MENU)); assertTrue(Upi.dialable(Upi.CODE_SEND))
        assertTrue(Upi.dialable("*99#")); assertTrue(Upi.dialable("*99*1*3#"))
        for (no in listOf("*99*1*3", "*401*9876543210#", "**21*9876543210#", "*99#*401#", " *99#", "*99# ", "*99 #", "* 99*1*3#", "*99*1*3 #", "",
            "#", "*99*1*3#9876543210", "*99*1*3##", "tel:*99#", "*٩٩#", "*99*1*3#\n", "*99*1*4#"))
            assertFalse("\"$no\"", Upi.dialable(no))
    }

    // ---------------------------------------------------------------- which phones

    @Test fun `Jio is known by its name or its network code`() {
        val yes: List<Pair<String?, String?>> = listOf("Jio 4G" to null, "JIO" to "", "Reliance Jio" to "40445", "jio" to null,
            null to "405840", null to "405854", "" to "405863", null to "405874", null to " 405857 ")
        for ((name, code) in yes) assertTrue("$name $code", Upi.jio(name, code))
        val no: List<Pair<String?, String?>> = listOf("Airtel" to "40445", "Vi India" to "40420", "BSNL Mobile" to "40471", "MTNL" to "40469",
            null to "405853", null to "405875", null to "405841", null to null, "" to "", " " to " ", null to "40584", null to "4058400")
        for ((name, code) in no) assertFalse("$name $code", Upi.jio(name, code))
    }

    @Test fun `it is offered on an Indian SIM, or when nothing says the phone is abroad`() {
        val table: List<Triple<String?, String?, Boolean>> = listOf(
            Triple("in", null, true), Triple("IN", "us", true), Triple(" in ", "", true), Triple("us", "in", false), Triple("np", null, false),
            Triple(null, null, true), Triple("", "", true), Triple(null, "in", true), Triple(" ", "IN ", true), Triple(null, "us", false), Triple("", " AE ", false))
        for ((sim, net, offered) in table) assertEquals("sim=$sim net=$net", offered, Upi.offered(sim, net))
    }
}
