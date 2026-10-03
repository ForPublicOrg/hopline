# Hopline

**Chat with everyone around you when there's no signal. Messages hop from phone to phone.**

Hopline is an Android app for treks, camps, festivals, fairs, rallies, power cuts — anywhere
people with phones have no mobile signal and no WiFi. Phones link to each other directly over
Bluetooth / WiFi, and every phone passes messages along for the others, so a message can travel
down a line of hikers or across a packed ground, far beyond the range of any single phone.
It works the same for five friends or a crowd — the protocol automatically sheds overhead
as the group grows (see *Built for a crowd* below).

No internet. No mobile signal. No account. No server. No hotspot to set up.

<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="Hopline icon"></p>

---

## Install

1. Download **`Hopline.apk`** from the [latest release](../../releases/latest).
2. Open it on the phone. If Android asks, allow installing from this source.
3. Open Hopline, type your name, tap **Allow** when it asks for Nearby devices.

**Do this at home, on every phone, before you leave signal.** Each phone also needs:

- Android 8.0 or newer
- Google Play services (almost every Android phone outside China has it)
- Bluetooth **on** and WiFi **on** (WiFi does not need to be connected to anything)

## Updates

Hopline keeps itself up to date from this repository's releases — and never makes you update.

- **What it asks.** When the phone has internet, Hopline asks GitHub (`api.github.com`) whether a
  newer release exists, at most a few times a day. Nothing about you or your group is sent; GitHub
  sees the phone's IP address, like any website does. Your group's messages still never touch a
  server.
- **What it downloads.** On WiFi it fetches the new `Hopline.apk` by itself; on mobile data only
  when you tap **Download** (the banner says how big it is).
- **What it checks.** Before it is offered, the download must be exactly the file GitHub published
  and be signed with the **same key as the Hopline already on the phone**. Anything else is thrown
  away and never offered.
- **You install it.** Nothing installs until you tap **Install**. The first time, Android asks
  you to confirm, and whether Hopline may install apps (the "install unknown apps" permission). On
  Android 12 and later, updates after that first one start on your tap alone, with no second
  question from Android — the tap is the confirmation. Your chats stay as they are, and the mesh
  comes back by itself afterwards.
- **Not now?** Close the banner: it stays away for that version, and **Settings → About** still
  has it whenever you like. *Update Hopline automatically* in Settings switches the looking off
  altogether (*Check for updates* still works by hand). A phone that never updates keeps working
  exactly as before.

## Use it

There are only three things to know:

| Step | What you do |
|---|---|
| **Start a group** | One person taps *Start a new group*. They get a **3-word code**, like `tiger river lamp`, and a QR. |
| **Join** | Everyone else taps *Join a group* and types the three words (or scans the QR). That's it. |
| **Chat** | A familiar chat app: group chat, private chats, photos and small files. |

Phones find each other on their own. Walk away and come back — the chat catches up by itself.
The ticks never lie: ◷ while it waits for a phone in range, ✓ when it's on its way, ✓✓ when
phones confirm. **Tap your own message** to see exactly who has it, by name.

**Photos and files** — tap the paperclip. Photos are shrunk hard (≈0.3 MB) so they hop in
seconds; files up to 2 MB are carried in pieces and reassembled on every phone, even ones that
were out of range when you sent them. A photo's ✓ only appears when the *whole* photo is on the
other phone.

**Location** — paperclip → *Location*. Send where you are (GPS works with no signal and no
internet) or name a meeting point by typing coordinates or pasting a Google Maps link. Everyone
sees the pin with **how far it is and which way** ("1.2 km away · north-east of you") right in
the chat; tapping it opens Google Maps (or any maps app). A location is a hundred bytes, so it
works even in crowds where photos switch off — and old Hopline versions just see a maps link
that opens the same spot.

**Live location** — paperclip → *Location* → *Share live location* (15 min, 1 h or 8 h).
Your position rides the group's regular presence beacons — no extra radio traffic — and the
People screen shows everyone who's sharing: "1.2 km away · north-east of you", updating as you
both walk. A banner in the chat (and the always-on notification) reminds you while you share;
one tap stops it, and it stops itself when the time is up.

**Voice notes** — tap the mic, talk (up to a minute), send. Clips are tiny (~180 KB/min) and
hop the mesh like photos do; the play bar honestly fills in as the pieces arrive.

**Replies, reactions, @mentions** — swipe any message right to reply with a quote (tap the
quote to jump back to the original). Long-press any message — text, photo, file, voice note or
pin — and a reaction bar springs up over it: 👍 ❤️ 😂 😮 😢 🙏, or **+** for every emoji. Double-tap
a bubble to ❤️ it. The pill under a bubble shows the top reactions and a count; tap it to see who
reacted (tap your own to take it back). You get a notification when someone reacts to your
message. Type `@` to mention someone by name; being mentioned always buzzes, even for messages
that arrive as old backlog.

**Names** — change your name any time (Settings, or the "You" row in People): everyone sees the
new name, on your old messages too. Tap the group chat's header for **Group info**: rename the
group for everyone (a "Asha renamed the group" line appears in the chat, and phones that were out
of range pick it up when they come back), mute it, see who's in it, clear the chat, or leave the
group.

**Tidy chats** — long-press for *Reply privately*, *Copy*, *Save to phone*, *Share*, *Info* and
*Delete for me*. Mute a chat for 8 hours, a week or always (being @mentioned still gets through). A chat opens
at the first message you haven't read, with an "unread messages" marker — so backlog that hopped in
after a reunion is never skipped. Reply or mark as read straight from the notification.

**Your whole chat, however long** — a chat opens on its latest messages; scroll up and the earlier
ones load from the phone's storage as you go. Nothing drops off the end. What reached your phone
stays on it until *you* delete it: one message (*Delete for me*), one chat (*Clear chat*), or a
whole group you've left (*Delete group*).

**More than one group** — Home shows every group you've saved. The radio serves one group at a
time; tap a paused group to switch. Nothing is deleted when you switch — each group keeps its
own history, unread counts and files.

**Leaving keeps the chat** — *Leave group* (in Group info or Settings, or press and hold a paused
group on Home) takes your phone off that group: it stops getting the group's messages and stops
passing them along. Everything already on the phone stays — the group chat, your private chats,
photos, files and voice notes — under **Groups you left** on Home. You can read it, copy from it,
save and share its photos and files, and delete messages for yourself; you can't write in it.
Nobody is told that you left: to the group, your phone has simply walked away.

- **Rejoin** is one tap and one confirm — the phone still knows the three words (typing or
  scanning them again asks the same question). The chat carries on under a "You left" / "You
  rejoined" line, and nearby phones fill in whatever the group is still carrying. A message of
  yours that hadn't gone out when you left is *not* sent behind your back: it reads "Not sent",
  with *Send again* if you still mean it.
- **Delete group** is a separate step, offered only for a group you have already left. It is the
  one thing that takes a whole group — every message, photo and file — off the phone, and it asks
  first. Copies you saved to Pictures or Downloads are yours, and stay.
- With every group left, Hopline still opens — on your old chats, with no radio running and no
  permissions needed — and *Start or join a group* is one tap away.

**Shared internet** — if **anyone** in the group gets signal, everyone can use a sliver of it:

- **Weather here** — one tap. Your GPS works with no signal; the phone with signal fetches a
  3-day forecast for exactly where you stand (storms, snow and rain windows called out).
- **Text home** — "I'm OK" to Mom, with your location and the time you wrote it. Any phone with
  even one bar of plain mobile service can send it — no data needed — and its owner taps Send.
- **Look it up / read a page** — type a question or paste a link. Pages come back as clean,
  readable text with their links numbered, so you can ask for the next page with one tap.

Ask any time, even when nobody has signal: the request travels with the group like a message, and
the first phone that gets signal — maybe tomorrow on the ridge, maybe your own — picks it up. If
that phone goes quiet, another takes over (a text home waits for you to say so, so Mom never gets
it twice). The answer comes back **only to you** (share it to the group with one
tap if it's useful to everyone). Your own phone helps only while "Share my internet" is on, within
a daily allowance you choose (5 MB by default — texts home cost no data and carry on after it runs
out), never while roaming unless you allow it, and you can see exactly what it fetched and for whom.

**Pay without internet** — Home → *Pay without internet* (India). With a bar of ordinary phone
signal — on Airtel, Vi, BSNL or MTNL, not Jio — you can pay by UPI with **\*99#**, the banks' own
UPI service, which runs on plain phone signal with no data at all. Tap *Pay with \*99#*:

- **Your bank asks, you answer.** The Phone app opens with `*99*1*3#` typed in. You press call,
  type the UPI ID (a shop's QR sticker usually has it printed under the code), see the name your
  bank has for it, type the amount, and enter your UPI PIN in your phone company's own box. Hopline
  asks for none of it, never sees the PIN, and never puts anything in the Phone app but `*99#` or
  `*99*1*3#`.
- **The steps and the errors, on one page.** What each thing the \*99# box may say means, and how
  to check before paying twice. Your bank's message and SMS are the proof it went through.
- **First time?** *Set up \*99#* opens the Phone app with `*99#` typed in: you'll need your debit
  card, and no internet.

## Built for a crowd, not just a trek

A protocol that's lovely for 8 hikers can melt at a festival. Hopline changes behaviour as
the group grows, and every phone follows the same rules on its own:

- **Delivery receipts** — in a small group (under ~13 people) every phone confirms every
  message, which powers "Reached 7 of 9" and named read-outs. In a crowd that would be N²
  traffic, so chat receipts switch off automatically; private messages still confirm
  person-to-person at any size.
- **Photos and files** — switch off automatically once the group outgrows ~30 people; media
  in a crowd would drown the radios everyone shares.
- **Presence beacons** — "I'm here" goes out every 30 s in a small group and slows to every
  5 minutes in a crowd of hundreds, so the radios carry messages instead of roll calls.
- **Dense mesh, short paths** — each phone keeps up to 6 direct links; in a packed venue the
  network's diameter grows only logarithmically, and messages allow up to 32 hops.
- **People list** — gets a search box once the group outgrows a trekking party.

## How it works (for the curious)

- Phones link with Google's **Nearby Connections** in cluster mode — a web of direct
  Bluetooth/WiFi links, no access point. Each phone keeps up to 6 links.
- Every message is **signed with a key derived from the 3-word code** and **flooded** to every
  link. A phone with the wrong code can't join, can't read, can't forge.
- Photos and files ride the same flood as **numbered ~19 KB chunks** (under the radio's 32 KB
  payload cap). Chunks are carried on disk and gap-filled like everything else, so an image can
  hop through phones whose owners never open it.
- Every phone **carries every message for 48 hours**. When two phones link up — and then again
  every minute or so while they stay linked — they swap inventories and fill each other's gaps.
  That periodic re-check is what heals a message a flaky (or jammed) radio dropped mid-flood
  without waiting for the link to break and re-form. It is also what makes a chain that keeps
  breaking and re-forming still deliver everything — a person walking between two groups literally
  carries the backlog in their pocket, and a message can hop through any number of hand-offs.
- **Carrying is not keeping.** After 48 hours a message stops being handed to phones that missed
  it, but it stays in the chat on every phone that got it. A phone keeps a group's latest 2,000
  messages at hand and files older ones in plain numbered files on its own storage; the chat reads
  them back, a page at a time, as you scroll up.
- Leaving a group puts nothing on the air — no goodbye, no new kind of message — so 2.1 and 2.2
  phones see a leaver exactly as a phone that walked away. The leaver's phone keeps the chat and
  lets go of what it only held for the others: the backlog, other people's requests, the pieces of
  files it was relaying. On a rejoin the group hands that backlog back, and the phone carries it
  again — its own old receipts and requests included — without showing or announcing any of it a
  second time.
- In small groups, delivery receipts flow back the same way, so "Reached 7 of 9" is real, not a guess.
- Names are last-writer-wins on the writer's own clock, so a rename can never be undone by old
  messages arriving late through gap-fill. A group rename also counts the renames before it, so a
  rename made after another always wins — even against a phone whose clock is hours off.
- Shared-internet requests ride the same carried envelopes: an open request every phone carries,
  a live "I'm on it" claim with a lease (so only one phone spends data, and a quiet one is replaced),
  and a private, compressed answer that fits in one radio frame. 2.0/2.1 phones carry all of it
  unchanged and are still served the old way.
- Each link's handshake proof is bound to that exact Nearby connection, so nobody can relay one
  member's proof to pose as them.
- A foreground service keeps relaying with the screen off.

The mesh logic is plain Kotlin with no Android dependencies, so the whole thing is tested on a
laptop with simulated phones: `./gradlew test` runs a chain of five, breaks it, heals it, walks
a courier between two separated groups, rejects a phone with the wrong code, drops a forged
message, hops a photo down the line in pieces, carries a request to a phone that only gets signal
later and hands it on when that phone goes quiet, refuses a relayed handshake, keeps a rename from
being undone by old backlog, leaves a group and rejoins it without showing a message twice or
sending one that was never meant to go, files a long chat's older messages away without losing
one, and more.

## Honest limits

- **Range per hop is Bluetooth range**: roughly 20–40 m between phones in the open, less through
  bodies, trees or walls. A group strung out along a trail forms a chain naturally; two groups
  500 m apart with nobody between them are two separate groups until someone walks across.
- **Android only.** iPhones can't join — Apple provides no equivalent of Nearby Connections to
  third-party apps, and iOS kills background radio work.
- **No end-to-end encryption.** Anyone with the 3-word code is in the group. Private chats are
  hidden from other people's screens, but every phone in the group carries them (unencrypted) to
  pass them along. A determined person nearby with special tools could also guess a 3-word code from
  the radio signal. Treat Hopline as a group walkie-talkie, not a secure channel.
- **Shared internet is honest, not magic.** Pages are text only; sites that only work in a full
  browser say so. The person whose phone has signal can see what you asked for.
- **Paying without internet rides on \*99#, with its limits.** Up to ₹5,000 at a time (your bank may
  allow less in a day). It works on Airtel, Vi, BSNL and MTNL — **not on Jio** — and only from the
  SIM whose number your bank has. Paying a bill shown on a card machine or a website this way may
  fail, or reach the shop without saying which bill it paid. Android lets no app answer the \*99#
  menus, so you type the UPI ID, amount and PIN yourself, and the PIN shows as you type it. Hopline
  can't see whether the payment went through — your bank's message is the proof.
- **Bluetooth stacks are flaky.** Links sometimes take 10–60 s to form, and some phones refuse
  to link until Bluetooth is toggled off and on. Hopline retries and restarts the radio on its
  own, but it is not instant.
- **Battery**: expect roughly 5–8 % per hour while actively relaying for a group. Keep power
  banks. If a phone dies, messages it was carrying are still on every other phone that had them.
- **Media is deliberately small.** Photos are recompressed to ≈0.3 MB and files cap at 2 MB —
  one full-size photo would take longer to hop than everything the group says in a day.
- **One group at a time on the radio.** You can save many groups and switch instantly, but the
  phone only relays for the group you're in.
- Messages older than 48 hours are no longer carried to people who missed them. (What already
  reached your phone stays there.)
- **Your chats live on your phone, and only there.** No server, no backup: uninstalling Hopline,
  clearing its data or losing the phone loses them.
- **A group you left stops where you left it.** Its chat shows what your phone had at that moment
  and nothing said after; a photo or file that hadn't fully arrived stays missing. Rejoining brings
  back only what the group is still carrying — the last 48 hours — so a longer absence leaves a
  gap, marked by the "You left" line.
- **Leaving is silent.** There is no member list anywhere to take you off: the others aren't told,
  what you sent stays on their phones, and anyone who has the three words can join again. Groups
  you left on Hopline 2.2 or earlier were deleted at the time and can't be brought back.

## Build from source

```bash
# needs JDK 17+, Android SDK (platform 34, build-tools 34)
./gradlew assembleDebug          # app/build/outputs/apk/debug/Hopline-debug.apk
./gradlew test                   # simulated-group tests
./gradlew assembleRelease        # needs keystore/hopline.jks — see keystore/README.txt
```

`keystore/hopline.jks` is **not** in the repo. Create your own with `keytool` (see
`keystore/README.txt`); the passwords go in `keystore/keystore.properties`.

A debug build never updates itself (Settings says "Test build — updates are off"): it is signed
with a debug key, so a released APK could not replace it anyway. A build signed with your own key
only accepts updates signed with that key.

## Layout

```
app/src/main/java/app/hopline/
  core/      Crypto (group key, signing), Words (the 3-word codes), Names (cleaning names from the air),
             WebText / Weather / Search / SmsText / SafeUrl / HelperLimits (the shared-internet engines, pure Kotlin),
             Update (reading GitHub's release, which version is newer, is the download the published one),
             Upi (the only two codes Hopline may dial, and which phones *99# works on)
  mesh/      Model, Router (flooding, carry, receipts, files, errands, the live window), ChunkStore, NearbyTransport,
             Archive (what a group's saved state becomes when you leave it)
  data/      Store (name, saved groups, read marks, per-group state), GroupRules (the groups you're in, the ones
             you left, which one the radio serves), History (a chat's older messages, on disk)
  service/   Core (glue), MeshService (foreground), Blobs (photo shrinking, chunk disk store),
             HistoryRules (moving old messages to the history, reading them back),
             Errands + Fetch (run requests safely for the group), Cell (mobile service), Notifications,
             Updater (checks GitHub, downloads, verifies, installs on your tap)
  ui/        Home (all chats), Chat (group + private, and read-only for a group you left), EarlierPages,
             MessageMenu + ReactionSheets, People, Group info, Internet + Reader, Pay (UPI over *99#),
             Settings, onboarding
app/src/test/ RouterTest, ProtocolTest, ErrandTest, ArchiveTest, SpillTest — the simulated group;
              GroupRulesTest, HistoryTest, HistoryRulesTest, EarlierPagesTest — leaving, rejoining and long chats;
              CoreTextTest — the engines on saved pages; UpdateTest — the updater's rules on a saved GitHub answer;
              UpiTest — only *99# itself is ever dialled
```

## License

MIT — see [LICENSE](LICENSE). Made for [ForPublicOrg](https://github.com/ForPublicOrg).
