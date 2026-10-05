package app.hopline.service

import app.hopline.core.Crypto
import app.hopline.data.History
import app.hopline.data.Upgrade
import app.hopline.mesh.Attachment
import app.hopline.mesh.ChunkStore
import app.hopline.mesh.Envelope
import app.hopline.mesh.FakeNet
import app.hopline.mesh.MemoryChunkStore
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.mesh.Transport
import app.hopline.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/**
 * The disk side of file pieces: which names may touch the disk, how long pieces live, and the space
 * they may take — and the files they make: sealed on the way out, checked piece by piece and as a
 * whole on the way in, and never announced unless this phone holds them.
 */
class BlobStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hour = 3600_000L
    private var now = 1_700_000_000_000L
    private val inline = Executor { it.run() }
    private val logged = ArrayList<String>()

    private fun store(dir: File, budget: Long = Long.MAX_VALUE, orphanCap: Int = 1000, reserve: Long = 0,
                      free: (() -> Long)? = null) =
        Blobs.DiskChunkStore(dir, budgetBytes = budget, reserveBytes = reserve, orphanCap = orphanCap,
            clock = { now }, background = inline, log = { msg, _ -> logged.add(msg) }, freeSpace = free ?: { dir.usableSpace })

    private fun piece(fid: String, i: Int, ts: Long = now, to: String? = null, data: String = "QUJD"): Envelope {
        val j = JSONObject().put("id", Envelope.chunkId(fid, i)).put("k", Envelope.CHUNK).put("o", "sender").put("on", "Asha")
            .put("ts", ts).put("h", 0).put("c", data)
        if (to != null) j.put("to", to)
        return Envelope(j)
    }

    private fun rawPiece(id: String): Envelope = Envelope(JSONObject().put("id", id).put("k", Envelope.CHUNK).put("o", "x")
        .put("ts", now).put("h", 0).put("c", "QUJD"))

    // ---------------------------------------------------------------- names

    @Test fun chunkIdsMustBeExactlyFidAndIndex() {
        assertEquals("abcdef", BlobRules.chunkFid("f.abcdef.0"))
        assertEquals("k3j9m2p4q8r7", BlobRules.chunkFid(Envelope.chunkId("k3j9m2p4q8r7", Router.MAX_CHUNKS - 1)))
        for (bad in listOf(
            "../../../mesh-state-x", "f.../x.0", "f.abcdef.0/../../x", "f.abcdef/../.0", "f.abcdef.0\\x",
            "f.abc.0",                       // fid too short
            "f.ABCDEF.0",                    // fids are lowercase
            "f.abcdef.007", "f.abcdef.+1",   // never what a sender builds
            "f.abcdef.-1", "f.abcdef.${Router.MAX_CHUNKS}", "f.abcdef.", "f.abcdef", "g.abcdef.0", "",
            "f." + "a".repeat(70) + ".0",
        )) assertNull("must refuse \"$bad\"", BlobRules.chunkFid(bad))
    }

    @Test fun everyIndexASenderMakesIsAccepted() {
        for (i in 0 until Router.MAX_CHUNKS) assertEquals("abcdef", BlobRules.chunkFid(Envelope.chunkId("abcdef", i)))
    }

    @Test fun insideMeansStrictlyInside() {
        val dir = tmp.newFolder("chunks")
        assertTrue(BlobRules.isInside(dir, File(dir, "f.abcdef.0.json")))
        assertFalse(BlobRules.isInside(dir, File(dir, "../escape.json")))
        assertFalse(BlobRules.isInside(dir, dir))
        assertFalse(BlobRules.isInside(dir, File(dir.parentFile, "chunks-other/x")))
    }

    @Test fun displayNamesKeepTheirWordsButNotPaths() {
        assertEquals("Trek plan.pdf", BlobRules.displayName("Trek plan.pdf"))
        assertEquals("फोटो.jpg", BlobRules.displayName("फोटो.jpg"))
        assertEquals("_.._etc_passwd", BlobRules.displayName("/../etc/passwd"))
        assertEquals("a_b_c", BlobRules.displayName("a\u0000b\nc"))
        assertEquals("file", BlobRules.displayName(".."))
        assertEquals("file", BlobRules.displayName("   "))
        assertEquals("x.bin", BlobRules.displayName("", fallback = "x.bin"))
        val long = BlobRules.displayName("n".repeat(300) + ".docx")
        assertEquals(100, long.length)
        assertTrue(long.endsWith(".docx"))
    }

    @Test fun safeNameIsTheOldOnDiskSpelling() {
        // Files assembled by 2.1 are found by this exact name; it must never change.
        assertEquals("Trek_plan_v2.pdf", BlobRules.safeName("Trek plan v2.pdf"))
        assertEquals("file", BlobRules.safeName(""))
        assertEquals(60, BlobRules.safeName("x".repeat(90)).length)
    }

    @Test fun uniqueNamesCountUp() {
        val taken = setOf("photo.jpg", "photo (1).jpg", "notes")
        assertEquals("photo (2).jpg", BlobRules.uniqueName("photo.jpg") { it in taken })
        assertEquals("notes (1)", BlobRules.uniqueName("notes") { it in taken })
        assertEquals("fresh.png", BlobRules.uniqueName("fresh.png") { it in taken })
    }

    // ---------------------------------------------------------------- how long a piece lives

    @Test fun aPieceLives48HoursFromTheSendersStamp() {
        assertEquals(now, BlobRules.anchor(now, now))
        // Every phone that got it within a day drops it at the same moment, however late it got here.
        assertEquals(now - hour, BlobRules.anchor(now - hour, now))
        assertEquals(now - 20 * hour, BlobRules.anchor(now - 20 * hour, now))
    }

    @Test fun aSenderClockAheadCantMakeAPieceImmortal() {
        assertEquals(now, BlobRules.anchor(now + 400 * 24 * hour, now))
        assertEquals(now, BlobRules.anchor(Long.MAX_VALUE, now))
    }

    @Test fun aSenderClockBehindStillGetsADay() {
        assertEquals(now - 24 * hour, BlobRules.anchor(now - 30 * hour, now))
        assertEquals(now - 24 * hour, BlobRules.anchor(now - 72 * hour, now))
        assertEquals(now - 24 * hour, BlobRules.anchor(0, now))
        assertEquals(now - 24 * hour, BlobRules.anchor(Long.MIN_VALUE, now))
    }

    @Test fun aLetGoPieceIsListedWhileAFriendMayStillCarryIt() {
        // Dropped on time: a friend that got it a day late keeps it up to a day longer.
        assertEquals(now + 24 * hour, BlobRules.listUntil(now - 48 * hour, now))
        // Squeezed out early: listed until its own 48 h would have ended, and that day on top.
        assertEquals(now + 72 * hour, BlobRules.listUntil(now, now))
    }

    // ---------------------------------------------------------------- the store

    @Test fun pathTraversalIdsNeverTouchTheDisk() {
        val root = tmp.newFolder("blobs")
        val dir = File(root, "chunks")
        val victim = File(root, "mesh-state-x.json").apply { writeText("precious") }
        val s = store(dir)
        assertFalse(s.put(rawPiece("../mesh-state-x")))
        assertFalse(s.put(rawPiece("f.abcdef.0/../../mesh-state-x")))
        assertFalse(s.has("../mesh-state-x"))
        assertNull(s.get("../mesh-state-x"))
        assertEquals("precious", victim.readText())
        s.expire(Long.MAX_VALUE)
        assertTrue(victim.exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun piecesRoundTrip() {
        val s = store(tmp.newFolder("chunks"))
        val e = piece("abcdef", 3, data = "SGVsbG8=")
        assertTrue(s.put(e))
        assertTrue(s.has(e.id))
        assertEquals(listOf(e.id), s.ids())
        val back = s.get(e.id)
        assertNotNull(back)
        assertEquals("SGVsbG8=", back!!.sealed)
        assertTrue(s.bytesUsed() > 0)
    }

    @Test fun aSenderDaysBehindStillHasItsPiecesKept() {
        val s = store(tmp.newFolder("chunks"))
        assertTrue(s.put(piece("abcdef", 0, ts = now - 72 * hour)))
        // The router expires everything older than 48 h on every tick: the piece must survive it…
        s.expire(now - Router.CARRY_MS)
        assertTrue(s.has(Envelope.chunkId("abcdef", 0)))
        // …for a day here, then go like anything else.
        now += 25 * hour
        s.expire(now - Router.CARRY_MS)
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
    }

    @Test fun aSenderFarAheadExpiresOnOurClock() {
        val dir = tmp.newFolder("chunks")
        val s = store(dir)
        assertTrue(s.put(piece("abcdef", 0, ts = now + 30 * 24 * hour)))
        now += 47 * hour
        s.expire(now - Router.CARRY_MS)
        assertTrue(s.has(Envelope.chunkId("abcdef", 0)))
        now += 2 * hour
        s.expire(now - Router.CARRY_MS)
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
        assertFalse(File(dir, "f.abcdef.0.json").exists())
    }

    @Test fun aPieceReceivedAgainKeepsItsFirstArrival() {
        val s = store(tmp.newFolder("chunks"))
        assertTrue(s.put(piece("abcdef", 0)))
        now += 40 * hour
        assertTrue(s.put(piece("abcdef", 0)))   // gap-fill hands it over again
        now += 9 * hour
        s.expire(now - Router.CARRY_MS)
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
    }

    @Test fun overBudgetTheOldestPiecesGoFirst() {
        val dir = tmp.newFolder("chunks")
        val one = piece("abcdef", 0).json.toString().toByteArray().size.toLong()
        val s = store(dir, budget = one * 3)
        for (i in 0 until 3) { assertTrue(s.put(piece("abcdef", i))); now += 1000 }
        assertTrue(s.put(piece("ghijkm", 0)))
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
        assertTrue(s.has(Envelope.chunkId("ghijkm", 0)))
        assertTrue(s.bytesUsed() <= one * 3)
        assertFalse(File(dir, "f.abcdef.0.json").exists())
        // Squeezed out, but friends still carry it: listed, so they don't offer it every sync…
        assertFalse(Envelope.chunkId("abcdef", 0) in s.ids())
        assertTrue(Envelope.chunkId("abcdef", 0) in s.advertised())
        // …and an offer that comes anyway doesn't push something else out.
        assertTrue(s.put(piece("abcdef", 0)))
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
        assertTrue(s.has(Envelope.chunkId("ghijkm", 0)))
    }

    @Test fun phonesThatGotAPieceHoursApartDropItTogether() {
        val sent = now
        val id = Envelope.chunkId("abcdef", 0)
        val early = store(tmp.newFolder("early"))
        val late = store(tmp.newFolder("late"))
        assertTrue(early.put(piece("abcdef", 0, ts = sent)))
        now += 8 * hour                                       // a friend walks it over that evening
        assertTrue(late.put(piece("abcdef", 0, ts = sent)))
        now = sent + Router.CARRY_MS - 1
        for (s in listOf(early, late)) { s.expire(now - Router.CARRY_MS); assertTrue(s.has(id)) }
        // Neither outlives the other, so neither keeps offering it to a phone that let it go.
        now = sent + Router.CARRY_MS + 1
        for (s in listOf(early, late)) { s.expire(now - Router.CARRY_MS); assertFalse(s.has(id)) }
    }

    @Test fun anExpiredPieceStaysListedUntilFriendsAreDoneWithIt() {
        val dir = tmp.newFolder("chunks")
        val s = store(dir)
        val id = Envelope.chunkId("abcdef", 0)
        val sent = now
        assertTrue(s.put(piece("abcdef", 0, ts = sent)))
        now += 49 * hour
        s.expire(now - Router.CARRY_MS)
        assertFalse(s.has(id))
        assertNull(s.get(id))
        assertEquals(emptyList<String>(), s.ids())
        assertEquals(listOf(id), s.advertised())
        assertFalse(File(dir, "f.abcdef.0.json").exists())
        // A late copy doesn't start its time over.
        assertTrue(s.put(piece("abcdef", 0, ts = sent)))
        assertFalse(s.has(id))
        // A friend that got it a day late is done with it a day later: then it's simply gone.
        now += 23 * hour
        s.expire(now - Router.CARRY_MS)
        assertEquals(listOf(id), s.advertised())
        now += 2 * hour
        s.expire(now - Router.CARRY_MS)
        assertEquals(emptyList<String>(), s.advertised())
        assertEquals(emptyList<String>(), s.takeReleased())   // let go for good, never fetched again
    }

    @Test fun strayPiecesAreTurnedAwayOnceTheyPileUp() {
        val s = store(tmp.newFolder("chunks"), orphanCap = 3)
        val known = HashSet<String>()
        s.isKnownFile = { it in known }
        for (i in 0 until 3) assertTrue(s.put(piece("zzzzzz", i)))
        // Accepted-and-dropped, never false: a false would make the router flood it on again.
        assertTrue(s.put(piece("zzzzzz", 3)))
        assertFalse(s.has(Envelope.chunkId("zzzzzz", 3)))
        // A private photo for someone else is carried, never counted as stray.
        assertTrue(s.put(piece("yyyyyy", 0, to = "bob")))
        assertTrue(s.has(Envelope.chunkId("yyyyyy", 0)))
        // A known file is always welcome.
        known.add("abcdef")
        assertTrue(s.put(piece("abcdef", 0)))
        assertTrue(s.has(Envelope.chunkId("abcdef", 0)))
        // Once the stray file's message turns up, its pieces stop counting against the cap.
        known.add("zzzzzz")
        assertTrue(s.put(piece("xxxxxx", 0)))
        assertTrue(s.has(Envelope.chunkId("xxxxxx", 0)))
    }

    @Test fun aStrayTurnedAwayComesBackOnceItsMessageArrives() {
        val s = store(tmp.newFolder("chunks"), orphanCap = 1)
        val known = HashSet<String>()
        s.isKnownFile = { it in known }
        val id = Envelope.chunkId("zzzzzz", 1)
        assertTrue(s.put(piece("zzzzzz", 0)))
        assertTrue(s.put(piece("zzzzzz", 1)))                 // over the cap: turned away
        assertFalse(s.has(id))
        assertTrue(id in s.advertised())                       // nobody keeps offering it meanwhile
        s.expire(now - Router.CARRY_MS)
        assertEquals(emptyList<String>(), s.takeReleased())   // still nobody's file
        known.add("zzzzzz")
        now += 30_000
        s.expire(now - Router.CARRY_MS)
        // The router forgets it saw the piece, so the next sync brings it again.
        assertEquals(listOf(id), s.takeReleased())
        assertFalse(id in s.advertised())
        assertTrue(s.put(piece("zzzzzz", 1)))
        assertTrue(s.has(id))
    }

    @Test fun piecesTurnedAwayForRoomComeBackOnceThereIsRoom() {
        var free = 100L * 1024 * 1024                         // under the 200 MB reserve
        val s = store(tmp.newFolder("chunks"), reserve = Blobs.RESERVE_BYTES, free = { free })
        val id = Envelope.chunkId("abcdef", 0)
        assertTrue(s.put(piece("abcdef", 0)))                  // accepted-and-dropped
        assertFalse(s.has(id))
        assertTrue(Blobs.storageLow)
        assertEquals(listOf(id), s.advertised())               // no friend keeps sending it to a full phone
        now += 30_000
        s.expire(now - Router.CARRY_MS)
        assertEquals(emptyList<String>(), s.takeReleased())
        assertTrue(Blobs.storageLow)
        // The person frees space. No new piece arrives, yet "storage full" clears on the next tick…
        free = 2L * 1024 * 1024 * 1024
        now += 30_000
        s.expire(now - Router.CARRY_MS)
        assertFalse(Blobs.storageLow)
        // …and the piece is handed back once, so the router lets the next sync bring it.
        assertEquals(listOf(id), s.takeReleased())
        assertEquals(emptyList<String>(), s.takeReleased())
        assertEquals(emptyList<String>(), s.advertised())
        assertTrue(s.put(piece("abcdef", 0)))
        assertTrue(s.has(id))
        assertEquals(listOf(id), s.advertised())
    }

    @Test fun aPhoneHoveringAtTheLineDoesNotFlipBackAndForth() {
        val mb = 1024L * 1024
        var free = Blobs.RESERVE_BYTES - mb
        val s = store(tmp.newFolder("chunks"), reserve = Blobs.RESERVE_BYTES, free = { free })
        for (i in 0 until 1100) assertTrue(s.put(rawPiece(Envelope.chunkId("abc${i / 100}def", i % 100))))
        assertTrue(Blobs.storageLow)
        // A cache somewhere frees a few MB: still full, nothing handed back to be refused again.
        free = Blobs.RESERVE_BYTES + 10 * mb
        now += 30_000; s.expire(now - Router.CARRY_MS)
        assertTrue(Blobs.storageLow)
        assertEquals(emptyList<String>(), s.takeReleased())
        // Real room again: only as many come back as fit above the reserve, the rest stay listed.
        free = Blobs.RESERVE_BYTES + 64 * mb + 10 * 64 * 1024
        now += 30_000; s.expire(now - Router.CARRY_MS)
        assertFalse(Blobs.storageLow)
        val back = s.takeReleased()
        assertEquals(1024 + 10, back.size)
        assertEquals(1100 - back.size, s.advertised().size)
    }

    @Test fun aStrayComesBackOnceItsMessageArrivesEvenWithLittleRoomToSpare() {
        val mb = 1024L * 1024
        val s = store(tmp.newFolder("chunks"), reserve = Blobs.RESERVE_BYTES, orphanCap = 1, free = { Blobs.RESERVE_BYTES + 30 * mb })
        var known = false
        s.isKnownFile = { known }
        assertTrue(s.put(piece("abcdef", 0)))                     // the one orphan allowed
        assertTrue(s.put(piece("abcdef", 1)))                     // turned away as a stray
        assertFalse(s.has(Envelope.chunkId("abcdef", 1)))
        known = true                                              // its message arrives
        now += 30_000; s.expire(now - Router.CARRY_MS)
        assertEquals(listOf(Envelope.chunkId("abcdef", 1)), s.takeReleased())
    }

    @Test fun aFullListNeverHandsBackPiecesTurnedAwayForRoom() {
        val s = store(tmp.newFolder("chunks"), reserve = Blobs.RESERVE_BYTES, free = { 1024L })
        // Far more pieces than the list holds: the overflow is forgotten, never handed back while full.
        for (i in 0 until 20_100) assertTrue(s.put(rawPiece(Envelope.chunkId("abc${i / 100}def", i % 100))))
        now += 30_000; s.expire(now - Router.CARRY_MS)
        assertEquals(emptyList<String>(), s.takeReleased())
        assertTrue(s.advertised().size <= 20_000)
    }

    @Test fun aPieceWaitingForRoomTooLongIsStillHandedBack() {
        val s = store(tmp.newFolder("chunks"), reserve = Blobs.RESERVE_BYTES, free = { 1024L })
        val id = Envelope.chunkId("abcdef", 0)
        assertTrue(s.put(piece("abcdef", 0)))
        // Past the time any friend could still carry it, the listing goes — and a copy that turns
        // up after all may come in (or be turned away again), never be thrown away unseen.
        now += 73 * hour
        s.expire(now - Router.CARRY_MS)
        assertEquals(emptyList<String>(), s.advertised())
        assertEquals(listOf(id), s.takeReleased())
        assertTrue(s.put(piece("abcdef", 0)))
        assertFalse(s.has(id))
        assertEquals(listOf(id), s.advertised())
    }

    @Test fun aFullPhoneStopsCarryingOthersPiecesButKeepsItsOwn() {
        // A reserve bigger than any disk: this phone is always "almost full".
        val s = store(tmp.newFolder("chunks"), reserve = Long.MAX_VALUE)
        s.isMine = { it.origin == "me" }
        assertTrue(s.put(piece("abcdef", 0)))                  // accepted-and-dropped: never flooded on in a loop
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
        val mine = Envelope(piece("ghijkm", 0).json.put("o", "me"))
        assertTrue(s.put(mine))                                // my own photo: only this phone has it yet
        assertTrue(s.has(mine.id))
    }

    @Test fun reopeningKeepsGoodPiecesAndClearsWreckage() {
        val dir = tmp.newFolder("chunks")
        val s = store(dir)
        assertTrue(s.put(piece("abcdef", 2, ts = now - 72 * hour)))
        File(dir, "f.abcdef.0.json").writeText("")                 // a write cut off at zero bytes
        File(dir, "f.abcdef.1.json.123.tmp").writeText("{half")      // a write cut off mid-piece
        File(dir, "garbage.json").writeText("{}")                    // never a piece name
        val again = store(dir)
        assertEquals(listOf(Envelope.chunkId("abcdef", 2)), again.ids())
        assertEquals(setOf("f.abcdef.2.json"), dir.list()!!.toSet())
        assertEquals(s.bytesUsed(), again.bytesUsed())
        // Its 48 h still runs from when it first arrived (the file's stamp), not from the reopen.
        now += 25 * hour
        again.expire(now - Router.CARRY_MS)
        assertFalse(again.has(Envelope.chunkId("abcdef", 2)))
    }

    @Test fun aDamagedPieceIsDroppedNotServed() {
        val dir = tmp.newFolder("chunks")
        val s = store(dir)
        assertTrue(s.put(piece("abcdef", 0)))
        File(dir, "f.abcdef.0.json").writeText("{not json")
        assertNull(s.get(Envelope.chunkId("abcdef", 0)))
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
        assertFalse(File(dir, "f.abcdef.0.json").exists())
        // Not listed, and handed back: a friend's good copy fills the gap at the next sync.
        assertEquals(emptyList<String>(), s.advertised())
        assertEquals(listOf(Envelope.chunkId("abcdef", 0)), s.takeReleased())
        assertTrue(s.put(piece("abcdef", 0)))
        assertNotNull(s.get(Envelope.chunkId("abcdef", 0)))
    }

    @Test fun aPieceUnderAnotherPiecesNameIsNotServed() {
        val dir = tmp.newFolder("chunks")
        val s = store(dir)
        assertTrue(s.put(piece("abcdef", 0)))
        File(dir, "f.abcdef.0.json").writeText(piece("abcdef", 1).json.toString())
        assertNull(s.get(Envelope.chunkId("abcdef", 0)))
    }

    @Test fun aVanishedPieceStopsBeingClaimed() {
        val dir = tmp.newFolder("chunks")
        val s = store(dir)
        assertTrue(s.put(piece("abcdef", 0)))
        assertTrue(File(dir, "f.abcdef.0.json").delete())
        assertNull(s.get(Envelope.chunkId("abcdef", 0)))
        assertFalse(s.has(Envelope.chunkId("abcdef", 0)))
        assertEquals(0L, s.bytesUsed())
        assertEquals(listOf(Envelope.chunkId("abcdef", 0)), s.takeReleased())
    }

    // ---------------------------------------------------------------- leaving and deleting a group

    private val day = 24 * hour
    /** Where a group's carried pieces live (2.4 and later). */
    private val P = BlobRules.PIECES

    /** A group's folder as the app lays it out: carried pieces, and the photos and files of its chat. */
    private fun group(root: File, fp: String, pieceAge: Long = hour): File = File(root, fp).also { g ->
        File(g, P).mkdirs(); File(g, "files").mkdirs()
        File(g, "$P/f.abcdef.0.json").apply { writeText("{}"); assertTrue(setLastModified(now - pieceAge)) }
        File(g, "files/abcdefgh-photo.jpg").apply { writeText("jpeg"); assertTrue(setLastModified(now - 400 * day)) }
    }

    @Test fun aHalfWrittenFileCanNeverBeTakenForAKeptOne() {
        assertTrue(BlobRules.isTemp(BlobRules.tempPrefix("abcdefgh-photo.jpg") + "4711.part"))
        assertTrue("a temp file's name needs three characters", BlobRules.tempPrefix("").length >= 3)
        assertTrue(BlobRules.tempPrefix("x".repeat(200)).length <= 41)
        // Whatever a sender calls their file, the name it is kept under is never a temp name.
        for (name in listOf("backup.part", "notes.part", "~lock.part", "~", "a~b", "")) {
            assertFalse("\"$name\" must be kept", BlobRules.isTemp("abcdefgh-" + BlobRules.safeName(name)))
            assertFalse(BlobRules.isTemp("invalid-" + BlobRules.safeName(name)))
        }
    }

    @Test fun tidyingKeepsAFileThatIsReallyNamedDotPart() {
        val root = tmp.newFolder("blobs")
        val files = File(group(root, "ab12cd34"), "files")
        // 2.2 took any old "*.part" for its own leftovers — and deleted this one, a file someone sent.
        val backup = File(files, "ijkmnpqr-backup.part").apply { writeText("someone's backup"); assertTrue(setLastModified(now - 30 * day)) }
        val photo = File(files, "abcdefgh-photo.jpg")
        val stale = File(files, BlobRules.tempPrefix("stuvwxyz-voice.m4a") + "1.part").apply { writeText("half"); assertTrue(setLastModified(now - 2 * hour)) }
        val fresh = File(files, BlobRules.tempPrefix("stuvwxyz-voice.m4a") + "2.part").apply { writeText("being written"); assertTrue(setLastModified(now - 60_000)) }
        // on the radio, paused, or left long ago: the same
        for (active in listOf("ab12cd34", null, "ef56ab78")) {
            BlobRules.sweep(root, now, active)
            assertTrue(backup.exists()); assertEquals("someone's backup", backup.readText())
            assertTrue(photo.exists())
            assertFalse("a write cut short an hour ago is nobody's file", stale.exists())
            assertTrue("one still being written is left to finish", fresh.exists())
        }
    }

    @Test fun droppingPiecesKeepsTheChatsFilesAndEveryOtherGroup() {
        val root = tmp.newFolder("blobs")
        val left = group(root, "ab12cd34")
        val other = group(root, "ef56ab78")
        val dead = BlobRules.moveAside(File(left, P), BlobRules.deadChunksName(now))
        assertNotNull(dead)
        assertTrue(BlobRules.isDeadChunks(dead!!.name)); assertEquals(left, dead.parentFile)
        assertFalse(File(left, P).exists())
        assertTrue("moved whole, to be deleted at leisure", File(dead, "f.abcdef.0.json").exists())
        assertTrue(File(left, "files/abcdefgh-photo.jpg").exists())
        assertTrue(File(other, "$P/f.abcdef.0.json").exists()); assertTrue(File(other, "files/abcdefgh-photo.jpg").exists())
        // Joined again a moment later: a fresh, empty folder under the old name, out of the slow delete's reach.
        val again = store(File(left, P))
        assertEquals(emptyList<String>(), again.ids())
        assertTrue(again.put(piece("ghijkm", 0)))
        assertTrue(dead.deleteRecursively())
        assertNotNull(again.get(Envelope.chunkId("ghijkm", 0)))
        assertTrue(File(left, "files/abcdefgh-photo.jpg").exists())
        // nothing there: nothing to move, and nothing is created by asking
        assertNull(BlobRules.moveAside(File(root, "aa11bb22/$P"), BlobRules.deadChunksName(now)))
        assertFalse(File(root, "aa11bb22").exists())
    }

    @Test fun deletingAGroupMovesItsWholeFolderAsideAtOnce() {
        val root = tmp.newFolder("blobs")
        val doomed = group(root, "ab12cd34")
        val other = group(root, "ef56ab78")
        val gone = BlobRules.moveAside(doomed, BlobRules.goneGroupName("ab12cd34", now))
        assertNotNull(gone)
        assertTrue(BlobRules.isGoneGroup(gone!!.name)); assertEquals(root, gone.parentFile)
        assertFalse(doomed.exists())
        assertTrue(File(gone, "files/abcdefgh-photo.jpg").exists())
        // The same code joined again right away starts in a folder of its own.
        val again = group(root, "ab12cd34")
        // If the phone dies before the delete finishes, the next tidy finishes it — and only it.
        BlobRules.sweep(root, now, activeFp = "ab12cd34")
        assertFalse(gone.exists())
        assertTrue(File(again, "files/abcdefgh-photo.jpg").exists()); assertTrue(File(again, "$P/f.abcdef.0.json").exists())
        assertTrue(File(other, "files/abcdefgh-photo.jpg").exists())
        // Neither name can ever be a group's or a live piece folder's own.
        assertFalse(BlobRules.isGoneGroup("ab12cd34")); assertFalse(BlobRules.isDeadChunks("chunks")); assertFalse(BlobRules.isDeadChunks("files"))
        assertFalse(BlobRules.isDeadChunks(P))
        assertTrue(BlobRules.isDeadChunks(BlobRules.deadChunksName(now, BlobRules.LEGACY_PIECES)))
    }

    @Test fun aSweepFinishesWhatAKillCutShortAndTouchesNoKeptFile() {
        val root = tmp.newFolder("blobs")
        val active = group(root, "cc33dd44", pieceAge = 60 * hour)
        val paused = group(root, "aa11bb22", pieceAge = 60 * hour)
        File(paused, "$P/f.abcdef.1.json").apply { writeText("{}"); assertTrue(setLastModified(now - 2 * hour)) }
        // left, and killed after the pieces were moved aside but before they were deleted
        val left = group(root, "ab12cd34")
        assertNotNull(BlobRules.moveAside(File(left, P), BlobRules.deadChunksName(now - day)))
        // being deleted by this very run of the app: not the sweep's business
        val closing = group(root, "ef56ab78", pieceAge = 60 * hour)
        BlobRules.sweep(root, now, activeFp = "cc33dd44") { it == "ef56ab78" }
        // the group on the radio expires its own pieces; a paused one has nobody to do it
        assertTrue(File(active, "$P/f.abcdef.0.json").exists())
        assertFalse(File(paused, "$P/f.abcdef.0.json").exists())
        assertTrue(File(paused, "$P/f.abcdef.1.json").exists())
        assertEquals(listOf("files"), left.list()!!.toList())
        assertTrue(File(closing, "$P/f.abcdef.0.json").exists())
        for (g in listOf(active, paused, left, closing)) assertTrue(File(g, "files/abcdefgh-photo.jpg").exists())
        // a folder that never existed is no trouble
        BlobRules.sweep(File(root, "nowhere"), now, activeFp = null)
    }

    // ---------------------------------------------------------------- the plain pieces of 2.3 and older

    /** What 2.3 left in a group's folder: pieces that were the file's own bytes. */
    private fun legacyPieces(g: File): File = File(g, BlobRules.LEGACY_PIECES).also { d ->
        d.mkdirs()
        File(d, "f.abcdef.0.json").writeText("""{"id":"f.abcdef.0","k":"chunk","d":"bWVldCBhdCB0aGUgZ2F0ZQ=="}""")
    }

    @Test fun thePlainPiecesOfOlderVersionsGoForEveryGroup() {
        val root = tmp.newFolder("blobs")
        val active = group(root, "cc33dd44")
        val paused = group(root, "aa11bb22")
        val left = File(root, "ab12cd34").also { File(it, "files").mkdirs(); File(it, "files/abcdefgh-photo.jpg").writeText("jpeg") }
        for (g in listOf(active, paused, left)) legacyPieces(g)
        // a left group's pieces that 2.3 moved aside and a kill kept from deleting
        File(left, "chunks.dead-1700000000000").mkdirs()
        BlobRules.sweep(root, now, activeFp = "cc33dd44")
        for (g in listOf(active, paused, left)) {
            assertFalse(File(g, BlobRules.LEGACY_PIECES).exists())
            assertTrue("kept files are never touched", File(g, "files/abcdefgh-photo.jpg").exists())
        }
        assertEquals(listOf("files"), left.list()!!.toList())
        // Today's pieces stay where they are, the group on the radio's included.
        assertTrue(File(active, "$P/f.abcdef.0.json").exists())
        assertTrue(File(paused, "$P/f.abcdef.0.json").exists())
        // Again, and again: nothing left to do, and nothing goes wrong.
        BlobRules.sweep(root, now, activeFp = "cc33dd44")
        BlobRules.sweep(root, now, activeFp = null)
        assertTrue(File(active, "files/abcdefgh-photo.jpg").exists())
    }

    @Test fun plainPiecesAreOutOfTheWayBeforeAPieceStoreIsBuilt() {
        val root = tmp.newFolder("blobs")
        val g = group(root, "ab12cd34")
        legacyPieces(g)
        val aside = BlobRules.legacyAside(g, now)
        assertNotNull(aside)
        assertFalse("taken out of the group's way in one step", File(g, BlobRules.LEGACY_PIECES).exists())
        assertTrue(BlobRules.isDeadChunks(aside!!.name)); assertEquals(g, aside.parentFile)
        // Asked again (every time a group starts): there is nothing there, and nothing is created.
        assertNull(BlobRules.legacyAside(g, now))
        assertFalse(File(g, BlobRules.LEGACY_PIECES).exists())
        // The store of today's pieces never sees them…
        val s = store(File(g, P))
        assertEquals(listOf(Envelope.chunkId("abcdef", 0)), s.ids())
        // …and whatever a kill kept from being deleted, the next tidy deletes.
        BlobRules.sweep(root, now, activeFp = "ab12cd34")
        assertFalse(aside.exists())
        assertTrue(File(g, "files/abcdefgh-photo.jpg").exists())
        // A left group's pieces of both kinds go when it is left.
        legacyPieces(g)
        val dead = listOf(P, BlobRules.LEGACY_PIECES).map { BlobRules.moveAside(File(g, it), BlobRules.deadChunksName(now, it)) }
        assertTrue(dead.all { it != null && BlobRules.isDeadChunks(it.name) })
        assertEquals(2, dead.toSet().size)
    }

    // ---------------------------------------------------------------- my own files

    private val quiet = object : Transport {
        override fun send(linkId: String, bytes: ByteArray): Long = -1L
        override fun disconnect(linkId: String) {}
    }

    private fun phone(label: String, pieces: ChunkStore, transport: Transport = quiet): Router =
        Router(FakeNet.identity(label), FakeNet.group(), transport, FakeNet().Recorder(), pieces) { now }

    /** [bytes] sealed the way the app sends them, as a file of [r]'s. */
    private fun sealedFile(r: Router, bytes: ByteArray, name: String = "photo.jpg"): Pair<Attachment, List<String>> {
        val s = Blobs.seal(bytes)
        return Attachment.make(r.newFid(), name, "image/jpeg", bytes.size.toLong(), s.pieces.size, 0, 0, "", key = s.key, sha = s.sha) to s.pieces
    }

    private fun send(r: Router, att: Attachment, pieces: List<String>, kept: Boolean = true, caption: String = "", to: String? = null) =
        BlobRules.send(r, att, kept, pieces, caption, to, null, emptyList())

    @Test fun piecesOnDiskAreSealedNeverTheFileItself() {
        val dir = tmp.newFolder("pieces")
        val s = store(dir)
        val a = phone("A", s)
        s.servedBy(a)
        val secret = "Meet at the north gate at noon, bring the blue tent. ".repeat(1_000).toByteArray()
        val (att, pieces) = sealedFile(a, secret)
        assertTrue(send(a, att, pieces) is BlobRules.Sent.Ok)
        val onDisk = dir.listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(att.chunks, onDisk.size)
        assertTrue(att.chunks > 1)
        for (f in onDisk) {
            val text = f.readText()
            assertFalse(text.contains("north gate"))
            val c = Crypto.unb64(JSONObject(text).getString("c"))!!
            assertFalse(String(c, Charsets.ISO_8859_1).contains("north gate"))
            // and neither the file's key nor its checksum lies beside it
            assertFalse(text.contains(Crypto.b64(att.key!!))); assertFalse(text.contains(att.sha))
        }
        // Put together with the key only the message holds, they are the file again.
        val out = File(tmp.newFolder("files"), "${att.fid}-photo.jpg")
        assertSame(BlobRules.Assembly.Ready, putTogether(out, s, a.me.id, att))
        assertArrayEquals(secret, out.readBytes())
    }

    @Test fun mineIsWhatThisPhoneSentNeverWhatAPieceClaims() {
        // A reserve bigger than any disk: this phone keeps nobody's pieces but its own.
        val dir = tmp.newFolder("pieces")
        val s = store(dir, reserve = Long.MAX_VALUE)
        val a = phone("A", s)
        s.servedBy(a)
        // Signed with my own key even — but for a file this phone never sent: a stranger's piece here.
        val claim = FakeNet.envelope("A", Envelope.CHUNK, JSONObject(), now, id = Envelope.chunkId(a.newFid(), 0),
            piece = Crypto.sealPiece(Crypto.randomBytes(32), 0, ByteArray(100)))
        assertTrue(s.put(claim))                 // accepted-and-dropped, never flooded on in a loop
        assertFalse(s.has(claim.id))
        // A file I really send is kept whole, however full the phone.
        val (att, pieces) = sealedFile(a, ByteArray(40_000) { 7 })
        assertTrue(send(a, att, pieces) is BlobRules.Sent.Ok)
        for (i in 0 until att.chunks) assertTrue(s.has(Envelope.chunkId(att.fid, i)))
        // After a restart too: the router rebuilt from its state knows its files again.
        val again = store(dir, reserve = Long.MAX_VALUE)
        val a2 = phone("A", again)
        a2.restore(JSONObject(a.snapshot().toString()))
        again.servedBy(a2)
        val (more, morePieces) = sealedFile(a2, ByteArray(20_000) { 8 })
        assertTrue(send(a2, more, morePieces) is BlobRules.Sent.Ok)
        assertTrue(a2.isMine(att.fid))
        assertTrue(again.put(FakeNet.envelope("A", Envelope.CHUNK, JSONObject(), now, id = Envelope.chunkId(a2.newFid(), 0), piece = pieces[0])))
        assertEquals(att.chunks + more.chunks, again.ids().size)
    }

    @Test fun aFileThatCantBeKeptHereIsNeverAnnounced() {
        val dir = tmp.newFolder("pieces")
        val s = store(dir)
        val frames = ArrayList<String>()
        val a = phone("A", s, object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(String(bytes, Charsets.UTF_8)); return frames.size.toLong() }
            override fun disconnect(linkId: String) {}
        })
        s.servedBy(a)
        a.onLinkUp("L", FakeNet.idOf("B"), "", "tok"); FakeNet.prove(a, "L", "B", "tok")
        frames.clear()
        val (att, pieces) = sealedFile(a, ByteArray(30_000) { 5 })
        // The copy this phone keeps couldn't be written: nothing goes.
        assertTrue(send(a, att, pieces, kept = false) is BlobRules.Sent.NoRoom)
        // The disk takes no piece at all (the folder is gone, and something else is in its place).
        assertTrue(dir.deleteRecursively()); dir.writeText("not a folder")
        assertTrue(send(a, att, pieces) is BlobRules.Sent.NoRoom)
        // Someone this phone has no key for: nothing can be sealed for them, so nothing is sent.
        assertTrue(send(a, att, pieces, to = FakeNet.idOf("C")).let { it is BlobRules.Sent.CantWrite && it.to == FakeNet.idOf("C") })
        assertTrue(frames.isEmpty())
        assertTrue(a.messages.isEmpty()); assertEquals(0, a.carrySize())
        assertFalse(a.isMine(att.fid)); assertNull(a.fileMessage(att.fid))
    }

    @Test fun sendAgainKeepsTheOldMessageWhenTheNewOneCantGo() {
        var refusing = false
        val held = MemoryChunkStore()
        val s = object : ChunkStore by held {
            override fun put(env: Envelope): Boolean = !refusing && held.put(env)
        }
        val a = phone("A", s)
        val bytes = ByteArray(20_000) { 9 }
        val (att0, p0) = sealedFile(a, bytes)
        val old = (send(a, att0, p0, caption = "the view") as BlobRules.Sent.Ok).m
        assertEquals(Message.QUEUED, old.status)
        // Storage full: the new copy can't be kept, so the old message stays exactly as it was.
        refusing = true
        val deleted = ArrayList<String>()
        val delete = { id: String -> deleted.add(id); a.hideMessages(listOf(id)); Unit }
        val (att1, p1) = sealedFile(a, bytes)
        assertTrue(BlobRules.resend(a, old, att1, true, p1, delete) is BlobRules.Sent.NoRoom)
        assertTrue(BlobRules.resend(a, old, att1, false, p1, delete) is BlobRules.Sent.NoRoom)
        assertEquals(emptyList<String>(), deleted)
        assertEquals(listOf(old.id), a.messages.map { it.id })
        assertSame(old, a.fileMessage(att0.fid)); assertNull(a.fileMessage(att1.fid))
        assertEquals(Message.QUEUED, old.status)
        // Room again: the new one goes — and only then the old one.
        refusing = false
        val (att2, p2) = sealedFile(a, bytes)
        val again = BlobRules.resend(a, old, att2, true, p2, delete)
        assertTrue(again is BlobRules.Sent.Ok)
        val m = (again as BlobRules.Sent.Ok).m
        assertEquals(listOf(old.id), deleted)
        assertEquals(listOf(m.id), a.messages.map { it.id })
        assertEquals("the view", m.text); assertEquals(att2.fid, m.att!!.fid)
    }

    @Test fun aFileOfMineTheOldFormatNeverGotOutGoesAgainFromItsCopy() {
        val net = FakeNet(); net.node("A"); net.node("B")
        val old = "k7m2p9qa"   // this phone before 2.4
        val t0 = net.now - hour
        fun unsent(id: String, fid: String, caption: String) = Message(id, Envelope.FILE, old, "A", null, caption, t0,
            Attachment.make(fid, "photo.jpg", "image/jpeg", 30_000, 3, 640, 480, "dGI")).also { it.status = Message.QUEUED }.toJson()
        fun carried(id: String) = JSONObject().put("id", id).put("k", Envelope.FILE).put("o", old).put("on", "A").put("ts", t0).put("h", 0)
        val saved = JSONObject()
            .put("messages", JSONArray(listOf(unsent("oldfile00001", "k3j9m2p4q8", "the view"), unsent("oldfile00002", "m4n5p6q7r8", "the tent"))))
            .put("carry", JSONArray(listOf(carried("oldfile00001"), carried("oldfile00002"))))
            .put("born", JSONObject().put("oldfile00001", t0).put("oldfile00002", t0))
        val a = net.nodes["A"]!!.router
        a.restore(Upgrade.state(saved, net.id("A"), setOf(old), net.now, left = false)!!)
        val files = a.reissueQueued()
        assertEquals(listOf("oldfile00001", "oldfile00002"), files.map { it.id })
        val (theView, theTent) = files
        // The view's copy is still on this phone: sealed again, under a new file id and a new key.
        val view = bytes(30_000)
        val sealed = Blobs.seal(view)
        val att = sealed.again(theView.att!!, a.newFid())
        assertTrue(Attachment.ownedBy(att.fid, a.me.id)); assertTrue(Attachment.sealable(att))
        assertEquals(listOf("photo.jpg", "image/jpeg", 640, 480, "dGI", view.size.toLong(), sealed.pieces.size),
            listOf(att.name, att.mime, att.width, att.height, att.thumb, att.size, att.chunks))
        val m = (BlobRules.resend(a, theView, att, true, sealed.pieces) { id -> a.hideMessages(listOf(id)) } as BlobRules.Sent.Ok).m
        assertEquals("the view", m.text)
        // The tent's copy is gone: it stays exactly as it was, "not sent", for the person to send again or delete.
        assertEquals(listOf(theTent.id, m.id), a.messages.map { it.id })
        assertEquals(Message.QUEUED, theTent.status)
        assertTrue(Ui.gaveUp(a, theTent, net.now))
        // A friend in range gets the view, every piece of it signed by me, and it opens there.
        net.connect("A", "B")
        val b = net.nodes["B"]!!.router
        val got = b.fileMessage(att.fid)!!
        assertEquals(a.me.id, got.from)
        assertTrue(b.fileComplete(got.att!!))
        val out = File(tmp.newFolder("files"), "${att.fid}-photo.jpg")
        assertSame(BlobRules.Assembly.Ready, putTogether(out, b.chunks, got.from, got.att!!))
        assertArrayEquals(view, out.readBytes())
        assertEquals(Message.SENT, m.status)
        assertNull(b.fileMessage(theTent.att!!.fid))
    }

    // ---------------------------------------------------------------- putting a received file together

    /** A file [from] sends: its attachment as the message brings it, and its pieces as they arrive here, signed by [from]. */
    private fun incoming(bytes: ByteArray, from: String = "A", size: Long = bytes.size.toLong(), sha: String? = null): Pair<Attachment, List<Envelope>> {
        val fid = FakeNet.idOf(from) + Crypto.randomId(8)
        val s = Blobs.seal(bytes)
        val att = Attachment.make(fid, "photo.jpg", "image/jpeg", size, s.pieces.size, 0, 0, "", key = s.key, sha = sha ?: s.sha)
        return att to s.pieces.mapIndexed { i, c -> signedPiece(from, fid, i, c) }
    }

    private fun signedPiece(from: String, fid: String, i: Int, c: String): Envelope =
        FakeNet.envelope(from, Envelope.CHUNK, JSONObject(), now, id = Envelope.chunkId(fid, i), piece = c)

    /** The same piece with one byte of what it seals changed: it is still signed, it just no longer opens. */
    private fun tampered(env: Envelope, from: String = "A"): Envelope {
        val c = Crypto.unb64(env.sealed)!!
        c[c.size / 2] = (c[c.size / 2].toInt() xor 1).toByte()
        return signedPiece(from, BlobRules.chunkFid(env.id)!!, env.id.substringAfterLast('.').toInt(), Crypto.b64(c))
    }

    private val random = java.util.Random(7)
    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun putTogether(out: File, pieces: ChunkStore, from: String, att: Attachment): BlobRules.Assembly =
        Blobs.putTogether(out, pieces, from, att, log = { msg, _ -> logged.add(msg) })

    /** Nothing in the folder but [names]: no half-written file was left behind. */
    private fun onlyThere(dir: File, vararg names: String) = assertEquals(names.toSet(), (dir.list() ?: emptyArray()).toSet())

    @Test fun aFileWhosePiecesAllCheckOutIsPutTogether() {
        val s = store(tmp.newFolder("pieces"))
        val bytes = bytes(40_000)
        val (att, envs) = incoming(bytes)
        envs.forEach { assertTrue(s.put(it)) }
        val files = tmp.newFolder("files")
        val out = File(files, "${att.fid}-photo.jpg")
        assertSame(BlobRules.Assembly.Ready, putTogether(out, s, FakeNet.idOf("A"), att))
        assertArrayEquals(bytes, out.readBytes())
        onlyThere(files, out.name)
        // Asked again: it is there, and nothing is read or written.
        assertSame(BlobRules.Assembly.Ready, putTogether(out, s, FakeNet.idOf("A"), att))
    }

    @Test fun aFileStillMissingAPieceWaits() {
        val s = store(tmp.newFolder("pieces"))
        val (att, envs) = incoming(bytes(40_000))
        envs.drop(1).forEach { s.put(it) }
        val files = tmp.newFolder("files")
        val out = File(files, "${att.fid}-photo.jpg")
        val a = putTogether(out, s, FakeNet.idOf("A"), att)
        assertTrue(a is BlobRules.Assembly.Waiting && a.refilled.isEmpty())
        onlyThere(files)
    }

    @Test fun aPieceThatIsntTheSendersIsFetchedAgainOnceThenTheFileCantBeOpened() {
        val s = store(tmp.newFolder("pieces"))
        val bytes = bytes(40_000)
        val (att, envs) = incoming(bytes)
        val id1 = envs[1].id
        // Someone else's piece under the file's piece id — the right bytes even, signed by them.
        val stranger = signedPiece("M", att.fid, 1, envs[1].sealed)
        envs.forEachIndexed { i, e -> assertTrue(s.put(if (i == 1) stranger else e)) }
        val files = tmp.newFolder("files")
        val out = File(files, "${att.fid}-photo.jpg")
        val first = putTogether(out, s, FakeNet.idOf("A"), att)
        assertTrue(first is BlobRules.Assembly.Waiting)
        assertEquals(listOf(1), (first as BlobRules.Assembly.Waiting).refilled)
        // Let go of, and handed back so the next sync brings a friend's copy; nothing was written.
        assertFalse(s.has(id1)); assertFalse(id1 in s.advertised()); assertEquals(listOf(id1), s.takeReleased())
        for (i in 0 until att.chunks) if (i != 1) assertTrue(s.has(envs[i].id))
        onlyThere(files)
        // The message remembers it was fetched again. Every friend has the same copy: it comes back.
        att.markRefilled(first.refilled)
        assertTrue(s.put(stranger))
        assertSame(BlobRules.Assembly.Bad, putTogether(out, s, FakeNet.idOf("A"), att))
        // The pieces stay — friends still pass them on — and nothing of it is in the files.
        for (e in envs) assertTrue(s.has(e.id))
        assertEquals(emptyList<String>(), s.takeReleased())
        onlyThere(files)
        // Marked for good: never tried again, even if the right piece turned up after all.
        att.markFailed()
        s.forget(id1); s.takeReleased(); assertTrue(s.put(envs[1]))
        assertSame(BlobRules.Assembly.Bad, putTogether(out, s, FakeNet.idOf("A"), att))
        onlyThere(files)
    }

    @Test fun aTamperedPieceIsFetchedAgainOnceThenTheFileCantBeOpened() {
        val s = store(tmp.newFolder("pieces"))
        val files = tmp.newFolder("files")
        // A friend's good copy arrives after the bad one was let go of: the file is whole.
        val bytes = bytes(40_000)
        val (att, envs) = incoming(bytes)
        envs.forEachIndexed { i, e -> s.put(if (i == 0) tampered(e) else e) }
        val out = File(files, "${att.fid}-photo.jpg")
        val first = putTogether(out, s, FakeNet.idOf("A"), att) as BlobRules.Assembly.Waiting
        assertEquals(listOf(0), first.refilled)
        assertEquals(listOf(envs[0].id), s.takeReleased())
        att.markRefilled(first.refilled)
        assertTrue(s.put(envs[0]))
        assertSame(BlobRules.Assembly.Ready, putTogether(out, s, FakeNet.idOf("A"), att))
        assertArrayEquals(bytes, out.readBytes())
        // Every copy around is the bad one: it fails again, and that is that.
        val (att2, envs2) = incoming(bytes(30_000))
        envs2.forEachIndexed { i, e -> s.put(if (i == 0 || i == 2) tampered(e) else e) }
        val out2 = File(files, "${att2.fid}-photo.jpg")
        val again = putTogether(out2, s, FakeNet.idOf("A"), att2) as BlobRules.Assembly.Waiting
        assertEquals("every bad piece is fetched again in one go", listOf(0, 2), again.refilled)
        att2.markRefilled(again.refilled)
        s.put(tampered(envs2[0])); s.put(tampered(envs2[2]))
        assertSame(BlobRules.Assembly.Bad, putTogether(out2, s, FakeNet.idOf("A"), att2))
        for (e in envs2) assertTrue(s.has(e.id))
        onlyThere(files, out.name)
    }

    @Test fun aWholeThatIsntWhatItsMessageSaysCantBeOpenedAndIsNeverRefilled() {
        val s = store(tmp.newFolder("pieces"))
        val files = tmp.newFolder("files")
        val bytes = bytes(40_000)
        for ((att, envs) in listOf(incoming(bytes, size = bytes.size + 1L), incoming(bytes, size = bytes.size - 1L),
                                   incoming(bytes, sha = Crypto.sha256Hex(bytes(40_000))))) {
            envs.forEach { s.put(it) }
            val out = File(files, "${att.fid}-photo.jpg")
            // Every piece opens, so nothing would come of fetching one again.
            assertSame(BlobRules.Assembly.Bad, putTogether(out, s, FakeNet.idOf("A"), att))
            for (e in envs) assertTrue(s.has(e.id))
            assertEquals(emptyList<String>(), s.takeReleased())
            onlyThere(files)
        }
    }

    @Test fun aFileIsOnlyEverTheMessageOfWhoeverMadeIt() {
        val s = store(tmp.newFolder("pieces"))
        val bytes = bytes(20_000)
        val (att, envs) = incoming(bytes)
        envs.forEach { s.put(it) }
        val files = tmp.newFolder("files")
        val out = File(files, "${att.fid}-photo.jpg").apply { writeText("Asha's photo, put together earlier") }
        // A message from M naming A's file: never handed A's file, and never writes over it.
        assertTrue(putTogether(out, s, FakeNet.idOf("M"), att) is BlobRules.Assembly.Waiting)
        assertEquals("Asha's photo, put together earlier", out.readText())
        assertSame(BlobRules.Assembly.Ready, putTogether(out, s, FakeNet.idOf("A"), att))
        // A message from before 2.4 (no key) has no pieces to come: left alone, nothing marked.
        val legacy = Attachment.make("k3j9m2p4q8", "photo.jpg", "image/jpeg", 10, 1, 0, 0, "")
        assertTrue(putTogether(File(files, "k3j9m2p4q8-photo.jpg"), s, "ab12cd34", legacy) is BlobRules.Assembly.Waiting)
    }

    @Test fun aFileThatCantBeWrittenIsTriedAgainLater() {
        val s = store(tmp.newFolder("pieces"))
        val (att, envs) = incoming(bytes(20_000))
        envs.forEach { s.put(it) }
        // Its folder can't be made (something else is in its place): no room to write, most likely.
        val blocked = File(tmp.root, "files").apply { writeText("not a folder") }
        assertSame(BlobRules.Assembly.Failed, putTogether(File(blocked, "${att.fid}-photo.jpg"), s, FakeNet.idOf("A"), att))
        // Nothing was let go of: the pieces are fine, and the try again needs them.
        for (e in envs) assertTrue(s.has(e.id))
        assertEquals(emptyList<String>(), s.takeReleased())
        // Less and less often, but never less than every half hour: the person may free space any time.
        assertEquals(BlobRules.RETRY_FIRST_MS, BlobRules.retryAfter(1))
        assertEquals(2 * BlobRules.RETRY_FIRST_MS, BlobRules.retryAfter(2))
        assertEquals(BlobRules.RETRY_FIRST_MS, BlobRules.retryAfter(0))
        for (n in 1..200) assertTrue(BlobRules.retryAfter(n + 1) >= BlobRules.retryAfter(n))
        assertEquals(BlobRules.RETRY_MAX_MS, BlobRules.retryAfter(200))
    }

    @Test fun aFilesMarksStayWithItsMessageAndNoSenderCanSetThem() {
        val (att, _) = incoming(bytes(100))
        att.markRefilled(listOf(3)); att.markRefilled(listOf(1, 3)); att.markFailed()
        val m = Message(FakeNet.newId("A"), Envelope.FILE, FakeNet.idOf("A"), "Asha", null, "", now, att)
        val back = Message.fromJson(JSONObject(m.toJson().toString())).att!!
        assertTrue(back.failed); assertEquals(setOf(1, 3), back.refilled)
        // …and through the group's history, where older messages go.
        val history = History(tmp.newFolder("history"))
        assertTrue(history.append(listOf(m.toJson())))
        val filed = Message.fromJson(history.read(history.segments().last()).single()).att!!
        assertTrue(filed.failed); assertEquals(setOf(1, 3), filed.refilled)
        // A sender who puts the marks in their own message marks nothing on anyone's phone.
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val (sent, pieces) = FakeNet.makeFile(a, ByteArray(4_000) { 3 })
        sent.markFailed(); sent.markRefilled(listOf(0))
        assertNotNull(a.sendFile(sent, pieces, "")); net.pump()
        val got = b.fileMessage(sent.fid)!!.att!!
        assertFalse(got.failed); assertEquals(emptySet<Int>(), got.refilled)
    }
}
