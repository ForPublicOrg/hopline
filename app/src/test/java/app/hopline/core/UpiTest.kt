package app.hopline.core

import app.hopline.core.Upi.AmountFlaw
import app.hopline.core.Upi.Flaw
import app.hopline.core.Upi.Payee
import app.hopline.core.Upi.Recent
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** What Hopline reads from a shop's UPI code and gets ready for *99#, from text alone — and that nothing it reads is ever dialled. */
class UpiTest {
    /** The payee [text] names; the test fails, saying why, when the code was refused. */
    private fun payee(text: String): Payee {
        val r = Upi.read(text)
        assertNull("${text.take(60)} was refused", r.flaw)
        return r.payee!!
    }

    private fun flaw(text: String): Flaw? = Upi.read(text).flaw

    // ---------------------------------------------------------------- upi://pay links

    @Test fun `a plain shop link gives the UPI ID, the name it claims, and no amount`() {
        assertEquals(Payee("sharmastores@okaxis", "Sharma General Stores", 0, 0, fixed = false, oneBill = false),
            payee("upi://pay?pa=sharmastores@okaxis&pn=Sharma%20General%20Stores&cu=INR"))
    }

    @Test fun `capitals are fine in the scheme, the host and the keys, and the ID keeps its case`() {
        val p = payee("UPI://PAY?PA=Sharma.Stores@OKAXIS&PN=SHARMA&AM=50&CU=inr")
        assertEquals("Sharma.Stores@OKAXIS", p.id); assertEquals("SHARMA", p.name); assertEquals(5000L, p.paise)
        assertEquals("a path after the host changes nothing", "a@ybl", payee("upi://pay/?pa=a@ybl").id)
    }

    @Test fun `the name is decoded the way UPI apps decode it`() {
        fun name(pn: String) = payee("upi://pay?pa=a@ybl&pn=$pn").name
        assertEquals("Sharma Stores", name("Sharma%20Stores"))
        assertEquals("Sharma Stores", name("Sharma+Stores"))
        assertEquals("a bare % is a space, as NPCI asks", "Sharma Stores", name("Sharma%Stores"))
        assertEquals("100 Pure Veg", name("100%%20Pure%20Veg"))
        assertEquals("Café Delhi", name("Caf%C3%A9%20Delhi"))
        assertEquals("राम किराना", name(java.net.URLEncoder.encode("राम किराना", "UTF-8")))
        assertEquals("a broken byte is a mark, not an exception", "A\uFFFDB", name("A%FFB"))
        assertEquals("nothing invisible survives", "Evilshop", name("Evil%E2%80%AEshop%0A"))
        assertEquals(60, name("x".repeat(100)).length)
    }

    @Test fun `a name that would pass for a UPI ID is no name, so the ID really paid is shown`() {
        fun name(pn: String) = payee("upi://pay?pa=thief@ybl&pn=$pn").name
        assertEquals("", name("sharmastores@okaxis"))
        assertEquals("", name("Sharma%20sharma%40okaxis"))
        assertEquals("", name("sharma@okaxis%20(verified)"))
        assertEquals("a full-width @", "", name("sharma%EF%BC%A0okaxis"))
        assertEquals("a small @", "", name("sharma%EF%B9%ABokaxis"))
        assertEquals("Sharma Stores", name("Sharma%20Stores"))
        assertEquals("in a Bharat QR too", "", payee(bharat(field("01", "11"), upi("thief@ybl"), field("53", "356"), field("59", "shop@okaxis"))).name)
        assertEquals("and in what was saved before", "", Upi.recents("""[{"id":"thief@ybl","name":"shop@okaxis","paise":100,"at":1}]""").single().name)
    }

    @Test fun `a name with nothing visible in it is no name`() {
        fun name(pn: String) = payee("upi://pay?pa=a@ybl&pn=$pn").name
        for (blank in listOf("%E2%80%8B", "%E2%81%A0", "%C2%AD", "%E3%85%A4", "%E2%80%8D", "%E2%A0%80", "%E2%80%8B%20%E2%80%8B", "%E1%85%9F%E1%85%A0", "%EF%BE%A0"))
            assertEquals(blank, "", name(blank))
        assertEquals("a real name with a joiner in it stays", "क्‍ष", name(java.net.URLEncoder.encode("क्‍ष", "UTF-8")))
        assertEquals("an emoji is something to see", "☕", name("%E2%98%95"))
    }

    @Test fun `a value may hold an = sign, and empty pieces are skipped`() {
        assertEquals("A=B", payee("upi://pay?pa=a@ybl&pn=A=B").name)
        assertEquals(payee("upi://pay?pa=a@ybl&pn=Shop&am=10"), payee("upi://pay?&&pa=a@ybl&&pn=Shop&&am=10&"))
    }

    @Test fun `mode, sign and the rest decide nothing, raw + = and slashes included`() {
        val plain = payee("upi://pay?pa=shop@okicici&pn=Shop&am=10")
        assertEquals(plain, payee("upi://pay?pa=shop@okicici&pn=Shop&am=10&mode=02&orgid=159761&sign=MEYCIQC+8x/Ab+c/d==&QRexpire=2026-10-03T17:48:19+05:30"))
        assertEquals(plain, payee("upi://pay?pa=shop@okicici&pn=Shop&am=10&mode=19&ver=01&qrMedium=02&mc=5411&gstBrkUp=GST:16.5|CGST:08.25&invoiceNo=1&aid=x"))
    }

    @Test fun `a real one-bill code from PayU is read, and marked as made for one bill`() {
        val p = payee("upi://pay?pa=gauravdua1.payu@indus&pn=smsplus&mc=7399&tr=DYQ13845198863&ver=01&mode=15&orgid=000000&qrMedium=06&cu=INR&purpose=02&pinCode=122002&am=1.00&QRexpire=2021-08-20T17:48:19+05:30")
        assertEquals(Payee("gauravdua1.payu@indus", "smsplus", 100, 0, fixed = true, oneBill = true), p)
        assertFalse("no reference, no one bill", payee("upi://pay?pa=a@ybl&am=10&tr=").oneBill)
        assertFalse("a reference with no amount is an ordinary shop code", payee("upi://pay?pa=a@ybl&tr=ABC123").oneBill)
    }

    @Test fun `the amount a link carries is read exactly, or not at all`() {
        fun am(a: String) = payee("upi://pay?pa=a@ybl&am=$a")
        assertEquals(25000L, am("250").paise); assertEquals(25050L, am("250.5").paise); assertEquals(25050L, am("250.50").paise)
        assertEquals(25050L, am("250.500").paise); assertEquals(25050L, am("250%2E50").paise)
        assertTrue(am("250").fixed)
        // Not an amount: the person types one, as on a code with none.
        for (none in listOf("0", "0.00", "abc", "1,000", "250.555", "٢٥٠", "", "+250", "250+")) {
            assertEquals("am=$none", 0L, am(none).paise); assertFalse("am=$none", am(none).fixed)
        }
    }

    @Test fun `a least amount means the person may pay more, so the amount is not fixed`() {
        val p = payee("upi://pay?pa=a@ybl&am=500&mam=100")
        assertEquals(50000L, p.paise); assertEquals(10000L, p.floor); assertFalse(p.fixed)
        for (none in listOf("null", "NULL", "", "0", "abc")) {
            val q = payee("upi://pay?pa=a@ybl&am=500&mam=$none")
            assertEquals("mam=$none", 0L, q.floor); assertTrue("mam=$none", q.fixed)
        }
        assertEquals("a least amount alone", Payee("a@ybl", "", 0, 10000, fixed = false, oneBill = false), payee("upi://pay?pa=a@ybl&mam=100"))
    }

    @Test fun `a UPI code that doesn't pay someone is refused as such`() {
        for (code in listOf("upi://mandate?pa=a@ybl&am=100&recur=MONTHLY", "upi://collect?pa=a@ybl&am=100", "upi:pay?pa=a@b", "upi://payment?pa=a@ybl", "upi:///pay?pa=a@ybl", "upi://"))
            assertEquals(code, Flaw.NOT_PAY, flaw(code))
    }

    @Test fun `another currency, or a kind of payment the USSD menu can't make, is refused`() {
        assertEquals(Flaw.FOREIGN, flaw("upi://pay?pa=a@ybl&am=10&cu=USD"))
        assertEquals(Flaw.FOREIGN, flaw("upi://pay?pa=a@ybl&cu=usd"))
        assertNull(flaw("upi://pay?pa=a@ybl&cu=inr")); assertNull(flaw("upi://pay?pa=a@ybl&cu="))
        for (special in listOf("12", "41", "11", "011", "2a", "٠٢", "-1", "99", "1.0"))
            assertEquals("purpose=$special", Flaw.SPECIAL, flaw("upi://pay?pa=a@ybl&purpose=$special"))
        for (everyday in listOf("00", "0", "02", "10", ""))
            assertNull("purpose=$everyday", flaw("upi://pay?pa=a@ybl&purpose=$everyday"))
    }

    @Test fun `a link with no UPI ID to pay is refused`() {
        for (code in listOf("upi://pay?pn=Shop&am=10", "upi://pay?pa=&pn=Shop", "upi://pay?pa=shop.okaxis", "upi://pay?pa=shop@", "upi://pay?pa", "upi://pay", "upi://pay?"))
            assertEquals(code, Flaw.NO_ID, flaw(code))
    }

    @Test fun `two payees or two amounts in one code is a trick, not a typo`() {
        assertEquals(Flaw.BROKEN, flaw("upi://pay?pa=shop@ybl&pn=Shop&pa=thief@ybl"))
        assertEquals(Flaw.BROKEN, flaw("upi://pay?pa=shop@ybl&am=10&am=1000"))
        assertEquals("a key's case doesn't hide it", Flaw.BROKEN, flaw("upi://pay?pa=shop@ybl&PA=thief@ybl"))
        assertEquals("the same payee twice is only a sloppy code", "shop@ybl", payee("upi://pay?pa=shop@ybl&pn=Shop&pa=shop@ybl").id)
        assertEquals("the same payee, written two ways", "shop@ybl", payee("upi://pay?pa=shop@ybl&pa=shop%40ybl").id)
        assertEquals("the same amount twice, likewise", 1000L, payee("upi://pay?pa=shop@ybl&am=10&am=10").paise)
        assertEquals("anything else twice: the first wins", "First", payee("upi://pay?pa=a@ybl&pn=First&pn=Second").name)
    }

    @Test fun `a code can't smuggle a USSD code in as its UPI ID`() {
        for (pa in listOf("*99#", "%2A99%23", "a@ybl*401*9876543210#", "a@ybl%23", "%2A401%2A9876543210%23@ybl", "a@ybl%0A*99%23", "a@ybl%20*99%23"))
            assertEquals(pa, Flaw.NO_ID, flaw("upi://pay?pa=$pa"))
    }

    @Test fun `decoding by hand - a plus is a space only in the name, a bare percent is a space, bytes are UTF-8`() {
        assertEquals("a b", Upi.decode("a+b", plusIsSpace = true))
        assertEquals("a+b", Upi.decode("a+b", plusIsSpace = false))
        assertEquals("2021-08-20T17:48:19+05:30", Upi.decode("2021-08-20T17:48:19+05:30", false))
        assertEquals("AB", Upi.decode("%41%42", false)); assertEquals("₹", Upi.decode("%e2%82%b9", false))
        assertEquals("100 ", Upi.decode("100%", false)); assertEquals(" zz", Upi.decode("%zz", false)); assertEquals("A 2", Upi.decode("A%2", false))
        assertEquals(" A", Upi.decode("%%41", false))
        assertEquals("\uFFFD", Upi.decode("%FF", false))
        assertEquals("only ASCII hex is hex", " ＡＡ", Upi.decode("%ＡＡ", false))
    }

    // ---------------------------------------------------------------- Bharat QR

    /** A real Bharat QR (a PayU test merchant's): a code for every customer, two UPI templates (an ID, then a reference), CRC 17EF. */
    private val payu = "000201010211021644038470007469080415522024070007469061661000307000746960825HDFC00006225020001855322626470010A000000524" +
        "0129yellowqr.payutest.94@hdfcbank27370010A0000005240119STQ9y45z1cv3z5450925204569153033565802IN5910vendorName6010vendorCity" +
        "610650017262350519STQ9y45z1cv3z545092070870007469630417EF"

    /**
     * EMVCo's own example (Annex B of its merchant QR spec): a Chinese shop, its name in Chinese too, priced in yuan, CRC A13A.
     * Kept as the hex of its UTF-8 bytes so no editor can change a character of it.
     */
    private val emvco = hexUtf8(
        "303030323031303130323132323933303030313244313536303030303030303030353130413933464F33323330513331" +
        "323830303132443135363030303030303031303330383132333435363738353230343431313135383032434E35393134" +
        "42455354205452414E53504F5254363030374245494A494E4736343230303030325A4830313034E69C80E4BDB3E8BF90" +
        "E8BE9330323032E58C97E4BAAC3534303532332E37323533303331353635353032303136323333303330343132333430" +
        "3630332A2A2A303730384136303038363637303930324D45393133323030313641303131323233333434393938383737" +
        "3037303831323334353637383633303441313341")

    private fun hexUtf8(hex: String): String =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.toString(Charsets.UTF_8)

    /** The test's own CRC-16/CCITT-FALSE, so codes can be built here and sealed the way a printer seals them. */
    private fun crc(s: String): String {
        var c = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { c = (if ((c and 0x8000) != 0) (c shl 1) xor 0x1021 else c shl 1) and 0xFFFF }
        }
        return c.toString(16).uppercase().padStart(4, '0')
    }

    /** One field: its ID, its length in characters, its value. */
    private fun field(id: String, value: String) = id + value.codePointCount(0, value.length).toString().padStart(2, '0') + value

    /** A UPI template in field [at]: NPCI's ID, the UPI ID, and a least amount when there is one. */
    private fun upi(id: String, least: String? = null, at: String = "26") =
        field(at, field("00", "A000000524") + field("01", id) + (least?.let { field("02", it) } ?: ""))

    /** A whole code made of [fields], sealed with a right CRC. */
    private fun bharat(vararg fields: String): String = ("000201" + fields.joinToString("") + "6304").let { it + crc(it) }

    private fun resealed(code: String): String = code.dropLast(4).let { it + crc(it) }

    private val shop = arrayOf(field("52", "5411"), field("53", "356"), field("58", "IN"), field("59", "Kumar Tea Stall"), field("60", "Manali"))

    @Test fun `the test's own CRC agrees with the standard and with both real codes`() {
        assertEquals("29B1", crc("123456789"))      // CRC-16/CCITT-FALSE's published check value
        assertEquals("17EF", crc(payu.dropLast(4)))
        assertEquals("A13A", crc(emvco.dropLast(4)))
    }

    @Test fun `a real Bharat QR gives the UPI ID inside it and the shop's name`() {
        assertEquals(Payee("yellowqr.payutest.94@hdfcbank", "vendorName", 0, 0, fixed = false, oneBill = false), payee(payu))
        assertEquals("hex is hex, in either case", payee(payu), payee(payu.dropLast(4) + "17ef"))
    }

    @Test fun `a Bharat QR changed anywhere fails its CRC`() {
        assertEquals(Flaw.BROKEN, flaw(payu.replace("yellowqr.payutest.94", "yellowqr.payutest.95")))
        assertEquals(Flaw.BROKEN, flaw(payu.dropLast(4) + "17EE"))
        // Every single character, changed in turn: the payee, the lengths, the CRC itself.
        for (i in 6 until payu.length) {
            val changed = payu.substring(0, i) + (if (payu[i] == '0') '1' else '0') + payu.substring(i + 1)
            assertEquals("character $i", Flaw.BROKEN, flaw(changed))
        }
    }

    @Test fun `EMVCo's own example, in Chinese, is read to the end and refused only for its currency`() {
        assertEquals(Flaw.FOREIGN, flaw(emvco))
        // Read in rupees, it gets as far as finding no UPI ID — which it can only do if lengths count
        // characters (最佳运输 is four, not twelve bytes) and the CRC covers the UTF-8 bytes.
        assertEquals(1, emvco.split("5303156").size - 1)
        assertEquals(Flaw.NO_ID, flaw(resealed(emvco.replace("5303156", "5303356"))))
    }

    @Test fun `a Bharat QR with an amount fixes it, and one with a least amount sets a floor`() {
        assertEquals(Payee("kumar.tea@okaxis", "Kumar Tea Stall", 0, 0, fixed = false, oneBill = false), payee(bharat(field("01", "11"), upi("kumar.tea@okaxis"), *shop)))
        val fixed = payee(bharat(field("01", "11"), upi("kumar.tea@okaxis"), *shop, field("54", "120.00")))
        assertEquals(12000L, fixed.paise); assertTrue(fixed.fixed); assertFalse(fixed.oneBill)
        val least = payee(bharat(field("01", "11"), upi("kumar.tea@okaxis", least = "50"), *shop, field("54", "120")))
        assertEquals(12000L, least.paise); assertEquals(5000L, least.floor); assertFalse(least.fixed)
    }

    @Test fun `a Bharat QR made for one bill is marked so`() {
        assertTrue(payee(bharat(field("01", "12"), upi("kumar.tea@okaxis"), *shop, field("54", "120"))).oneBill)
        assertFalse("no amount, nothing to match", payee(bharat(field("01", "12"), upi("kumar.tea@okaxis"), *shop)).oneBill)
        val reference = bharat(field("01", "11"), upi("kumar.tea@okaxis"), upi("STQ9y45z1cv3z545092", at = "27"), *shop, field("54", "120"))
        assertTrue("a second UPI template carrying a bill's reference", payee(reference).oneBill)
        val askForOne = bharat(field("01", "11"), upi("kumar.tea@okaxis"), upi("***", at = "27"), *shop, field("54", "120"))
        assertFalse("*** asks the payer to type a reference; it isn't one", payee(askForOne).oneBill)
    }

    @Test fun `a Bharat QR in another currency, or for cards only, is refused`() {
        assertEquals(Flaw.FOREIGN, flaw(bharat(field("01", "11"), upi("kumar.tea@okaxis"), field("53", "840"), field("59", "Shop"))))
        assertEquals("no currency said is no other currency", "kumar.tea@okaxis", payee(bharat(field("01", "11"), upi("kumar.tea@okaxis"), field("59", "Shop"))).id)
        assertEquals("cards only", Flaw.NO_ID, flaw(bharat(field("01", "11"), field("04", "5220240700074690"), *shop)))
        assertEquals("a UPI template whose ID isn't one", Flaw.NO_ID, flaw(bharat(field("01", "11"), upi("not an id"), *shop)))
        assertEquals("another network's template", Flaw.NO_ID, flaw(bharat(field("01", "11"), field("26", field("00", "D15600000000") + field("01", "kumar@okaxis")), *shop)))
    }

    @Test fun `templates are searched 26 to 51 in order, and one that can't be read is skipped`() {
        assertEquals("by number, not by place", "first@okaxis", payee(bharat(field("01", "11"), upi("second@okaxis", at = "28"), upi("first@okaxis", at = "26"), *shop)).id)
        assertEquals("kumar@okaxis", payee(bharat(field("01", "11"), field("26", "garbage!"), upi("kumar@okaxis", at = "27"), *shop)).id)
        assertEquals("NPCI's ID in small letters", "kumar@okaxis", payee(bharat(field("01", "11"), field("26", field("00", "a000000524") + field("01", "kumar@okaxis")), *shop)).id)
        assertEquals("a field that comes twice: the first counts", "Kumar Tea Stall", payee(bharat(field("01", "11"), upi("kumar@okaxis"), *shop, field("59", "Someone Else"))).name)
        assertEquals("a template beyond 51 is not looked at", Flaw.NO_ID, flaw(bharat(field("01", "11"), upi("kumar@okaxis", at = "52"), field("59", "Shop"))))
    }

    @Test fun `a Bharat QR that doesn't add up is broken`() {
        val good = bharat(field("01", "11"), upi("kumar@okaxis"), *shop)
        assertEquals("kumar@okaxis", payee(good).id)
        assertEquals("cut short", Flaw.BROKEN, flaw(good.dropLast(10)))
        assertEquals("a field longer than what is left", Flaw.BROKEN, flaw(("000201" + upi("kumar@okaxis") + "5999Kumar6304").let { it + crc(it) }))
        assertEquals("no CRC at all", Flaw.BROKEN, flaw("000201" + upi("kumar@okaxis") + shop.joinToString("")))
        assertEquals("a CRC that isn't the last field", Flaw.BROKEN, flaw(good + field("62", "0503abc")))
        assertEquals("a CRC of the wrong length", Flaw.BROKEN, flaw(("000201" + upi("kumar@okaxis") + "6305").let { it + crc(it) + "0" }))
        assertEquals("a CRC that isn't hex", Flaw.BROKEN, flaw("000201" + upi("kumar@okaxis") + "6304WXYZ"))
    }

    // ---------------------------------------------------------------- junk

    @Test fun `anything that isn't a payment code says so, and never throws`() {
        for (junk in listOf("", " ", "\n\t", "hello", "https://example.com/pay?pa=a@b", "hopline://join?code=a-b-c", "upi", "UPI ID: a@ybl", "a@ybl",
            "x".repeat(5000), "upi://pay?pa=a@ybl&pn=" + "x".repeat(5000), "\u0000\u0001\u001b[31m\u007f", "\uFEFF", "00020", "0002", "٠٠٠٢٠١")) {
            val r = Upi.read(junk)
            assertEquals(junk.take(30), Flaw.NOT_UPI, r.flaw); assertNull(r.payee)
            assertFalse(junk.take(30), Upi.payable(junk))
        }
    }

    @Test fun `a payment code cut short is refused for what it is, never with an exception`() {
        assertEquals(Flaw.NOT_PAY, flaw("upi://")); assertEquals(Flaw.NOT_PAY, flaw("upi:"))
        assertEquals(Flaw.NO_ID, flaw("upi://pay")); assertEquals(Flaw.NO_ID, flaw("upi://pay?"))
        assertEquals(Flaw.BROKEN, flaw("000201")); assertEquals(Flaw.BROKEN, flaw("0002010102"))
        for (n in 6 until payu.length) assertEquals("first $n characters", Flaw.BROKEN, flaw(payu.take(n)))
        // Every cut of a real code is read or refused — one or the other, never both, never thrown.
        val link = "upi://pay?pa=gauravdua1.payu@indus&pn=sms%20plus&tr=DYQ13845198863&am=1.00&mam=null&purpose=02&cu=INR"
        for (code in listOf(payu, emvco, link)) for (n in 0..code.length) {
            val r = Upi.read(code.take(n))
            assertTrue(code.take(n), (r.payee == null) != (r.flaw == null))
        }
        // Known as payment codes, even the ones that can't be paid: a scan anywhere can say what it is.
        for (code in listOf("upi://mandate?pa=a@ybl", "000201", payu)) assertNotEquals(code, Flaw.NOT_UPI, flaw(code))
    }

    @Test fun `only a code the Pay screen could pay is offered for paying`() {
        for (yes in listOf("upi://pay?pa=sharmastores@okaxis&pn=Sharma", payu, "upi://pay?pa=a@ybl&am=500&mam=100", "upi://pay?pa=a@ybl&am=5000",
            "upi://pay?pa=a@ybl&am=1", bharat(field("01", "11"), upi("kumar.tea@okaxis"), *shop)))
            assertTrue(yes.take(40), Upi.payable(yes))
        for (no in listOf("upi://mandate?pa=a@ybl", "upi://collect?pa=a@ybl", "upi://pay?pa=a@ybl&cu=USD", "upi://pay?pa=a@ybl&pa=b@ybl",
            "upi://pay?pa=a@ybl&am=6000", "upi://pay?pa=a@ybl&am=0.50", "upi://pay?pa=a@ybl&am=9000&mam=6000", "upi://pay?pn=Shop",
            bharat(field("01", "11"), field("04", "5220240700074690"), *shop), payu.dropLast(4) + "17EE", "https://example.com"))
            assertFalse(no.take(40), Upi.payable(no))
    }

    @Test fun `a byte-order mark or spaces around a code are not part of it`() {
        assertEquals("a@ybl", payee("\uFEFFupi://pay?pa=a@ybl").id)
        assertEquals("a@ybl", payee("  upi://pay?pa=a@ybl\n").id)
        assertEquals("yellowqr.payutest.94@hdfcbank", payee("\uFEFF$payu\r\n").id)
    }

    // ---------------------------------------------------------------- UPI IDs typed by hand

    @Test fun `real UPI IDs are accepted as written`() {
        for (good in listOf("name@okaxis", "9876543210@ybl", "MAB.037135005900242@AXISBANK", "a.b-c_d@upi", "a@ybl", "x".repeat(153) + "@okaxis"))
            assertEquals(good, good, Upi.id(good))
        assertEquals("trimmed", "name@okaxis", Upi.id("  name@okaxis \n"))
    }

    @Test fun `anything else is not a UPI ID`() {
        for (bad in listOf("", " ", "name", "@ybl", "name@", "na me@ybl", "name@y", "name@@ybl", "name@ybl*401*", "name@ybl#", "x".repeat(154) + "@okaxis",
            "name\n@ybl", "name@ybl\nother@ybl", "*99#", "name*@ybl", "na#me@ybl", ".name@ybl", "-name@ybl", "name@ok.axis", "name@1ybl", "n\u0430me@ybl",
            "name@ybl.", "٩٨٧٦@ybl", "name@ybl\u202E", "upi://pay?pa=name@ybl"))
            assertNull(bad, Upi.id(bad))
    }

    @Test fun `a phone number typed where a UPI ID goes is recognised, to say so kindly`() {
        for (phone in listOf("9876543210", "+919876543210", "919876543210", "09876543210", "+91 98765 43210", "98765-43210", "6000000000"))
            assertTrue(phone, Upi.isPhone(phone))
        for (not in listOf("", "12345", "5876543210", "987654321", "98765432101", "+9198765432", "9876543210@ybl", "+19876543210", "٩٨٧٦٥٤٣٢١٠", "98765 4321O"))
            assertFalse(not, Upi.isPhone(not))
    }

    // ---------------------------------------------------------------- what may be dialled

    @Test fun `only the two fixed codes may ever be dialled`() {
        assertTrue(Upi.dialable(Upi.CODE_MENU)); assertTrue(Upi.dialable(Upi.CODE_SEND))
        assertTrue(Upi.dialable("*99#")); assertTrue(Upi.dialable("*99*1*3#"))
        for (no in listOf("*99*1*3", "*401*9876543210#", "**21*9876543210#", "*99#*401#", " *99#", "*99# ", "*99 #", "* 99*1*3#", "*99*1*3 #", "",
            "#", "*99*1*3#9876543210", "*99*1*3##", "tel:*99#", "*٩٩#", "*99*1*3#\n", "*99*1*4#"))
            assertFalse("\"$no\"", Upi.dialable(no))
    }

    // ---------------------------------------------------------------- amounts

    @Test fun `an amount is rupees with up to two places of paise, in plain digits`() {
        val good = mapOf("250" to 25000L, " 250 " to 25000L, "250.5" to 25050L, "250.05" to 25005L, "250.50" to 25050L, "250.500" to 25050L, "250.5000" to 25050L,
            "0.01" to 1L, "1" to 100L, "0" to 0L, "0.00" to 0L, "000250" to 25000L, "5000" to 500000L, "999999999.99" to 99_999_999_999L)
        for ((text, paise) in good) assertEquals("\"$text\"", paise, Upi.paise(text))
        for (bad in listOf("", " ", ".", "250.", ".5", "-5", "+5", "1,000", "1 000", "₹250", "Rs 250", "250 rs", "250.555", "250.501", "250.5.0", "1e3", "0x10",
            "٢٥٠", "२५०", "1000000000", "250,50", "١.٥", "250 .5"))
            assertNull("\"$bad\"", Upi.paise(bad))
    }

    /** [keys] typed one at a time at the end of the field, the way a keypad sends them; a refused key changes nothing. */
    private fun keys(keys: String, commaIsDecimal: Boolean = false): String {
        var t = ""
        for (k in keys) t = Upi.amountEdit(t, t.length, t.length, k.toString(), commaIsDecimal) ?: t
        return t
    }

    @Test fun `a comma typed in an amount groups thousands, it is never a decimal point`() {
        assertEquals("2500", keys("2,500")); assertEquals("1000", keys("1,000")); assertEquals("125000", keys("1,25,000"))
        assertEquals("12.50", keys("12.50")); assertEquals("2.50", keys("2.505")); assertEquals("1234567", keys("12345678"))
        assertEquals("one dot only", "2.5", keys("2.5."))
        assertEquals("another script's digits are written 0 to 9", "2500", keys("२,५००"))
        assertEquals("only where the language writes a comma for the dot", "2.50", keys("2,50", commaIsDecimal = true))
    }

    @Test fun `a pasted amount loses its rupee sign, spaces and grouping, or goes in not at all`() {
        assertEquals("1250.50", Upi.amountEdit("", 0, 0, "₹ 1,250.50")); assertEquals("1250.50", Upi.amountEdit("", 0, 0, "1,250.50"))
        assertEquals("125000", Upi.amountEdit("", 0, 0, " ₹1,25,000 "))
        for (bad in listOf("Rs 250", "-5", "1e3", "250 rs", "12.345", "abc", "1.250,50", "12345678", "₹", ",", " "))
            assertNull("\"$bad\"", Upi.amountEdit("", 0, 0, bad))
        assertEquals("with the comma as the dot, Indian grouping can't be read", null, Upi.amountEdit("", 0, 0, "1,250.50", commaIsDecimal = true))
    }

    @Test fun `a refused edit leaves the field as it was, a selection included, and deleting is always allowed`() {
        assertNull("a sign over a selection", Upi.amountEdit("1250", 0, 2, "-"))
        assertNull("a comma over a selection doesn't delete it", Upi.amountEdit("1250", 0, 2, ","))
        assertNull("letters", Upi.amountEdit("1250", 0, 2, "abc"))
        assertEquals("350", Upi.amountEdit("1250", 0, 2, "3"))
        assertNull("a third place after the dot", Upi.amountEdit("250.50", 6, 6, "0"))
        assertNull("a second dot", Upi.amountEdit("2.5", 3, 3, "."))
        assertEquals("deleting the dot, even where what is left is too long to type", "123456750", Upi.amountEdit("1234567.50", 7, 8, ""))
        assertEquals("", Upi.amountEdit("250", 0, 3, ""))
        for ((s, e) in listOf(-1 to 0, 2 to 1, 0 to 9)) assertNull("$s..$e", Upi.amountEdit("250", s, e, "1"))
    }

    @Test fun `what is typed into the USSD box is plain rupees, with paise only when there are some`() {
        assertEquals("250", Upi.rupees(25000)); assertEquals("250.50", Upi.rupees(25050)); assertEquals("250.05", Upi.rupees(25005))
        assertEquals("1", Upi.rupees(100)); assertEquals("0.01", Upi.rupees(1)); assertEquals("5000", Upi.rupees(Upi.MAX_PAISE))
        for (p in listOf(1L, 5L, 99L, 100L, 101L, 25050L, 500000L, 99_999_999_999L)) assertEquals(p, Upi.paise(Upi.rupees(p)))
    }

    @Test fun `on screen an amount is grouped the Indian way`() {
        assertEquals("₹250", Upi.shown(25000)); assertEquals("₹1,250.50", Upi.shown(125050)); assertEquals("₹1,25,000", Upi.shown(12500000))
        assertEquals("₹0", Upi.shown(0)); assertEquals("₹0.05", Upi.shown(5)); assertEquals("₹999", Upi.shown(99900)); assertEquals("₹1,000", Upi.shown(100000))
        assertEquals("₹5,000", Upi.shown(Upi.MAX_PAISE)); assertEquals("₹12,34,567.89", Upi.shown(123456789)); assertEquals("₹99,99,99,999.99", Upi.shown(99_999_999_999))
    }

    @Test fun `amounts are written in plain digits whatever language the phone is set to`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("1250.50", Upi.rupees(125050)); assertEquals("₹1,250.50", Upi.shown(125050)); assertEquals(125050L, Upi.paise("1250.50"))
        } finally { Locale.setDefault(before) }
    }

    @Test fun `an amount is checked against the USSD limits, then the code's least amount`() {
        val open = Payee("a@ybl", "", 0, 0, fixed = false, oneBill = false)
        val least = open.copy(floor = 20000)
        assertEquals(AmountFlaw.EMPTY, Upi.amountFlaw(null, open))
        assertEquals(AmountFlaw.TOO_SMALL, Upi.amountFlaw(0, open)); assertEquals(AmountFlaw.TOO_SMALL, Upi.amountFlaw(99, open))
        assertNull(Upi.amountFlaw(100, open)); assertNull(Upi.amountFlaw(Upi.MAX_PAISE, open))
        assertEquals(AmountFlaw.TOO_BIG, Upi.amountFlaw(Upi.MAX_PAISE + 1, open))
        assertEquals(AmountFlaw.UNDER_FLOOR, Upi.amountFlaw(19999, least)); assertNull(Upi.amountFlaw(20000, least))
        assertEquals("nothing typed comes first", AmountFlaw.EMPTY, Upi.amountFlaw(null, least))
        assertEquals("under ₹1 before under the least amount", AmountFlaw.TOO_SMALL, Upi.amountFlaw(50, least))
        assertEquals("over ₹5,000 before anything the code says", AmountFlaw.TOO_BIG, Upi.amountFlaw(550000, open.copy(floor = 600000)))
    }

    // ---------------------------------------------------------------- people paid before

    private val now = 1_700_000_000_000L
    private fun recent(id: String, name: String = "Shop", paise: Long = 25000, at: Long = now) = Recent(id, name, paise, at)

    @Test fun `recent payees survive a round trip`() {
        val list = listOf(recent("kumar@okaxis", "Kumar Tea Stall", 12000), recent("9876543210@ybl", "", 50, now - 1), recent("MAB.037135005900242@AXISBANK", "Café राम", 500000, 0))
        assertEquals(list, Upi.recents(Upi.json(list)))
        assertEquals(emptyList<Recent>(), Upi.recents(Upi.json(emptyList())))
    }

    @Test fun `the newest payee comes first, once, however its ID is written`() {
        var list = emptyList<Recent>()
        list = Upi.remember(list, recent("a@ybl", at = 1)); list = Upi.remember(list, recent("b@ybl", at = 2)); list = Upi.remember(list, recent("A@YBL", "Again", at = 3))
        assertEquals(listOf("A@YBL", "b@ybl"), list.map { it.id }); assertEquals("Again", list[0].name)
        assertEquals(listOf("b@ybl"), Upi.forget(list, "a@ybl").map { it.id })
        assertEquals(list, Upi.forget(list, "c@ybl"))
    }

    @Test fun `only the last six are kept`() {
        var list = emptyList<Recent>()
        for (i in 1..9) list = Upi.remember(list, recent("shop$i@ybl", at = i.toLong()))
        assertEquals((9 downTo 4).map { "shop$it@ybl" }, list.map { it.id })
        val stored = (1..9).joinToString(",", "[", "]") { """{"id":"shop$it@ybl","name":"Shop $it","paise":100,"at":$it}""" }
        assertEquals((1..6).map { "shop$it@ybl" }, Upi.recents(stored).map { it.id })
    }

    @Test fun `stored recents are read tolerantly - junk is no one, and one bad entry loses only itself`() {
        for (junk in listOf(null, "", " ", "null", "{}", "[1,2", "hello", "[null, 1, \"x\", []]", "{\"id\":\"a@ybl\"}"))
            assertEquals("$junk", emptyList<Recent>(), Upi.recents(junk))
        val mixed = """[{"id":"not an id","name":"x"},{"name":"no id"},{"id":null},{"id":"*99#"},{"id":"good@ybl","name":"Good","paise":100,"at":5},{"id":"GOOD@ybl","name":"twice"}]"""
        assertEquals(listOf(recent("good@ybl", "Good", 100, 5)), Upi.recents(mixed))
        val odd = """[{"id":"a@ybl","name":null,"paise":-5,"at":-1},{"id":"b@ybl","name":"Evil\u202Eshop\nnext\u0007","paise":"lots"}]"""
        assertEquals(listOf(recent("a@ybl", "", 0, 0), recent("b@ybl", "Evilshop next", 0, 0)), Upi.recents(odd))
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
