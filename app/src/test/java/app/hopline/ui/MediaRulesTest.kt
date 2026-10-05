package app.hopline.ui

import app.hopline.service.BlobRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A received file's name and type are the sender's words: what this phone shows and tells other apps instead. */
class MediaRulesTest {
    private val installer = "application/vnd.android.package-archive"

    // ---------------------------------------------------------------- the type other apps are told

    @Test fun commonKindsGetTheirTypeFromTheExtension() {
        assertEquals("application/pdf", MediaRules.typeFor("Trek plan.pdf"))
        assertEquals("image/jpeg", MediaRules.typeFor("summit.JPG"))
        assertEquals("image/png", MediaRules.typeFor("map.png"))
        assertEquals("audio/mpeg", MediaRules.typeFor("song.mp3"))
        assertEquals("audio/mp4", MediaRules.typeFor("voice-1700000000.m4a"))
        assertEquals("video/mp4", MediaRules.typeFor("river.mp4"))
        assertEquals("text/plain", MediaRules.typeFor("notes.txt"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", MediaRules.typeFor("budget.xlsx"))
        assertEquals("application/msword", MediaRules.typeFor("letter.doc"))
    }

    @Test fun anythingOffTheListIsJustBytesWhateverTheSenderSaid() {
        for (name in listOf("page.html", "drawing.svg", "script.js", "archive.zip", "no extension", "app.apk", "", ".", "tent."))
            assertEquals(name, MediaRules.ANY, MediaRules.typeFor(name))
    }

    @Test fun noFileIsEverOfferedToAnyAppAtAll() {
        // The "any app" fallback would put the package installer on the list.
        assertEquals("image/*", MediaRules.broadType("image/jpeg"))
        assertEquals("audio/*", MediaRules.broadType("audio/mpeg"))
        assertNull(MediaRules.broadType(MediaRules.ANY))
        assertNull(MediaRules.broadType("application/pdf"))
    }

    // ---------------------------------------------------------------- app installers

    @Test fun anAppInstallerByItsNameIsOnlyEverSaved() {
        for (name in listOf("game.apk", "GAME.APK", "bundle.apks", "bundle.xapk", "mirror.apkm", "game.apk.", "game.apk   ", ".apk"))
            assertTrue(name, MediaRules.saveOnly(name, "image/jpeg"))
    }

    @Test fun anAppInstallerByTheTypeItWasSentAsIsOnlyEverSaved() {
        assertTrue(MediaRules.saveOnly("photo.jpg", installer))
        assertTrue(MediaRules.saveOnly("photo.jpg", "Application/Vnd.Android.Package-Archive; charset=binary"))
        assertFalse(MediaRules.saveOnly("photo.jpg", "image/jpeg"))
        assertFalse(MediaRules.saveOnly("Trek plan.pdf", "application/pdf"))
    }

    @Test fun aNameTurnedRoundByADirectionMarkIsSeenForWhatItIs() {
        // Shows as "photo_kpa.jpg" where direction marks are obeyed: it is an app installer.
        val disguised = "photo_‮gpj.apk"
        assertEquals("photo_gpj.apk", MediaRules.shownName(disguised))
        assertTrue(MediaRules.saveOnly(disguised, "image/jpeg"))
        assertEquals(MediaRules.ANY, MediaRules.typeFor(disguised))
    }

    @Test fun aLongNameCannotPushTheExtensionOutOfSight() {
        val long = "a".repeat(1_000) + ".apk"
        assertTrue(MediaRules.saveOnly(long, "image/jpeg"))
        val shown = MediaRules.shownName("Trek ".repeat(80) + "plan.pdf")
        assertTrue(shown.codePointCount(0, shown.length) <= MediaRules.MAX_NAME)
        assertTrue(shown, shown.endsWith(".pdf"))
        assertEquals("application/pdf", MediaRules.typeFor("Trek ".repeat(80) + "plan.pdf"))
    }

    // ---------------------------------------------------------------- the name shown

    @Test fun controlAndDirectionCharactersNeverReachTheScreen() {
        assertEquals("a b.txt", MediaRules.shownName("a\nb.txt"))
        assertEquals("report.pdf", MediaRules.shownName("⁧report⁩.pdf‏"))
        assertEquals("tab.txt", MediaRules.shownName("t\u0000a\u0085b.txt"))
        assertEquals("file", MediaRules.shownName("‮‏"))
        assertEquals("file", MediaRules.shownName(""))
        assertEquals("kept", MediaRules.shownName("   ", fallback = "kept"))
    }

    @Test fun everyScriptAndEmojiIsLeftAsItIs() {
        assertEquals("👨‍👩‍👧 trip.jpg", MediaRules.shownName("👨‍👩‍👧 trip.jpg"))
        assertEquals("ट्रेक योजना.pdf", MediaRules.shownName("ट्रेक योजना.pdf"))
        assertEquals("خطة.pdf", MediaRules.shownName("خطة.pdf"))
    }

    @Test fun aNameOfAnyLengthIsCutWithoutSplittingAnEmoji() {
        // Measured as file names are (an emoji is two units): 50 of them fill it exactly
        val shown = MediaRules.shownName("😀".repeat(300))
        assertEquals(MediaRules.MAX_NAME, shown.length)
        assertTrue(shown.codePoints().allMatch { it == 0x1F600 })
        // a cut that would fall inside one leaves it out whole
        val odd = MediaRules.shownName("a" + "😀".repeat(300))
        assertEquals(MediaRules.MAX_NAME - 1, odd.length)
        assertTrue(odd.drop(1).codePoints().allMatch { it == 0x1F600 })
        val copy = BlobRules.displayName("a" + "😀".repeat(300))
        assertTrue(copy.drop(1).codePoints().allMatch { it == 0x1F600 })
    }

    @Test fun theCopyHandedToAnotherAppIsNamedExactlyWhatWasJudged() {
        // 48 emoji, ".apk", 6 emoji: 58 characters but 112 units. Judged by its whole name it isn't an
        // installer ("apk😀😀😀😀😀😀"); cut a second way for the copy, it could have ended in ".apk".
        val crafted = "😀".repeat(48) + ".apk" + "😀".repeat(6)
        val shown = MediaRules.shownName(crafted)
        val copy = BlobRules.displayName(shown)
        assertEquals("the copy is not cut again", shown, copy)
        assertEquals(MediaRules.saveOnly(crafted, "application/pdf"), MediaRules.saveOnly(copy, ""))
        assertTrue("it ends in .apk, and is only ever saved", copy.endsWith(".apk") && MediaRules.saveOnly(crafted, "application/pdf"))
        // the same holds for any name: what was judged is what the other app gets
        for (name in listOf("a".repeat(97) + ".apk" + "b".repeat(20), "😀".repeat(49) + ".apk.x", "Trek ".repeat(80) + "plan.pdf",
                "ट्रेक".repeat(40) + ".apk", "x.apk" + " ".repeat(200) + "y")) {
            val s = MediaRules.shownName(name)
            assertEquals(name, s, BlobRules.displayName(s))
            assertEquals(name, MediaRules.saveOnly(name, ""), MediaRules.saveOnly(BlobRules.displayName(s), ""))
        }
    }
}
