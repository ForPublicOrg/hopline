package app.hopline.service

import app.hopline.mesh.Envelope
import app.hopline.mesh.Router
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** The disk side of file pieces: which names may touch the disk, how long pieces live, and the space they may take. */
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
            .put("ts", ts).put("h", 0).put("p", JSONObject().put("fid", fid).put("i", i).put("d", data))
        if (to != null) j.put("to", to)
        return Envelope(j)
    }

    private fun rawPiece(id: String): Envelope = Envelope(JSONObject().put("id", id).put("k", Envelope.CHUNK).put("o", "x")
        .put("ts", now).put("h", 0).put("p", JSONObject().put("fid", "abcdef").put("i", 0).put("d", "QUJD")))

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
        assertEquals("SGVsbG8=", back!!.payload.getString("d"))
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

    /** A group's folder as the app lays it out: carried pieces, and the photos and files of its chat. */
    private fun group(root: File, fp: String, pieceAge: Long = hour): File = File(root, fp).also { g ->
        File(g, "chunks").mkdirs(); File(g, "files").mkdirs()
        File(g, "chunks/f.abcdef.0.json").apply { writeText("{}"); assertTrue(setLastModified(now - pieceAge)) }
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
        val dead = BlobRules.moveAside(File(left, "chunks"), BlobRules.deadChunksName(now))
        assertNotNull(dead)
        assertTrue(BlobRules.isDeadChunks(dead!!.name)); assertEquals(left, dead.parentFile)
        assertFalse(File(left, "chunks").exists())
        assertTrue("moved whole, to be deleted at leisure", File(dead, "f.abcdef.0.json").exists())
        assertTrue(File(left, "files/abcdefgh-photo.jpg").exists())
        assertTrue(File(other, "chunks/f.abcdef.0.json").exists()); assertTrue(File(other, "files/abcdefgh-photo.jpg").exists())
        // Joined again a moment later: a fresh, empty folder under the old name, out of the slow delete's reach.
        val again = store(File(left, "chunks"))
        assertEquals(emptyList<String>(), again.ids())
        assertTrue(again.put(piece("ghijkm", 0)))
        assertTrue(dead.deleteRecursively())
        assertNotNull(again.get(Envelope.chunkId("ghijkm", 0)))
        assertTrue(File(left, "files/abcdefgh-photo.jpg").exists())
        // nothing there: nothing to move, and nothing is created by asking
        assertNull(BlobRules.moveAside(File(root, "aa11bb22/chunks"), BlobRules.deadChunksName(now)))
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
        assertTrue(File(again, "files/abcdefgh-photo.jpg").exists()); assertTrue(File(again, "chunks/f.abcdef.0.json").exists())
        assertTrue(File(other, "files/abcdefgh-photo.jpg").exists())
        // Neither name can ever be a group's or a live piece folder's own.
        assertFalse(BlobRules.isGoneGroup("ab12cd34")); assertFalse(BlobRules.isDeadChunks("chunks")); assertFalse(BlobRules.isDeadChunks("files"))
    }

    @Test fun aSweepFinishesWhatAKillCutShortAndTouchesNoKeptFile() {
        val root = tmp.newFolder("blobs")
        val active = group(root, "cc33dd44", pieceAge = 60 * hour)
        val paused = group(root, "aa11bb22", pieceAge = 60 * hour)
        File(paused, "chunks/f.abcdef.1.json").apply { writeText("{}"); assertTrue(setLastModified(now - 2 * hour)) }
        // left, and killed after the pieces were moved aside but before they were deleted
        val left = group(root, "ab12cd34")
        assertNotNull(BlobRules.moveAside(File(left, "chunks"), BlobRules.deadChunksName(now - day)))
        // being deleted by this very run of the app: not the sweep's business
        val closing = group(root, "ef56ab78", pieceAge = 60 * hour)
        BlobRules.sweep(root, now, activeFp = "cc33dd44") { it == "ef56ab78" }
        // the group on the radio expires its own pieces; a paused one has nobody to do it
        assertTrue(File(active, "chunks/f.abcdef.0.json").exists())
        assertFalse(File(paused, "chunks/f.abcdef.0.json").exists())
        assertTrue(File(paused, "chunks/f.abcdef.1.json").exists())
        assertEquals(listOf("files"), left.list()!!.toList())
        assertTrue(File(closing, "chunks/f.abcdef.0.json").exists())
        for (g in listOf(active, paused, left, closing)) assertTrue(File(g, "files/abcdefgh-photo.jpg").exists())
        // a folder that never existed is no trouble
        BlobRules.sweep(File(root, "nowhere"), now, activeFp = null)
    }
}
