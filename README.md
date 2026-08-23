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

## Use it

There are only three things to know:

| Step | What you do |
|---|---|
| **Start a group** | One person taps *Start a new group*. They get a **3-word code**, like `tiger river lamp`, and a QR. |
| **Join** | Everyone else taps *Join a group* and types the three words (or scans the QR). That's it. |
| **Chat** | One group chat, like a WhatsApp group. Tap a name for a private chat. |

Phones find each other on their own. Walk away and come back — the chat catches up by itself.
The ticks never lie: ◷ while it waits for a phone in range, ✓ when it's on its way, ✓✓ when
phones confirm. **Tap your own message** to see exactly who has it, by name.

**The Outside World** (menu) — if **anyone** in the group has internet, everyone can use a
sliver of it: read a web page as plain text, or send an SMS/email home through the friend's
phone. Answers hop back and appear in the group chat for all.

## Built for a crowd, not just a trek

A protocol that's lovely for 8 hikers can melt at a festival. Hopline changes behaviour as
the group grows, and every phone follows the same rules on its own:

- **Delivery receipts** — in a small group (under ~13 people) every phone confirms every
  message, which powers "Reached 7 of 9" and named read-outs. In a crowd that would be N²
  traffic, so chat receipts switch off automatically; private messages still confirm
  person-to-person at any size.
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
- Every phone **carries every message for 48 hours**. When two phones link up they swap
  inventories and fill each other's gaps. That is what makes a chain that keeps breaking and
  re-forming still deliver everything — a person walking between two groups literally carries
  the backlog in their pocket.
- In small groups, delivery receipts flow back the same way, so "Reached 7 of 9" is real, not a guess.
- A foreground service keeps relaying with the screen off.

The mesh logic is plain Kotlin with no Android dependencies, so the whole thing is tested on a
laptop with simulated phones: `./gradlew test` runs a chain of five, breaks it, heals it, walks
a courier between two separated groups, rejects a phone with the wrong code, drops a forged
message, routes an errand to the one phone with internet, and more.

## Honest limits

- **Range per hop is Bluetooth range**: roughly 20–40 m between phones in the open, less through
  bodies, trees or walls. A group strung out along a trail forms a chain naturally; two groups
  500 m apart with nobody between them are two separate groups until someone walks across.
- **Android only.** iPhones can't join — Apple provides no equivalent of Nearby Connections to
  third-party apps, and iOS kills background radio work.
- **No end-to-end encryption.** Anyone with the 3-word code is in the group. Treat it as a
  group walkie-talkie, not a secure channel.
- **Bluetooth stacks are flaky.** Links sometimes take 10–60 s to form, and some phones refuse
  to link until Bluetooth is toggled off and on. Hopline retries and restarts the radio on its
  own, but it is not instant.
- **Battery**: expect roughly 5–8 % per hour while actively relaying for a group. Keep power
  banks. If a phone dies, messages it was carrying are still on every other phone that had them.
- **Text only**, on purpose. One photo is bigger than every message the group will send in a week.
- Messages older than 48 hours are no longer carried to people who missed them.

## Build from source

```bash
# needs JDK 17+, Android SDK (platform 34, build-tools 34)
./gradlew assembleDebug          # app/build/outputs/apk/debug/Hopline-debug.apk
./gradlew test                   # simulated-group tests
./gradlew assembleRelease        # needs keystore/hopline.jks — see keystore/README.txt
```

`keystore/hopline.jks` is **not** in the repo. Create your own with `keytool` (see
`keystore/README.txt`); the passwords go in `keystore/keystore.properties`.

## Layout

```
app/src/main/java/app/hopline/
  core/      Crypto (group key, signing), Words (the 3-word codes)
  mesh/      Model, Router (flooding, carry, receipts, errands), NearbyTransport (radio)
  data/      Store (name, group, saved state)
  service/   Core (glue), MeshService (foreground), Errands (read a page / send out), Notifications
  ui/        one Activity per screen; MessageAdapter
app/src/test/ RouterTest — the simulated group
```

## License

MIT — see [LICENSE](LICENSE). Made for [ForPublicOrg](https://github.com/ForPublicOrg).
