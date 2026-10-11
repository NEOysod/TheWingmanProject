# The Wingman Project: technical reference

How the mod works, part by part, for anyone reading or changing the code. Player-facing instructions are in
`docs/USER_GUIDE.md`; what changed between versions is in `CHANGELOG.md`.

Everything in `src/wingman/net/` is tested without the game (`test/NetTest.java`, `test/AttackTest.java`).
Everything that touches the game is compiled against the 0.98a-RC8 API and tested by playing.

---

## 1. Design in brief

**The host decides, joiners show.** The host's game runs the only real battle. Each joiner's game runs a copy
in which every ship, fighter and missile is a *puppet*: built from the host's description, moved to where the
host says, damaged only as the host says, and destroyed only when the host says. A joiner's controls go to the
host, where they fly the real ship.

**Scripts run everywhere; results come from the host.** The API has no way to copy particles, sounds or other
drawn effects from one PC to another. So ship systems, hullmods, weapon and missile scripts run on every
copy, which produces the right look, while positions, hull, flux, armor, deaths and orders are always the host's.

Three constraints shape almost every detail:

1. **The sandbox.** Starsector's script loader (`com.fs.starfarer.loading.scripts.B`, 0.98a-RC8) refuses
   most of `java.io` (only `IOException`, `InputStream`, `OutputStream` are used), `java.lang.reflect`, the
   `java.nio.file` file classes, `javax.script` and `java.util.prefs`. `java.lang.invoke` is not refused;
   Wingman uses `MethodHandles` for exactly one private field (`MessageTap`). Hence the hand-written byte
   packing (`Out`/`In`), files only through `Global.getSettings()` and `saves/common`, no lambdas, and risky classes kept alone (`Zip` for `java.util.zip`, `Udp` for `DatagramSocket`,
   `Transport` to probe sockets) so that a refusal switches off one feature rather than the mod.
2. **The game thread never waits.** Network I/O runs on background threads with queues in between; expensive
   reads happen on demand; an idle host does almost nothing.
3. **Fail soft.** Each feature has its own `try/catch` and `broken` flag; a failure switches off that feature
   for the battle with one log line. Section 11 lists the rings.

---

## 2. Code map

| Area | Classes |
| --- | --- |
| Battle plugins (registered in `data/config/settings.json`) | `WingmanHostPlugin` (host side of every battle), `WingmanTitlePlugin` (title screen panel); `WingmanClientPlugin` is attached by the join mission |
| Host helpers | `RemotePilotAI`, `HostDamage`, `HostExtras`, `HostEnvironment`, `HostOrders`, `ShipStats` |
| Joiner helpers | `PuppetAI`, `PuppetMissileAI`, `GuardedMissileAI`, `HullGuard`, `LocalDamage`, `NoDamage`, `ClientEnvironment`, `ClientOrders`, `CampaignStandIn`, `ObjectiveMirror`, `MirrorObjectiveEffect`, `MessageTap`, `MessageSpy`, `BayLink`, `Subsystems`, `SystemWatch`, `Compat`, `Groups` |
| Title screen and joining | `WingmanTitlePlugin`, `JoinForm`, `SavedJoin`, `JoinSetup`, `TitleLauncher`, `data/missions/wingman_join/MissionDefinition.java` |
| Settings and output | `WingmanConfig`, `Settings`, `Panel`, `PlayerTags`, `Ui`, `Dbg`, `Prof`, `Mods` |
| Campaign | `WingmanModPlugin`, `CampaignLobby` (the pre-battle lobby), `Hosting` (opens the session with the saved settings, for a battle, the lobby or another mod) |
| For other mods | `wingman.api.WingmanAPI` (see `docs/MOD_API.md`), `PilotMarks` (who flies what, written on ships) |
| Optional agent (`agent/src`, its own jar; 8.9) | `wingman.agent.Agent`, `Patcher`, `Hooks`, `Bridge`; in Wingman: `AgentGate`, `AgentLink` |
| Network (`wingman.net`, no game classes) | `Roster` (pre-battle lobby), `Conn`, `RawIO`, `StreamIO`, `ChannelIO`, `Transport`, `Zip`, `Udp`, `HostSession`, `ClientLink`, `Msg`, `In`, `Out`, `NetStats`, `ModChannel` (other mods' messages), `InputBuffer` (steady controls), `Budget` (bandwidth limit), `Smoother` (smooth corrections, jitter buffer) |
| Messages (`wingman.net`) | `Snapshot`, `SnapshotCodec`, `InputState`, `ShipSpawn`, `DamageState`, `Extras`, `Orders`, `BattleInfo`, `Lobby`, `ModList` |
| Build | `build.sh`, `build.bat`, `tools/PatchNew.java`, `tools/PatchPackage.java`, `tools/stub/`, `dist/make.sh` |

---

## 3. A battle, end to end

1. **Connect.** On the title screen the joiner opens the CO-OP panel and presses Enter. `ClientLink.connect`
   (background thread) opens TCP and sends `HELLO`. It connects now so that a wrong address or a mod mismatch
   shows at once, and stays connected between battles.
2. **Handshake.** `HostSession.pump()` checks the hello (4.5). On success: `WELCOME`, then the compression and
   UDP offers. The player gets a P-number (`slot`).
3. **The host starts a battle.** `WingmanHostPlugin.start()` calls `session.beginBattle()`: `RESET` to everyone,
   all marked for a full resend. On the same frame each joiner is sent the whole battle; a joiner still on the
   title screen keeps only `BATTLE_INFO` (`JoinSetup.remember`) and starts a 3 s auto-join countdown.
4. **The join mission.** `TitleLauncher` starts mission `wingman_join`. Its `MissionDefinition` (compiled by the
   game) sizes the map and backdrop from the remembered `BATTLE_INFO`, sets up the stand-in campaign, creates the
   host's objectives, adds 18 vanilla spare objectives and one invisible stand-in ship per side, and attaches
   `WingmanClientPlugin`. Map size and objectives can only be set at this moment.
5. **Sync.** The joiner sends `SYNC_REQ`; the host answers `RESET`, `BATTLE_INFO`, `NEBULA`, every `SPAWN`, the
   wrecks and their break-ups, missiles in flight and their targets, `OBJECTIVES`, `ASSIGN`, then a full update.
6. **Ready check.** The host's battle is held paused after it deploys. Joiners report ships built and pick a ship
   (`CLAIM` → `ASSIGN`). The host presses Enter (everyone ready) or Ctrl+Enter.
7. **Battle.** Every frame: joiners send controls (`INPUT`); the host flies their ships with `RemotePilotAI` and
   sends position updates plus events (spawns, deaths, hits, armor, stats, orders); joiners move puppets and
   predict their own ship.
8. **End.** When `engine.isCombatOver()` (not in the simulator), the host sends `BATTLE_END`; joiners leave
   3.5 s later and land on the title screen, still connected. `WingmanModPlugin` (campaign map) and the title
   plugin (title screen) end a battle the host left another way.

---

## 4. Network layer (`wingman.net`)

### 4.1 Sockets and the sandbox

`RawIO` is a minimal connection interface with two drivers. `Transport.decide()` asks the game's class loader
for each class a driver needs (`touch(i)`) and uses classic sockets (`StreamIO`, TCP_NODELAY, keep-alive, 5 s
connect timeout) if they load, else NIO channels (`ChannelIO`), which use no `java.io` stream classes.
`Transport.describe()` goes in the log. Tests can `force()` either.

### 4.2 Connections (`Conn`)

- **Frames:** `[4-byte length][1-byte type][payload]`, the length counting type plus payload. The length is
  checked (1 to the limit) before a buffer is made.
- **Threads:** a reader fills an inbox, a writer drains an outbox; the game thread only calls `send()` /
  `poll()`. The writer packs everything queued into one 32 KB buffer per write, and sends `PING` after 1 s of
  silence (on the network thread, because the game thread runs no mod code while loading).
- **Two kinds of send:**

  | Call | For | When the link is slow |
  | --- | --- | --- |
  | `send()` | Must arrive: spawns, deaths, hits, orders | Queued; past 20,000 waiting the link is cut |
  | `sendDroppable()` | Superseded by the next: position updates, controls | Refused if the oldest queued message is 150 ms old, a write is stuck 150 ms, or 5,000 are queued |

- **Inbox:** at most 4,000; a full inbox drops the oldest and counts it (`takeDropped`), so the joiner can ask
  for everything again.
- **Limits:** host-side connections are created with 256 KB per message and 300 messages a second, from the
  first byte, for life. Joiner-side links take up to 4 MB.
- **Dead link:** nothing heard for 12 s (`HostSession.LINK_TIMEOUT_MS`), checked by `HostSession.pump()` and
  `ClientLink.getState()`.
- **Taps:** `Conn.Tap` sees messages on the reader thread first; `ClientLink` uses it for the UDP plumbing.

### 4.3 Compression (`Zip`)

One raw deflate stream per direction, level 1, `SYNC_FLUSH` after each written batch, so each message is
compressed with the earlier ones as its dictionary. Agreed inside `Conn`: a side that can inflate sends
`ZIP_OK`; the other answers `ZIP_ON` (plain) and deflates everything after it. A side accepts `ZIP_ON` only
after sending its own `ZIP_OK` (`zipInvited`). On `ZIP_ON` the reader feeds the rest of its buffer and all later
bytes through the inflater. Frame limits apply after inflating. Only `Zip` touches `java.util.zip`;
`Zip.available()` catches a refusal.

### 4.4 UDP (`Udp`)

Position updates and controls go by UDP where it gets through; a lost datagram costs one update instead of a TCP
stall. Everything else stays on TCP.

- **Datagrams.** Every one starts with an 8-byte token and a kind byte. Joiner → host: `HELLO`, `ACK` (epoch,
  tick), `INPUT` (epoch, tick, controls). Host → joiner: `PONG`, `UPDATE` (tick, piece, pieces, data; pieces of at
  most 1,100 bytes, at most 255; the joiner keeps pieces for the newest 8 ticks).
- **Setup over TCP.** After `WELCOME`, `UDP_OFFER` (token, port). The joiner sends UDP hellos (every 250 ms for
  10 s, then every 5 s) until a `PONG`, then `UDP_READY`. The host switches that player to UDP and sends
  `UDP_MODE 1`.
- **Unknown tokens get no answer**, so the port can't be used to bounce traffic. The host follows the address a
  valid token comes from.
- **Choosing the road per player** (`HostSession.broadcastUpdate`): UDP while acks are under 3 s old (a gap in the
  host's own sending is excused); otherwise TCP with `UDP_MODE 0` and a full update; back to UDP after 5 s if a
  datagram was heard in the last 0.5 s.
- **Acks ride on controls**; a bare `ACK` goes only if nothing was sent for 30 ms.
- Queues are bounded (4,000 out on the host, 300 updates in on the joiner). `Udp.available()` probes for a
  `DatagramSocket`.

### 4.5 The lobby (`HostSession`) and the joiner's link (`ClientLink`)

`HostSession` lives for the game session (`getOrStart` reuses it unless the port changes); `pump()` runs each
host frame.

- **At the door** (`admit`, on the accepting thread, before a `Conn` exists): at most 16 connections not yet let
  in, 4 from one address; each arrival sweeps out closed ones and ones silent for 5 s. Works where `pump()`
  doesn't run (the campaign map).
- **Handshake order:** first message must be `HELLO`; protocol version; game build; password lockout (3 wrong
  non-empty passwords from one address → 30 s between tries; cleared on success); password; ban (names kicked
  this session); mod list (`ModList.differences`, up to 10 lines); reconnect replacement; player cap (host
  counts as one). Rejections send `REJECT` and close after 500 ms.
- **Names:** `cleanName` drops control, C1, zero-width, line-separator, bidi and BOM characters, collapses spaces
  and cuts to 24 characters (also applied to names in `LOBBY` on the joiner).
- **Ids and numbers:** `id` counts up per session; `slot` is the P-number, the lowest free from 2 (host is P1).
- **Reconnect replacement:** the same name from the same address while the old link is closed or silent for
  1.5 s replaces the old player and keeps the slot (`Player.replaced`).
- **Per player, each frame:** reads `INPUT` (`InputState.decodeIfNewer`), `INPUTS` (into `InputBuffer`), `READY`,
  `CLAIM`, `NEED_KEY`, `SYNC_REQ`, `UDP_READY`, `MOD` (into `ModChannel`, at most 100 a second per player), `BYE`,
  the latest UDP controls and any UDP bundles; then plays out the steady-controls buffer (4.7). A garbled message
  is skipped; over 20 drops the player.
- **Outside battles:** another mod may open the session (`WingmanAPI.openHost`, via `Hosting.open`) and pump it
  from the campaign (`WingmanAPI.pump`), so joiners can connect before the host's first battle. Joins and
  departures are posted to other mods on `wingman:joined` / `wingman:left`.

`ClientLink` is the joiner's single connection, a singleton shared by the title screen and the battle. It
connects on its own thread, sends `HELLO` (protocol, name, mod list, game build, password), handles UDP setup in
its tap, and offers `sendControls`, `ack`, `pollUpdate`. The tap also takes `MOD` (into `ModChannel`, for other
mods; see `docs/MOD_API.md`), `INPUT_MODE` (steady controls on or off), and reads the host's name from `WELCOME`.

### 4.6 Messages

Protocol version 51 (`Msg.PROTOCOL_VERSION`); host and joiner must match. Payloads are packed with `Out`/`In`:
big-endian numbers, varints (7 bits per byte), text as length + 2 bytes per character. Every read checks bounds
(`IOException("message cut short")`).

| Id | Name | Direction | Carries |
| --- | --- | --- | --- |
| 1 | HELLO | joiner → host | protocol, name, mod list, game build, password |
| 2 | INPUT | joiner → host | controls (`InputState`) |
| 3 | SYNC_REQ | joiner → host | send me everything |
| 4 | CLAIM | joiner → host | claim number, ship id |
| 5 | BYE | joiner → host | leaving |
| 6 | READY | joiner → host | ready, ships built, ships skipped, watching (no ship by choice; appended) |
| 10 | WELCOME | host → joiner | player id, cap, update rate, host name |
| 11 | REJECT | host → joiner | reason |
| 12 | SPAWN | host → joiner | `ShipSpawn` |
| 13 | DESPAWN | host → joiner | id, reason (removed, destroyed, wrecked, no wreck) |
| 14 | SNAPSHOT | host → joiner | full-format update (fallback) |
| 15 | ASSIGN | host → joiner | claim number, ship id or -1 |
| 16 | RESET | host → joiner | forget everything |
| 19 | NOTICE | host → joiner | a line for the message feed |
| 20 | MISSILE_SPAWN | host → joiner | missile launch |
| 21 | MISSILE_DESPAWN | host → joiner | missile gone, and how |
| 22 | LOBBY | host → all | ready-check roster |
| 23 | ALERT | host → joiner | pop-up |
| 24 | OBJECTIVES | host → joiner | objective kinds and places |
| 25 | ORDERS | host → joiner | `Orders` |
| 26 | BATTLE_INFO | host → joiner | `BattleInfo` |
| 27 | ASTEROIDS | host → joiner | asteroid states (full list or changes) |
| 28 | NEBULA | host → joiner | nebula bitmap |
| 29 | DAMAGE | host → joiner | `DamageState` |
| 30 | MISSILE_TARGET | host → joiner | a scripted missile's new target |
| 31 | PING | both | keep-alive (never reaches the game) |
| 32 | BATTLE_END | host → joiner | the battle is over |
| 33 | CHECK | host → joiner | counts and id sums |
| 34 | MISSILE_HIT | host → joiner | missile id, target, point, shield or hull |
| 35 | SHOT_HIT | host → joiner | a scripted shot's hit |
| 36 | WRECK_SPLIT | host → joiner | a wreck broke up: wreck, piece, dice seed, order |
| 37 | HURTS | host → joiner | damage reports |
| 38 | STATS | host → joiner | ship stats |
| 39 | NOTES | host → joiner | script memory values |
| 40 | SNAPSHOT_D | host → joiner | compact update (`SnapshotCodec`) |
| 41 | NEED_KEY | joiner → host | send me a full update |
| 42 / 43 | ZIP_OK / ZIP_ON | both | compression (inside `Conn`) |
| 44 / 45 / 46 | UDP_OFFER / UDP_READY / UDP_MODE | TCP | UDP setup (inside `ClientLink`) |
| 47 | SIDE | host → joiner | id, new side (0, 1 or neutral 100) |
| 48 | OBJECTIVE_TEXT | host → joiner | objective index, its tooltip text (≤ 4,000 characters), on change |
| 49 | COMBAT_MSG | host → joiner | a script's line in the message feed: indent, ≤ 40 parts (colour, text ≤ 1,000, ship id, objective index); ≤ 30/s |
| 50 | SUBSYSTEM | both | ship id, MagicLib subsystem class (≤ 200 characters). Joiner → host: the joiner used it (host keeps ≤ 10 a second). Host → joiner: someone else used it on the ship they fly (8.7) |
| 51 | LANDING | host → joiner | ship id, carrier id (or −1), byte 1 landing / 2 launched |
| 52 | MOD | both | another mod's message: channel (≤ 64), length, bytes (≤ 60 KB); see `ModChannel`, `docs/MOD_API.md` |
| 53 | INPUT_MODE | host → joiner | byte 1: send controls as bundles (steady controls, 4.7), 0: plain |
| 54 | INPUTS | joiner → host | a bundle: count (1-3), then per set: joiner clock (ms), length, controls; newest first |
| 55 | ROSTER | host → joiner | the pre-battle lobby (`Roster`): host name, state (0 campaign map, 1 battle), size limit, players (name, slot), fleet members (≤ 120: id, ship name, hull name, size, designation, CR, officer and level, flags flagship / mothballed / can't fly with a note, picked by) |
| 56 | PICK | joiner → host | fleet member id the player wants to fly in the next battle ("" none; ≤ 64 characters, the last one counts) |

### 4.7 Steady controls (`InputBuffer`, host setting `steadyControls`, off by default)

Without it the host takes a joiner's newest controls each frame. Two sets that arrive in the same host frame
count as one (a tap that began and ended between two host frames never happens), and a late set makes the ship
lurch. With it:

- The host sends `INPUT_MODE 1`; the joiner then sends each set as an `INPUTS` bundle (by UDP kind `INPUTS`, or
  TCP) with its own clock and the two sets before it (`ClientLink.sentSets`), so a lost datagram is made good by
  the next without a resend.
- The host queues sets by number (copies and old ones dropped, `recovered` counted), learns the usual lateness
  (quickest arrival = base, over two 3 s windows) and its spread (the jitter, capped at 120 ms), and plays each set
  at its joiner time + base + jitter: a small steady delay instead of an uneven one.
- One set a frame while on time, so a tap lasts at least a frame. More than the allowance behind (the host
  hitched): skips to the newest due (`skipped`). Nothing for a second: starts over.
- Edge presses (system, retreat) are counts in the controls, so nothing here can lose or double them.
- The traffic line in the log adds per player: sets played, the delay added, sets made good from copies, skips.

---

## 5. Position updates

(5.4 below: the bandwidth limit.)

### 5.1 What an update holds (`Snapshot`)

Header: tick, paused, battle speed (`timeMult`, clamped 0.05 to 20), host flagship, host battle clock
(`hostTime`), and per flown ship the control set it last acted on, for how long, its system charges, and where
its pilot points (`pilotedAimX/Y`, 2 bytes each, to 2 units), plus where the host points (`hasHostAim`,
`hostAimX/Y`, from the host's ship's mouse target). A joiner sets the mouse target of its copies of other
people's ships to these each frame (`applyAims`), so a system or script aiming "where the pilot points" aims
there on every PC.

Per ship (`ShipState`): id, position, velocity, facing, turn rate, hull (0-1), CR, flux and hard flux, flags
(shield, overload, venting, system on, phased), shield facing, firing bits (64 weapons), system and cloak stage
(0 idle … 4 cooldown) and level, turret angles (1 byte each), and shot totals. Host-only: `exact` (flown by a
player), `fluxStep` (1% of capacity, at least 5).

Per missile: id, position, velocity, facing, turn rate, flags (fizzling), flight time.

### 5.2 What the host puts in (`WingmanHostPlugin.buildSnapshot`)

- **By default (`sendEverything` on) every ship, fighter and missile goes in every update,** except unseen
  enemies.
- **Unseen enemies** (`trimUnseen`): an enemy no one on side 0 can see (shared fog of war, cached 250 ms) is sent
  about once a second unless it fired or was hit; unseen missiles every 2 s. In full again the moment it is seen.
- **With `sendEverything` off,** the host keeps a `Sent` record per entity and dead-reckons where the joiner thinks
  it is; a ship goes in only if it strayed (1.5 units or 1°), changed hull, CR, flags, flux (1% of capacity, or
  across zero), shield (3°) or system, fired, turned a turret, is flown, or wasn't refreshed for about half a
  second. Fighters go in every other update unless firing or hit. Missiles: when they stray (3 units, 4°, 0.25 s
  of flight), at least every two refresh intervals.
- **Wrecks** are sent when knocked off course (3 units, 2°) or every ~4 s.
- **Rate** (`snapshotRate`, default 30/s; measured with `SyncBench`, 30 is as smooth as 60 and smoother over an
  uneven link, at about two thirds of the bytes): when a link refuses an update, the next is full and the rate drops to
  two thirds (not below 8); after 5 clean seconds it climbs back by a fifth of the setting.

### 5.3 The compact form (`SnapshotCodec`)

- **Whole numbers.** A ship is 17 integers (position 1/16 unit, facing 1/65,536 turn, speed 1/8 unit/s, turn rate
  1/10°/s, flux and hard flux whole units, shield 1/256 turn, firing low/high, hull 0-65,535, CR 0-200, system
  stages, system and cloak level 0-255); a missile 8. Both ends do the same integer arithmetic, so the joiner
  rebuilds exactly the host's numbers.
- **Built on an earlier update.** Header: kind (1 key, 0 changes), a random `epoch` per player per battle, tick,
  and for changes the tick it is built on. Per entity: zigzag varint of (id − previous id), a bit mask of fields
  that differ, then per field zigzag(current − reference), or XOR for bit-set fields (flags, firing, stages);
  angles compare the short way round. Turret angles and shot totals go as lists of changed entries.
- **Guessing.** The reference is a guess, not the base itself, with dt from the two updates' `hostTime` (sent
  exactly, so both ends compute the same): speeds + acceleration × dt, position + (speed + ½ acceleration × dt)
  × dt, facing likewise from turn rate and turn acceleration, flight time + dt. Acceleration is used only when the
  updates are at most 0.25 s apart (`ACC_SPAN`).
- **Acceleration** is never sent. Each remembered state carries three extra integers past the sent fields (x, y
  and turn acceleration in the speed field's steps per second), set by `settle()` after every update on both
  ends: half the old value plus half of (speed − previous speed) / dt, clamped; zero for a new entity or a longer
  gap. Decoded states expose it as `ax`, `ay`, `angAcc` for the joiner's motion (7.4).
- **Bands** (encoder only): a field within its band of the guess (position 8 steps = ½ unit, facing 16 steps,
  speeds 4 (½ unit/s), turn rate 3, shield 1, missile flight 2, flux `fluxStep` never across zero) is not sent, and
  the guess is recorded as what the joiner holds, so errors never add up. `exact` ships have no bands.
- **Left out when guessed right.** A ship or missile held by the player whose every field is within its band of
  the guess (and turret angles and shot totals unchanged; never an `exact` ship) is not written at all. Both ends
  then *carry* it: the guess becomes its state, acceleration settled, and a quiet counter (another unsent
  integer) goes up. Entities in the base but absent from the snapshot (gone, or trimmed as unseen) are carried
  the same way. From the third carried update in a row (`FADE_AFTER`), and for the first update sent after that,
  the acceleration is not measured again but halved, on both ends (`settle`): measuring from a guess would keep a
  stale acceleration for ever, and the measure after a long carry could be wild, so a thing left out for many
  updates (the bandwidth limit, 5.4) ran away (speeds in the tens of thousands in tests). From the first carried
  update this cost about 3% bandwidth (things steadily speeding up were sent again sooner); from the third it
  costs nothing measurable. At `CARRY` (30) quiet updates in a row an entity must be sent, or is forgotten by both ends. The
  decoder hands the game every entity it holds, sent or carried; ids the joiner no longer has are passed over.
  It refuses an update that would leave it holding over 30,000 ships or missiles.
- **Per-player encoder with history.** `HostSession.Player.encoder` keeps the last 64 states (`HISTORY`), immutable,
  unchanged entities sharing arrays. Over TCP each update is built on the previous; over UDP on the newest tick
  the player acknowledged, if still held. Otherwise a **key** (measured against zeros), always by TCP and taken as
  held at once. Keys also go to new players, after a refused send, on `NEED_KEY`/`SYNC_REQ`, after falling back
  from UDP, and every 120 updates over TCP (`KEY_EVERY`).
- **Decoder results:** `READ` (acknowledged), `OLD` (reordered or duplicate: ignored), `NOTHING_TO_BUILD_ON` (the
  joiner sends `NEED_KEY`, at most every 200 ms). A new epoch wipes the ring; a read error leaves it untouched.
- **Shots survive loss.** The host sends each weapon's running shot total (8 bits); the decoder reports the
  difference from the update it last *read* (masked to 8 bits; over 120 means a reset; capped at 100) and sets
  those weapons' firing bits.

`Snapshot` also has its own full format (`SNAPSHOT`), used when `compactUpdates` is off or if the codec ever
throws on the host; joiners read both into the same object.

**Measured** with `test/SyncBench.java` (a simulated battle of 40 ships, 80 fighters and 200 missiles, all
manoeuvring, accelerating and firing; one joiner; 50 ms + up to 20 ms jitter):

| | 0.5 (60/s) | 0.6 (30/s) |
|---|---|---|
| TCP, compressed | 79.5 KB/s | 38.9 KB/s |
| UDP, with headers | 121 KB/s | 52 KB/s |
| copies off the host (avg, ships / fighters / missiles) | 1.8 / 5.8 / 9.6 units | 1.6 / 5.1 / 8.1 units |
| jitter (RMS acceleration error, same order) | 162 / 605 / 912 u/s² | 144 / 506 / 697 u/s² |
| jitter at 120 ms + up to 60 ms | 289 / 915 / 1780 u/s² | 161 / 585 / 871 u/s² |

Most of the "off" figure is the joiner's own tolerance (7.4), not the update rate. Higher rates are *less*
smooth over an uneven link, since each unevenly timed update is a small correction. Packing takes about 0.2 ms
per frame on the host.


**Joiner modes** (0.6.5, `test/SyncBench.java rate=30 seconds=40 mode=...`; "off" is measured against the host's
position one link delay earlier, or, with the jitter buffer, at exactly the host moment being shown; missiles are
never buffered):

| Link | Plain (`carry`) | Smooth corrections | + Jitter buffer (delay added) |
|---|---|---|---|
| 50 ms + 0-20 ms: off (ships / fighters / missiles) | 1.6 / 5.1 / 8.2 | 0.4 / 1.0 / 1.8 | 0.4 / 0.8 / 1.8 (67 ms) |
| same: jitter, u/s² | 177 / 615 / 2299 | 83 / 244 / 1039 | 82 / 198 / 1039 |
| 120 ms + 0-60 ms: off | 1.9 / 6.0 / 11.7 | 0.7 / 1.7 / 4.8 | 0.4 / 0.8 / 4.8 (117 ms) |
| same: jitter | 197 / 710 / 3677 | 117 / 374 / 2017 | 81 / 201 / 2017 |
| 60 ms + 0-150 ms: off | 3.7 / 10.4 / 29.6 | 2.5 / 5.9 / 22.3 | 0.4 / 0.9 / 22.3 (198 ms) |
| same: jitter | 446 / 1304 / 8935 | 316 / 830 / 6639 | 85 / 220 / 6639 |

### 5.4 The bandwidth limit (`Budget`, host setting `bandwidthLimit`, off by default)

Per player, a token bucket (the limit per second, a quarter second's worth saved up at most) and a priority
accumulator (after Glenn Fiedler's "state synchronization" and Halo: Reach). Each update, every entity in it
gains priority: its kind (ships 3, missiles 1, a ship a player flies 100: it always goes), ×(1 + 3 × nearness to
what the player looks at, within 4,000 units: their ship, or the host's; `focusX/Y` set by the host plugin),
×2 if firing, and ×(1 + off/20) where *off* is how far the player's copy, as the packer carries it on, is from the
real thing now (`Encoder.heldOff`, the codec's own guess from what the player holds). Something the player
doesn't hold yet goes at once.

If the encoded update fits the bucket, it goes as it is. If not, the entities are sorted by priority and as many
as fit (estimated from the update's bytes per entity) go; the update is encoded again for the same tick (which
replaces what the encoder noted for it) with only those. Sent entities' priority drops to zero; the rest keep
gaining. Anything left out 20 updates in a row goes regardless (the packer forgets after 30). Updates in full
(keys) are never cut, only counted. The limit counts update bytes before compression; other messages are not
limited. The traffic line in the log adds updates cut and states left out.

Measured (smooth corrections, 50 ms + 0-20 ms; the battle needs 49.5 KB/s):

| Limit | TCP compressed | off (ships / fighters / missiles) | jitter, u/s² |
|---|---|---|---|
| none | 39.0 KB/s | 0.4 / 1.0 / 1.8 | 83 / 244 / 1039 |
| 40 KB/s | 34.5 KB/s | 0.4 / 1.0 / 5.7 | 82 / 274 / 3496 |
| 32 KB/s | 28.3 KB/s | 0.4 / 1.1 / 8.4 | 89 / 377 / 5515 |
| 24 KB/s | 22.2 KB/s | 0.6 / 2.4 / 15.5 | 139 / 826 / 8610 |
---

## 6. The host

### 6.1 One frame (`WingmanHostPlugin.step`)

`start()` runs on the first frame and switches the plugin off on the title screen's battle, on a joiner
(`CLIENT_FLAG` on the engine), or with hosting off; otherwise it opens the session, sets access and settings,
calls `beginBattle()`, and logs this PC's addresses (shown on screen only with `announceAddresses`). Then each
frame:

1. `session.pump()`.
2. Departed players: `releaseShip` (ship back to its AI); mid-battle the game pauses and an alert goes out.
3. Battle over (not the simulator): `BATTLE_END`, stop.
4. Nobody connected: count ships twice a second for the ready panel, stop.
5. Track: ships, wrecks, missiles, scripted shots (alternate frames), firing.
6. Players needing a sync get the whole battle (3, step 5).
7. Flown ships, claims, retreats, targets; `CHECK` every 3 s.
8. Helpers: orders, environment, damage, extras.
9. Ready check; position update at the current rate.

### 6.2 Ships, ids and wrecks

- **Network ids** for ships, missiles and wrecks (`ids`, `byId`, …): messages name ids, never game objects.
- **Eligible:** ships, modules, drones, wing fighters, sides 0, 1 and neutral 100 (ships a mod's objective parks,
  such as derelicts waiting to be captured), not hulks or shuttles.
- **`announce`** builds and caches the `SPAWN` (`buildSpawn`): hull, name, place, CR, vents and capacitors,
  hullmods (normal, s-mods, permanent, s-modded built-ins, suppressed), every weapon by slot and the weapon order
  used for firing bits, weapon groups (autofire from the design, since the AI turns most groups on), wings,
  officer and skills (fighters only with their own officer), the fleet commander's skills (up to 80), and the
  host's max flux, hull and speed. Modules are announced just before their ship.
- **Deaths:** `DESPAWN` with a reason. A dead non-fighter becomes a tracked wreck under the same id (up to 150).
  `trackWrecks` reports wrecks gone and, four times a second (at once after the wreck dice were rolled), gives
  new hulk pieces an id and a parent (`WRECK_SPLIT`, with the dice seed of the cut when known). Split history is
  pruned past 600.
- **Side changes** (`checkSide`): a tracked ship's side is remembered at `announce`; a change to 0, 1 or 100 updates the
  cached `SPAWN` and broadcasts `SIDE`. A player whose ship leaves side 0 loses it with a notice.

### 6.3 Missiles and scripted shots

- **New missile:** `weaponIdOf` finds the weapon (the missile's own spec, the launcher, then hidden system
  weapons); none → never sent. `MISSILE_SPAWN` carries projectile id, whether it runs its own script (AI class
  outside the game's plain missile AI, or MIRV), owner, place, velocity, flight, max flight and arming time.
  Script-steered missiles have their target checked ~5 times a second (`MISSILE_TARGET`).
- **Missile gone:** with `didDamage()` → `MISSILE_HIT` (target, point, shield flag). Otherwise `MISSILE_DESPAWN`:
  quiet (burning out, or a stage separation: new missiles within 80 units from the same source), scripted (still
  had hull, did no damage, not fizzling), or destroyed.
- **Scripted shots** (`trackShots`): non-missile projectiles whose spec has an on-hit script are reported once
  when they hit or vanish (`SHOT_HIT`, droppable, at most 120 a second). Ordinary bullets are left to each PC.
- **Firing** (`trackFiring`, every frame): a weapon's bit is set while it is heading for a shot (`wantsShot`:
  `isFiring`, except that a beam or a charge-up weapon whose charge level is falling is charging down, which
  includes a charge-up let go of before it was done), or if its cooldown restarted or its ammo dropped; shot
  counts and running totals per weapon. Ammo changes `touch` the ship for `HostDamage`.

### 6.4 Players and the ships they fly

- **Claims** (`handleClaims`): refused for no ship, not side 0, fighter, module, drone, the host's ship, too big
  (`maxShipSize`), or already flown. Granted: the ship's AI becomes a `RemotePilotAI`; always answered with
  `ASSIGN`. A one-time campaign warning says a friend's lost ship is lost for real.
- **Kept ships** (`seatReserved`): another mod may keep a fleet member for a player (`WingmanAPI.reserveShip`; the
  a campaign mod can). From 2 s into the battle, a player with no ship, no claim pending and synced is given
  a claim for it, once per battle, checked by `handleClaims` like any other; the joiner, still on the placeholder,
  is moved in by the `ASSIGN`.
- **Pilot marks** (`PilotMarks`, for other mods): every frame the host writes `wingman_pilot` (name) on the ship
  each person flies (its own while anyone is connected), and `wingman_remote` on joiners' ships; the joiner does
  the same from the lobby (host's ship, other joiners', its own without `wingman_remote`). Ships nobody flies lose
  both.
- **`RemotePilotAI`** turns the player's controls into the commands the game's AI uses:
  - controls older than 500 ms → the ship only decelerates;
  - movement flags → accelerate, back, decelerate, strafe;
  - **facing:** the joiner sends the heading on its own screen; the host turns toward it, allowing for the turn
    the ship will coast (relaying turn keys fought the game's own steering in strafe mode);
  - the cursor becomes the ship's mouse target (read by teleporters, omni shields and mod scripts), and the
    system-target AI flags are answered for vanilla system scripts;
  - **weapons** (`GroupFire`), modelled on the game's own code (`combat.systems.WeaponGroup`, `Ship`):
    - the FIRE order fires the ship's *selected* group, so the joiner's group in hand is selected every frame
      until it holds (selection is carried out before the same frame's fire order); its turrets track the cursor
      within their arcs (`trackCursor`);
    - a group with autofire on is fired by its weapon AI and ignores the button, except on the local player's
      ship; so the group in hand has its autofire switch off here, the other groups follow the joiner's switches;
    - linked: every weapon whose arc covers the cursor or that always fires (turret, arc over 10 degrees, not
      DO_NOT_AIM/GUIDED_POOR); if none, the closest one;
    - alternating: the active weapon fires; it moves on at release, and while held every (burst + refire +
      charge-up, or a burst beam's burst + cooldown + charge-up/down) / weapons / rate-of-fire bonus seconds;
      the game does that held rotation only for the local player's ship, so the host does it
      (`WeaponGroup.setCurrentActive`, public on the game's class, through `MethodHandles.publicLookup`);
    - charge-up as in the game, by the game's own weapon state machine (`combat.entities.ship.trackers.B`), with
      the button held exactly as long as the joiner held it: a charge needs the button held to completion
      unless the weapon is `autoCharge`; a burst stops on release only if `interruptibleBurst`. On a fighter, an
      ammo weapon that shows no sign of charging or firing once the button has been held for its charge-up
      + 0.1 s is fired by hand by the same rules (shots spawned one burst delay apart, one round each, cooldown
      after the burst; auto-charge finishes a begun charge; release stops an interruptible burst), again each
      time it is ready while held;
    - a hold that makes nothing happen for 0.35 s (not overloaded, venting or phased) fires the weapons the game
      would have fired directly, until release; a group the ship won't select for 0.25 s likewise;
    - a ship with no groups (an AI-flown fighter) fires its loadout's group of that number, by slot;
  - shield or phase toggled to match (at most every 0.3 s); vent on the rising edge; Hold Fire matched;
  - fighters: the game's `PULL_BACK_FIGHTERS` order on a change, plus what the game does only for the player's
    carrier (`forceCarrierTarget` = R target for 20 s, 30 s if friendly; `forceCarrierPullBackTime` 20 s);
  - flown in every time-step of a frame (a ship with time multiplier above 1 is moved in several; see
    docs/GAME_CODE_AUDIT.md);
  - **ship system:** the joiner sends a running count of uses; new uses (at most 4, dropped after 1.5 s) are issued
    0.1 s apart at the cursor; drone orders stepped to match; toggles switched off when released.
  - An error inside marks it failed; the host hands the ship back to the AI and tells the player.
- **Targets** (`syncTargets`): the player's target is set on the real ship (guided missiles, autofire and systems
  use it). **Autopilot** (U) swaps in the game's AI and back.
- **Retreat** (`handleRetreats`): the game's Retreat order is passed on; at the bottom edge (within 900 units)
  Enter makes the ship leave at once and the player is unassigned.
- **Host keys:** Ctrl+Shift+P-number removes a player (name refused until restart); Ctrl+Shift+T takes back the
  targeted ship; Ctrl+Shift+O opens settings.

### 6.5 The pre-battle lobby, then choosing a ship and the ready check

**Before the battle (`CampaignLobby`, host setting `campaignLobby`, on by default).** A transient campaign script
(added in `WingmanModPlugin.onGameLoad`, runs while paused) opens the session with `Hosting.open` as soon as a
save is loaded, so friends can connect while the host is on the map, and pumps it each frame (joins and leaves go
to the campaign message feed). While anyone is connected it sends `ROSTER`: the host's fleet (flagship first, then
by size), who is connected, and who picked which ship; again whenever that changes, and every 5 s. A joiner's
`PICK` is checked on the game thread (`handlePicks`: in the fleet, not the flagship, not mothballed, not a station,
within the size limit, not picked by someone else connected; a refusal goes back as `NOTICE`) and kept as a
reservation (`WingmanAPI.reserveShip`, the same store the campaign companion's crew screen uses). In a battle the
host plugin still handles picks from joiners on their title screen and sends `ROSTER` with state 1 at the start.
When the battle is in its ready check, `seatReserved` seats each player without a ship in their pick, if it was
deployed (once per battle; before, it waited for 2 s of battle time, which never passes while the ready check
holds the battle paused).

**On the joiner's title screen** (`WingmanTitlePlugin`): the first `ROSTER` opens the **LOBBY** page by itself
(F4 brings it back): the host's state, who is there, the fleet (a window of 9, the marked ship's designation and
officer below), Up/Down, F (or Enter on the map) to pick, Delete for no pick, Enter to join when the host is in a
battle. The pick (ship name, hull name) is kept in `JoinSetup.lobbyPick` for the battle.

**In the battle: two stages, then the start.** Phase 0 waits until the host has deployed (unpaused with a real
flagship), phase 1 holds the battle paused, phase 2 runs. During phase 1 each joiner goes through:
1. **Choose your ship** (`WingmanClientPlugin.stage() == 1`: no ship and not watching, or Backspace from stage
   2): once everything is loaded, the list of the host side's ships (Up/Down, Enter or F takes it through the
   usual claim, W watches without a ship). A lobby pick that is deployed is shown, and the host seats them in it;
   the remembered ship from last time is not taken then.
2. **Ready check**: Enter ready / not ready, Backspace back to stage 1. Ready can't be given in stage 1.

`READY` carries the watching flag (appended; `In.remaining()`); `LOBBY` per player: name, ready, built, skipped,
ship, slot, ship id, watching. `Lobby.describe` reads "loading", "choosing a ship", or ready/not ready with the
ship or "watching". The host's panel adds a line counting players at each stage. The host starts with Enter
when everyone is ready, or Ctrl+Enter; after 60 s of waiting the host is reminded of Ctrl+Enter. `LOBBY` goes
out when it changes and at least once a second. A joiner counts as loaded when built + skipped reaches the
expected count.

### 6.6 Helpers

| Class | Sends | How |
| --- | --- | --- |
| `HostDamage` | `DAMAGE`: armor grid (cells 0-255), disabled weapons and engines, flame-out, ammo | 4×/s, at most 40 ships per message. Reads a ship only if hit, touched (ammo), flown, never sent, or on a 1-in-8 rotation; everything again every 10 s. Armor as whole grid, changed cells, or nothing (whole grid when a third or more changed). |
| `HostExtras` | `STATS`, `NOTES`, `HURTS` | One engine damage listener notes ships hit. **Stats** 4×/s: the 43 `ShipStats` of ships touched, flown, busy or on a 1-in-4 rotation; changes over 0.5% only. **Notes** 2×/s, half the ships each pass: simple custom-data values (boolean, number, text ≤ 64) of the engine and ships, ≤ 40 per ship, keys ≤ 80 not starting `wingman`; a value that changed six checks running is a timer and stops being sent. **Hurts** every 250 ms: damage per ship not from a weapon's projectile or beam (those happen on every PC anyway), summed, with the heaviest blow's type, point and source. |
| `HostEnvironment` | `BATTLE_INFO`, `NEBULA`, `ASTEROIDS` | Battle info: map size, background, colours, glow, starfield, hyperspace, the campaign planet behind the battle, the objectives (kind, place; ≤ 64) and the campaign facts (8.3). Nebula as one bit per tile. Asteroids 2×/s by battle time, those off by 6 units or 1 unit/s; the full list every 5 s (how removals are learned); at most 600. |
| `HostOrders` | `OBJECTIVES`, `ORDERS`, `OBJECTIVE_TEXT` | Objective owners, with `Orders.GONE` (0x80) added once the host's game has taken one off the field; each objective's tooltip (its script's `getLongDescription`), checked every second and sent on change; side 0's task manager: full assault/retreat, assignments with targets and ships, direct retreat/search-and-destroy orders. Rebuilt every 300 ms, sent on change or every 3 s. |
| `ShipStats` | (used by both) | 43 stats: 23 `MutableStat` (speed, acceleration, turn, flux, rates of fire, projectile speed, time mult, shield, vent, recoil…) and 20 `StatBonus` measured as `computeEffective(1000)` (ranges, weapon turn, shield arc, sight, missile handling, system range/cooldown/regen, peak CR, phase…). Damage-only stats are left out. |

---

## 7. The joiner (`WingmanClientPlugin`)

### 7.1 One frame

`prepare()` (first frames): marks the engine (`CLIENT_FLAG`), removes the enemy stand-in ship, makes the player
stand-in ship (the placeholder) invisible, collision-less and invulnerable, starts orders and environment, and
sends `SYNC_REQ` and `CLAIM -1` (or opens the join form if not connected). Then `step()`:

1. Link state: before `WELCOME` a failure returns to the join form; after it, reconnecting starts (7.13).
2. Placeholder rides with the host's flagship so the camera follows the fight.
3. Every TCP message through `handle()` in its own try/catch (first 5 errors logged, 40 stop co-op); then up to
   400 UDP updates.
4. Lost messages (inbox overflow) → ask for everything.
5. Battle speed follows the host (`followHostTime`, `keepPace`).
6. Strays: fighters and asteroids the local game made are removed, missiles matched (7.8), ships matched (7.11).
7. Ready check, remembered ship, the player's ship choice (`watchPlayerShip` → `CLAIM`).
8. `apply()` every puppet, then missiles, wrecks, pending hits.
9. EMP/ammo re-applied twice a second; lost ships rescued; orders.
10. Controls sent; stall warnings (notice at 4 s, alert at 20 s offering Ctrl+Enter to leave).

### 7.2 Building puppets

- **`spawn`:** modules are held until their ship arrives and then set into its variant, so the game builds the
  whole ship at once; children are matched to their spawns by slot. Fighters beyond 800 are dropped. A ship that
  can't be built (missing mod content, or a mod's script throwing while it is built: logged once per hull) is
  counted in `skippedIds` with its modules, so the ready check and drift check still balance.
- **`buildVariant`:** hull spec, vents, capacitors, hullmods the way the host has them (so their scripts give the
  same effects), weapons by slot (forced again where a hullmod or script placed something else), wings, weapon
  groups.
- **`spawnShip`:** crew filled (no crew = 0% CR), CR, side, the officer (`officerOf`), fleet commander skills
  (`underCommander`: one stand-in commander per distinct skill set, set with `setFleetCommanderForStats`),
  membership in the side's stand-in fleet (`CampaignStandIn.enlist`), then `spawnFleetMember` with deployment
  messages off.
- **`spawnFighter`:** a `FIGHTER_WING` member; the leader is kept as the puppet and the rest made inert and
  removed at once (a wingman left one frame crashes the game); fighters are regrouped into wings that mirror the
  host's (`joinHostWing`). Officered fighters get their officer.
- **`register`:** `PuppetAI`, invulnerability, `HullGuard`, side, CR, weapons mapped to the host's order. Max flux
  is corrected if it differs from the host's; other differences go to the detailed log.

### 7.3 Inert and unhurt

- **`PuppetAI`** does nothing; `watchPlayerShip` restores it on every ship but the player's, because the game gives
  the ship the player left back to its own AI.
- **Damage:** `makeInvulnerable` sets armor and EMP damage taken ×0, hull ×0.0001 (exactly zero makes the game
  treat a ship as untargetable), break-up chance 0, fighter refit ×100,000. `HullGuard` cancels all hull damage
  until `standAside`. A puppet dies only through `destroy()` (guards off, 10 million damage) when the host says.
- **Values from the host every frame:** hull (never below 1), CR, flux (clamped to capacity), overload, venting
  and shield on other ships; stats forced through `ShipStats.force` (one modifier, `wingman_host`, so the final
  value equals the host's whatever local hullmods and skills did), when new values arrive or every 6/60 frames.
- **Armor and EMP** (`applyDamage`): disabled weapons and engines, flame-out, ammo, and the armor grid cell by cell.

### 7.4 Moving puppets

- Each puppet is carried forward from its last state: position + (velocity + ½ acceleration × a) × age, velocity
  + acceleration × a, facing and turn rate likewise, with age capped at 1 s and a = min(age, 0.25 s)
  (`ACC_REACH`: past a quarter of a second it goes on in a straight line). Missiles the same (age capped at 2 s).
  Ships: the acceleration is capped at 400 + 4 × speed (`clampAcc`); while the host reports the system or the
  right-click system running (stage 1-3) it is not used at all, the tolerance is cut to `3 + 0.01 v` and the
  excess closed at 25/s, velocity blended at 60/s.
- **Tolerance:** inside `3 + 0.05 v` units for ships (`5 + 0.06 v` fighters, `6 + 0.06 v` missiles; × `syncTolerance`)
  the gap fades at about 1/s; beyond, the excess closes at 10/s (fighters 14/s); over 500 units, or after a
  detected teleport (`jumped`: more than `150 + 0.3 × speeds` off the dead-reckoned place), it snaps. Velocity is
  blended at 25/s. Facing: `1.5 + 0.05 w` degrees. Modules are left to their ship.
- **Lateness:** each update is stamped with how late it arrived compared with the quickest of the last 8 (at most
  0.1 s), so fast things are carried exactly as far as they should be.
- The above is the **plain** mode. Two joiner settings change it (`Smoother`; measured in 5.3):
  - **Smooth corrections** (`smoothCorrections`, on by default since 0.6.5): each puppet (ships, fighters,
    missiles; not modules, not the joiner's own ship) keeps its recent host states (`Puppet.track`). The *model*
    is the newest state carried forward to host-now (`clock - gapMin`), as above. The game moves the puppet at
    the model's speed; each frame the gap between where it is and the model is the *error offset*, faded by 10%
    a frame at 60 fps (up to 20% for a gap over 100 units; facing 12%), and the puppet put at model + offset. So
    a smoothly moving model is followed exactly, while a jump in the model (an update that changed the guess, a
    collision here) is spread over a few frames, and it always ends on the model: no tolerance band. Over 500
    units, or at a detected teleport (`markJump`, at the host time of the update that leapt), it is put straight
    there (with the teleport trail).
  - **Jitter buffer** (`jitterBuffer`, off by default; uses smooth corrections): ships and fighters (not missiles:
    their end is the host's hit, which is not delayed) are shown at host time `Smoother.Delay.showing(clock)`:
    our clock minus *lag*, where lag = base (the promptest arrival gap of two 3 s windows) + delay (the latest an
    update has been lately + the gap between updates + a frame, ≤ 0.25 s). Lag moves toward its target at 3% of
    the time passing, only once off by more than 6 ms (20 ms to shrink), so the picture never jumps, only runs up
    to 3% slow or fast for a moment. The model is carried on from the update at or before the moment shown, so
    never further than to the next one, which has arrived: lateness no longer matters. (Curves through the
    updates were tried and measured worse: rounded positions make a curve through them wobble.) The cost: other
    ships are that much later on screen; their shots too, so with `scriptedHits = match` more hits may need the
    host's copy. The detailed log reports the delay and how often an update past the shown moment was there.
- **Battle speed:** `followHostTime` keeps the engine at the host's speed × `pace`; `keepPace` measures host time
  against local time every 0.5 s and moves `pace` 70% of the way when they differ by over 3% (0.25 to 1.1), so a
  slow host doesn't make everything race ahead and snap back.

### 7.5 The player's own ship

- **Prediction** (`predictOwnShip`): the ship moves with local physics and local controls. Facing stays local unless
  40° off (overload, dead engines).
- **Reconciliation** (`reconcile`): each control set carries a 16-bit sequence number; the joiner remembers when
  each was sent and where its ship was each frame (256 frames). The host reports which set it last acted on and
  for how long, so the joiner compares the host's position with where *its own* ship was at that moment: the
  true error with the delay removed (during a speed burst from a system, here or on the host, moving over 1.25 ×
  the hull's base top speed + 20, and 0.4 s after: `40 + 0.15 v` instead). Beyond `3 + 0.02 v` units it is pulled at `4 + min(8, over/25)`/s, the
  history shifted too; velocity blended over 60; over 700 the ship jumps. Without a usable ack, the speed-based
  slack is used.
- **Controls** (`captureInput`): movement keys, fire (not in the command screen or paused), shield/phase, system
  uses (a running count; `noteSystemUse`), vent, fighter pull-back, retreat, mouse, target, autofire mask, selected
  group (`Groups`), heading. Sent on any change up to 60/s and at least 15/s; over UDP a change is sent on the
  next two frames too. On autopilot only the autopilot flag goes.
- **System readiness:** `matchSystemReadiness` copies the host's charges after 1.5 s of disagreement and clears a
  local cooldown the host doesn't have after 1 s; `confirmSystemUse` resends a use once after 0.8 s and logs it if
  the host never responds.
- **Latency hiding per mechanic** (`hideLatency`, on by default; with `predictOwnShip`). Each thing the player
  does gets an immediate answer here, kept in step with the host's later one:
  - movement, turning, shield, system, vent, the group in hand: already local (above, 7.6, 7.7);
  - **flux bar** (`ownFlux`): the flux and hard flux of our ship are recorded each frame with its position, and the
    host's figures compared with ours at the moment they were true (the same ack as `reconcile`); only a
    difference over `max(15, 1% of capacity)` is closed, at 4/s, the record shifted with it. Before, the bar
    showed the host's figures a round trip late: firing, it rose after the shots and fell back after a vent;
  - **ammo**: on our ship a host count *above* ours is the host not yet having fired what we have, so it is taken
    only if it stays above for a second (a reload, a refill); a lower count is taken at once.

### 7.6 Weapons

Turret angles follow the host's. A weapon in the player's selected group answers the player's mouse; the rest are
forced to fire or hold each frame. With `shotCounts`, the joiner counts its own shots (cooldown restarts) against
the host's (`ahead`, clamped ±6, forgotten after 1.5 s unchanged): fire if behind, or level with the bit set and
0.12 s since the last shot; otherwise hold. Launchers also hold while too many of their missiles are unconfirmed
(1 for launchers with 12 ammo or less, else 3), and a launcher whose missile was refuted is silenced until its
firing bit clears.

### 7.7 Ship systems

- **`followSystem`** puts each copy's system and right-click system into the host's stage; the system's own script
  then runs as written (a phase ship fades, a mod's system draws its effects). Disagreements are corrected at once
  for on/off and after 0.25 s for smaller ones; a phase state is forced after 0.3 s. On the player's ship the keys
  rule and the host only corrects after most of a second.
- **Stopping:** a system running on the copy while the host's is at rest is switched off with `deactivate()` first
  (it winds down and starts its real cooldown), `forceState` only after 0.3 s more. A forced COOLDOWN is given the
  system's full cooldown if it came with none, so it doesn't read as idle at once (UAF's Isenoria ends its fighter
  hand-over when Rafflesya Control is neither on nor cooling down).
- **Teleporters on other ships** (spec type containing `DISPLACER` or `TELEPORTER`, e.g. Phase Skimmer, or the
  game's teleporter class) are never run on the copy. Their look is drawn instead (`teleportLook`,
  `teleportTrail`): the spec's jitter (colour, copies, range; defaults if none) while the host's stage is IN, ACTIVE
  or OUT, its use sound when that starts, and when the copy snaps on a detected jump (over 100 units) an afterimage
  where it stood (0.5 s fade; the sound too if the stages were missed).
- **Starting from rest:** `useSystem()` first (a teleporter decides its destination at that moment), `forceState`
  only as a fallback and never for teleporter-class systems; teleporters and drone systems on other ships are left
  alone (their positions come from the host).
- **Drones** a local system launches are ghosted (invisible, no collisions, guns held), not removed, since the system
  would only launch more; the host's drones arrive as `DRONE` spawns.
- **Crash breadcrumb** (`SystemWatch`): before a mod's system first runs on a copy, its id is written to
  `saves/common/wingman_systems` as pending; after 5 s it is trusted; still pending at the next launch → blocked on
  this PC. `neverMirrorSystems` blocks by setting. F5 in the CO-OP panel resets.

### 7.8 Missiles

- **Pairing:** the joiner's own launch is kept when it matches the host's (same projectile, same ship, within 700
  units), since everything the mod's scripts did at launch belongs to it. Unpaired local missiles are untouchable
  and removed after 0.5 s; if nothing local appears within 0.25 s a copy is built (`build`, by exact projectile id,
  borrowing a same-side launcher if the ship is gone; at most 30 a frame); a late real one replaces the copy.
- **`takeOver`:** collisions off (a missile never hits anything on a joiner by itself). The game's plain missile AI
  becomes `PuppetMissileAI` (no steering, flame on); the game's special brains stay; a mod's AI runs inside
  `GuardedMissileAI`, which switches off just that script on an error. A missile whose launcher has a mod effect
  plugin is `tended`; a built copy has that plugin's `onFire` called by hand.
- **Each frame:** dead reckoning (2 s), snap beyond 300 units, hitpoints held at 100,000 (only the host's point
  defense counts), flame-out and flight time from the host; a tended missile gets exactly the host's max flight.
- **Revival:** a copy that vanished while the host's flies on is rebuilt after 0.5 s (twice per missile, three a frame).
- **Hits:** on `MISSILE_HIT` the copy is placed 6 units short of the point, flying at it, collisions restored, so the
  game makes the real hit next frame (explosion, sound, on-hit script); if it is still there after 0.1 s the hit is
  done by hand (`NoDamage` result, explosion, removal).
- **Endings:** scripted or tended missiles get 0.35-0.6 s for their own script to end them (`releasing`), else they
  are removed quietly and logged once per kind; destroyed ones blow up here.

### 7.9 Scripted shots and hit matching

Shots whose weapon has an on-hit script would fire their effect on both PCs at different moments.
- **`scriptedHits: "match"`** (default): the joiner's own scripted shots may hit. Each host `SHOT_HIT` is paired
  with one of the joiner's own hits of the same kind, source and target (modules count as their ship) within the
  window (`matchWindowMs`, default 100) + 0.25 s. If a shot of that kind is about to land, the report waits for the
  window. Unpaired, the host's hit is replayed: a fresh shot 8 units short of the point, or the on-hit script run
  by hand with `NoDamage` if that misses within 0.1 s. Own hits the host never reports are only counted.
- **`"host"`:** scripted shots pass through everything and are removed on reaching an enemy; only the host's hits
  play out. **`"local"`:** each PC's own hits only.

### 7.10 Damage nobody here caused

`LocalDamage` tallies what this PC aimed at each ship over the last 0.3-0.6 s. On a `HURTS` report, if the host's
amount exceeds it by more than `40 + 0.5 × amount`, the shortfall is applied with `engine.applyDamage` at the
reported point. The ship is invulnerable, so nothing changes, but the hit is seen and scripts that react to hits
react. Replayed damage is not counted as local.

### 7.11 Fighters, modules, wrecks, and ships local scripts make

- **Wrecks:** a destroyed puppet always leaves one wreck (hulk chance 1, break-up 0); wrecks are sealed against local
  damage, follow the host (8/s, snapping beyond 400), and break up when the host's do (`splitShip`, retried for up
  to 30 s), with the wreck dice set to the host's seed for that cut when one came (`WreckDice`, see below). A
  whole wreck lost locally is rebuilt once. Up to 200.
- **Ships a local script makes** (an objective's turret, reinforcements, summons): on first sight made inert and
  invulnerable and noted (`localShips`). A host `SPAWN` for a plain ship first looks for a waiting local ship of the
  same side, hull and weapons (`sameShip`) and uses it as the puppet (moved to the host's spot, a pinned station
  re-pinned there); a local ship that appears within 3 s after a puppet was built replaces it (`swapIn`), never the
  player's. Unmatched after 3 s, it is removed with its modules. The script that made it thus keeps hold of the
  ship when they match.

### 7.12 Battlefield, objectives and orders

- **Map and backdrop** come from `BATTLE_INFO` before the battle (3, step 4). `ClientEnvironment.onBattleInfo` applies
  colours and checks the size and the objectives; a mismatch raises "BATTLEFIELD DIFFERS" with a one-key rejoin
  (not within 30 s of the last one).
- **Nebula** is painted from the bitmap when the grids match. **Asteroids** are made by size class (the class whose
  radius is nearest, measured once off-map), snapped when 25 units off, removed when missing from a full list.
- **Objectives** (`ClientOrders`): the mission makes the host's objectives in place plus 18 vanilla spares. For each
  host objective the unused local one of the same kind nearest its spot is used (or the stand-in made at that exact
  spot, 8.2); the rest are parked off the map, owned by nobody. Ownership is forced from the host every frame. An
  objective the host marks gone is removed here too, with a notice of who captured it if its script was held back.
  Tooltip texts from `OBJECTIVE_TEXT` are put on held-back objectives' `MirrorObjectiveEffect`.
- **Orders:** every 300 ms the joiner's command screen is made to match the host's (assignments created or removed,
  full assault, direct orders); anything the joiner adds is removed with a notice. Full retreat is disabled.

### 7.13 Staying in step

- **Drift check** (`CHECK` every 3 s): counts and id sums of ships, fighters and wrecks, with ships the joiner knows
  of but couldn't build counted in. Three ship/fighter misses in a row (five for wrecks, once a battle) → resync.
- **Resync** (`requestResync`): at most 5 a battle, with a minimum gap; the joiner remembers its ship and is put back
  in it after `ASSIGN`.
- **Rescue:** twice a second, a puppet the local game lost or killed while the host has it is rebuilt at the host's
  place (3 times at most), damage state restored, the player put back at the helm if it was theirs.
- **Reconnect:** the battle pauses, the ship is remembered, `connect` is retried every 3 s up to 10 times with the
  saved address, name and password; on success the host sends everything and the same hull is re-boarded if free.
- **Battle end:** "BATTLE OVER", then `leaveBattle` after 3.5 s: report saved, stand-in campaign removed, title-screen
  flags set (`hostBattleOver`, `leftOnPurpose`, `rejoinRequested`), `endCombat`.

### 7.14 Player tags

`PlayerTags` draws "P1"…"P4" (green, blue, yellow, white; white beyond 4) at the top right of each player's ship in
screen space, plus an optional diamond outline (raw OpenGL line loop at 1.15 × collision radius, 1 px, 55% opacity,
state saved and restored). P1 is the host's flagship; others from the lobby. F3 in battle hides them until the game
closes.

---

## 8. Mod content and compatibility

### 8.1 How mod content is handled

- Ships, weapons, hullmods and skills: built from the host's description and run their own scripts (7.2).
- Ship systems: stage-mirrored, scripts run locally, crash breadcrumb (7.7).
- Missiles and their scripts: paired, guarded, hits from the host (7.8).
- Scripted shots: matched or replayed (7.9); script damage: replayed harmlessly (7.10).
- Script memory and stats: copied from the host (6.6).
- Ships a script makes: matched or removed (7.11). Ships changing sides: followed (6.2).
- The mod list check keeps content the same on every PC.

### 8.2 Objectives from mods

Mods add objective kinds in `data/config/battle_objectives.json`, each with an `effectClass` that runs from the
objective's creation. The host sends its objectives in `BATTLE_INFO`; `MissionDefinition` makes each through
`JoinSetup.kindToMake(type)`, which looks up the kind's effect class in the merged JSON and asks
`SystemWatch.allowObjective`:

| Effect class | On a joiner |
| --- | --- |
| The game's own (`com.fs.starfarer.`) or none | Made as is |
| In `CAMPAIGN_WORLD_OBJECTIVES` (needs the campaign battle, star systems or campaign scripts: SOTF Extra Combat Objectives' derelict fleet) | Held back |
| In `CAMPAIGN_DATA_OBJECTIVES` (needs only the player fleet, cargo, credits, level: Secrets of the Frontier's reinforcement objectives; the add-on's asteroid and credit cache) | Made if the stand-in campaign is in place; otherwise held back |
| Any other mod's | Made, after `objective:<type>` is written as pending to the safety file; trusted when a battle with it ends normally; held back from the next start if the game died |

**Held back** (`ObjectiveMirror`): the objective is made as its own kind, so it has its real name, icon, sprite and
capture time, but runs `MirrorObjectiveEffect`, which does nothing. The game builds an objective's script from its
kind's spec (`com.fs.starfarer.loading.specs.oOOo`, fetched with `SpecStore.o00000(oOOo.class, type)`) when the
battle starts, through `ScriptStore`, which loads the class by name. So `blank(type, effectClass)` points the spec's
effect class at `wingman.MirrorObjectiveEffect` (the setter is named `Ó00000(String)`; the build compiles against
`tools/stub/.../SpecStore.java`) and `restore()` puts the JSON's class back. Restore runs in the joiner's
`prepare()` once the battle has started, in `onGameLoad` (before any campaign battle), and on the title screen once
a change is over 15 s old (the mission list sets the mission up just to show it). Nothing on the host is changed.

What a held-back objective does reaches joiners through the host: ships it spawns or parks are copied (neutral
side 100 included), side changes follow (`SIDE`), its tooltip text comes by `OBJECTIVE_TEXT`, and its removal by
`Orders.GONE`, and the lines its script writes to the message feed come by `COMBAT_MSG` (8.5).

If changing the spec fails, the kind gets the older stand-in instead: a `sensor_array` at the same spot, listed in
`JoinSetup.standIns` so the battlefield check doesn't count it as missing; `ClientOrders` places, owns and orders it
like the real one. The lists were made by reading each class's bytecode for `SectorAPI` / `CampaignFleetAPI` calls.

### 8.3 The stand-in campaign (`CampaignStandIn`)

A joiner's battle starts from the title screen, where the game has a sector but no player fleet and no campaign
data; mods' battle scripts that read them would crash.

- **What the host sends** (in `BATTLE_INFO`, campaign battles only, `HostEnvironment.campaignFacts`): credits; the
  player fleet's commodities (≤ 300); the player's name, portrait, level and skills (≤ 120); story points; the
  sector memory's simple values (boolean, number, text ≤ 64; keys ≤ 80; ≤ 600; `$wingman*` skipped).
- **What the joiner makes** (`install`, from `JoinSetup.prepareCampaign` in `MissionDefinition`, before any objective
  or ship exists): only if the sector has no player fleet and `campaignStandIn` is on. A fleet
  (`createEmptyFleet("player", …)`) with that cargo and credits and a commander copied from the host's character,
  set as the sector's player fleet; story points on `getPlayerStats()`; every host flag the sector's memory lacks.
  The fleet is added to a location so `getContainingLocation()` is never null (Arma Armatura's `armaa_strikeCraft`
  reads its terrain on every ship made in battle): hyperspace when the host's battle is there, otherwise an empty
  star system made for it (`createStarSystem("Wingman stand-in")`, removed with the stand-in).
- **Fleets for ships:** each puppet's fleet member joins the side's stand-in fleet (side 0 the player fleet, side 1
  a separate `independent` one), so `getFleetData()` is never null. Fighters are left out.
- **Removal** (`remove`): on `leaveBattle`, and from the title screen when a stand-in older than 15 s is found (the
  join mission, and the mission list's preview, set it up just before the battle). Everything set is undone.
- **Not possible:** a player character where the game has none, faction relations, markets, complex flag values.

### 8.4 The compatibility report (`Compat`, F2)

A scan of all weapons, ship systems and hullmods, by source mod and script kind (missile hit, gun hit, on-fire,
beam, system, hullmod), plus evidence from play: `good`/`odd` counters per missile, shot, script and system,
saved in `saves/common/wingman_compat` at the end of each battle. "Worth a look": any failed script or system,
and missiles or shots that needed help at least 5 times and more often than they worked. Delete clears it.

---

### 8.5 Battle messages (`MessageTap`, `MessageSpy`)

Lines scripts write with `CombatUIAPI.addMessage` (an objective's hail, a system's warning, a mod's notices)
are sent from the host and shown on joiners, whose own scripts' lines are held back meanwhile, so every screen
reads the same and held-back objectives still speak.

- **The feed.** `CombatState.messageWidget` (class `com.fs.starfarer.class.C`) is private and made with a plain
  `new C()`. At battle start (host `start()`, joiner `prepare()`, re-checked every 3 s) `MessageTap.install`
  swaps it for a `MessageSpy`, a subclass, through `MethodHandles.privateLookupIn` (looked up by name so the jar
  still targets Java 8). Java source can't name the package `class`, so `MessageSpy` is compiled against
  `tools/stub/com/fs/starfarer/klass/` and `tools/PatchPackage` rewrites `klass` to `class` in the compiled class.
- **Telling scripts' lines from the game's.** The game's own lines (destroyed, disabled, deployed, retreated,
  objective taken, no command points) all go through `o00000(Object...)`, which forwards to
  `o00000(int, Object...)`; `addMessage` calls the second directly. `MessageSpy` counts entries into the first, so a
  line reaching the second at depth 0 is a script's. Each PC still makes the game's own lines from what it sees.
  Wingman's notices (`Ui.say`) set `MessageSpy.ownLine` and are never sent or held back.
- **Parts.** The feed takes text, `Color`, ships (as `Ship`, deployed member or fleet member: an icon and name)
  and objectives. Ships go as the host's net id and objectives as the host's index; the joiner swaps in its
  copies, or the name (`?` if unknown).
- **Joiner.** While connected with `hostMessages` on, the sink returns false for its own scripts' lines and
  host lines are shown through `showHostLine`, past the sink. Malformed messages are skipped with one log line.
- **Limits.** 30 lines a second from the host (the rest of that second is dropped and logged once), 40 parts,
  1,000 characters per text part. If the swap fails (another Java, a changed game) the feature is off with one
  warning and both screens behave as before.

### 8.6 Carriers' fighters (`BayLink`)

A copy of a carrier never launches anything (its refit time is ×100,000 and anything it launches is removed);
the host's fighters are built as wings of their own (7.11). `ShipAPI.getAllWings()` goes hangar by hangar
(`bay.getWing()`), so a copy's hangars looked empty: a greyed carrier panel, and carrier scripts finding no
fighters. The host sends each fighter's carrier and hangar (`ShipSpawn.carrierId`, `bay`: the index in
`getLaunchBaysCopy()` of `wing.getSource()`). When a host wing is first built on a joiner (`joinHostWing`),
`BayLink.attach` tells the wing its carrier and bay (`setSourceShip`, `setSourceBay`) and points the bay at the
wing's deployed-member record: the private field `Òôôo00` (type `CombatFleetManager$O0`) of the game's bay class
`combat.entities.ship.A.C`, set through `MethodHandles.privateLookupIn`. `bay.getWing()` then returns the host's
wing; the bay's own replacements stay held back by the refit time. Setting `fighterHangars` (joiner).

### 8.6b Bays that build nothing (`BayQuiet`)

A puppet carrier's fighter refit time is ×100,000, so it never finishes a fighter, but a bay that loses one still
starts a replacement and the game draws its build sparks (`renderers.fx.oOOO`) while it is pending. Each frame,
for every puppet with bays, a pending replacement is cleared through `java.lang.invoke` on the bay class's private
fields, as the game does when a build completes: the request (`O\u00f5\u00f4o00`, `CombatFleetManager...M$Oo`),
its timer (`\u00d6\u00f4\u00f4o00`, `IntervalTracker`), the effect (`String.String$interface`) and the queued count
(`super.String$interface`, 0). Nothing is launched. On failure: one log line, sparks as before.

### 8.7 MagicLib subsystems (`Subsystems`)

MagicLib's `MagicSubsystem.advanceInternal` reads the hotkey only when the ship is the engine's player ship;
otherwise it calls the mod's `shouldActivateAI`. On the host a joiner's ship is never the player ship, so the AI
logic fired its subsystems. MagicLib is reached by name through `java.lang.invoke` (no build or run dependency):
subsystems are the values of the ship's custom data entry `magicSubsystems`.

- **Joiner** (`sendSubsystems`): for the ship the player flies, each subsystem's state is compared frame to frame;
  READY → IN/ACTIVE (or ACTIVE → OUT for a toggle) sends `SUBSYSTEM` (ship id, class name).
- **Host** (`runSubsystems`, each frame): for every ship a joiner flies (not on autopilot) each READY subsystem is
  parked: its `state` field set to COOLDOWN and its `stateInterval` to 10⁹ s, without `setState`, so no callback of
  the mod runs. A request un-parks the named subsystem and calls `activate()` if `canActivateInternal()` and
  `canActivate()`. Ships no longer flown by a joiner are un-parked (READY), so their AI uses them again.
- **Fighters a joiner flies** (`keepPilots`): their `RemotePilotAI` is put back the same frame if anything replaced it
  (UAF's Rafflesya dock), not after 0.5 s as for ships.
- **Scripts that switch "the player" to another ship:** a fired subsystem that changes the host's player ship
  (Isenoria's override) is undone at once; the subsystems the script just added to the new ship are removed on
  the host (`dropAddedSince`). Each frame (`guardHostShip`), a switch of the host to a fighter that isn't from
  the host's previous ship is undone (vanilla Transfer Command never picks a fighter). On the joiner the same
  script switches the local player ship; `watchPlayerShip` then claims it, and a fighter is claimable when it is
  from the ship the player flies (`WingmanHostPlugin.linked`: wing source ship, or its parent station). The host
  grants it under the same rule. With Wingman's agent (8.9) the subsystem is fired as part of the joiner's ship's
  frame, so the hand-over moves the joiner on the host as well, and the host's player ship never changes.
- **Everyone else's copies:** the host relays each use to the other joiners (`relaySubsystem`, `SUBSYSTEM` host →
  joiner): a joiner's use once the host has fired it, and the host's own (`Subsystems.pressed` on the host's ship).
  On a joiner, the subsystems of every ship marked `wingman_remote` are parked each frame like the host parks
  joiners' (`holdOthers`), and a relayed use fires the named one on that copy (`fireForOther`, inside
  `AgentGate.runAs`; a hand-over of this PC's player it makes is undone, as on the host). So another person's
  abilities fire on your screen when they used them, not when your copy's AI logic would have.

Setting `joinerSubsystems` (both).

### 8.8 Ships that land on carriers, and mods that take the AI slot (`keepPilots`, `watchLandings`)

A joiner flies their ship on the host through its AI slot (`RemotePilotAI`), so a mod that puts its own AI in
that slot takes the ship from the joiner. Arma Armatura does this on purpose: its `armaa_strikeCraft` hullmod
sends a damaged or empty strikecraft to a carrier with a docking AI (`setShipAI`, then `beginLandingAnimation`),
repairs and rearms it while landed, and on take-off calls `setAnimatedLaunch` and `resetDefaultAI`. For the
local player it does this only on autopilot; any other ship it treats as AI-flown.

- **Host, `keepPilots` (each frame):** for every ship a joiner flies themselves (not on autopilot) the custom data
  mark `armaa_refitSuppressedUntil` (Float, battle time) is set to now + 1 s; Arma Armatura's `isRefitSuppressed`
  then skips it, unless it is already landing. With the joiner on autopilot the mark runs out and the mod refits
  the ship as it would the player's. `RemotePilotAI.lastFlown` is the battle time of its last `advance`; if it is
  0.5 s old while the joiner flies the ship and the ship is not landing, landed, retreating or `controlsLocked`,
  the pilot is put back with `setShipAI` (this covers `resetDefaultAI` after take-off and any other mod's AI swap).
- **Host, `watchLandings`:** ships with an id and no wing are watched for `isLanding() || isFinishedLanding()`; a
  change sends `LANDING` (ship id, nearest own carrier with launch bays or −1, 1 landing / 2 launched).
- **Joiner:** every copy carries the mark at 10⁹, so the joiner's copy of the mod never docks a ship itself.
  `LANDING` plays `beginLandingAnimation(carrier copy)` or `setAnimatedLaunch()` on the copy; positions keep
  following the host as usual. Hull, armour, CR and ammo restored on the host reach the joiner through the
  normal damage state.

### 8.9 Wingman's agent (optional, host): mods' "player ship" scripts for joiners (`agent/`, `AgentGate`, `AgentLink`)

On each PC, a ship another person flies is an AI ship (on the host: joiners' ships; on a joiner: the host's
and the other joiners'), so any script that does something for `engine.getPlayerShip()` (a "switch to a fighter"
override, an ability aimed at the cursor, a status line) never does it for that person's ship there, and the PCs
disagree about what the script did. Each such mod could be
handled one by one (8.7); the agent handles the pattern instead. It is a Java agent in its own jar,
`jars/WingmanAgent.jar`, not listed in `mod_info.json`: the game can't load an agent itself, so the host adds
`-javaagent:<path>` to the game's launch settings (USER_GUIDE section 9). Without that line nothing below happens
and Wingman works as before.

**Loading.** `Agent.premain` registers a `ClassFileTransformer`. As each class is defined (by any class loader:
the game's, mod jars', Janino's for loose scripts) it is offered to `Patcher` with a rule set chosen by name
(`Agent.rulesFor`): JDK, Wingman and the game's libraries are skipped; the game's own classes (`com/fs/starfarer/`,
not `api/`) get the engine rules; `com/fs/starfarer/api/impl/` (vanilla scripts) and everything else (mods) get the
mod rules. Option `novanilla` (after `=` in the launch line) leaves the vanilla scripts alone; `off` patches nothing.
Interface code is never changed (`Agent.screenOnly`: classes named `MagicUI` or `StatusBarData`, MagicLib's and the
copies mods bundle, and anything in a `ui`, `gui`, `combatgui` or `hud` package), nor are achievements (an
`achievements` package, as MagicLib's): they ask "is this the player's ship?" to decide what to draw on this
screen or count for this player, and that must stay this PC's player, or other people's bars would be drawn
over yours and their kills counted as yours. Checked against MagicLib 1.5.6, LazyLib 3.0 and AshLib 2.2.3: what
is left to change there is MagicLib's subsystems and interference warning (status lines), LazyLib's
`CombatUtils.centerViewport` (camera), and sounds in interface elements that never run for another ship.
A class the patcher can't read is left exactly as it was (counted in the report), and so is every class whose
loader can't reach `wingman.agent.Hooks` (`Agent.seesHooks`: asked once per loader with `loadClass`, which must
return the agent's own `Hooks`), since a swapped call there would fail when it ran. `premain` sets the system
property `wingman.agent` (`on`/`off`), so Wingman's start-up line can tell "not started" from "started, but
Wingman's own loader can't reach it".

**Patching without a bytecode library.** Every swap keeps the code the same length and the operand stack the same
shape, so nothing else in the class changes: `invokeinterface Owner.m(args)R` (5 bytes) becomes `invokestatic
Hooks.m(Owner, args)R` (3 bytes) and two `nop`s; `invokevirtual`/`invokestatic` (3 bytes) becomes `invokestatic`
(3 bytes). Jump offsets, exception tables, line numbers and stack map frames stay valid, and the new constants
(the `Hooks` class and one Methodref per rule used) are appended to the end of the constant pool, where no
existing index moves. `Patcher` parses the pool (all tags up to 20), finds the Methodref/InterfaceMethodref entries
that match a rule by owner, name and descriptor, then walks each `Code` attribute instruction by instruction
(a full length table, including `tableswitch`/`lookupswitch` padding and `wide`) and swaps only the call
instructions of the rule's opcode that use those entries. `invokespecial` (a `super.` call) is never swapped.

| Rules | Call swapped | Hook |
|---|---|---|
| mod | `CombatEngineAPI.getPlayerShip()` | the acting ship, else the real one |
| mod | `CombatEngineAPI.setPlayerShipExternal(s)` | acting: `Bridge.moveRemote`: on the host the joiner is moved into `s` and the host stays; on a joiner nothing happens |
| mod | `CombatEngineAPI.maintainStatusForPlayerShip(...)` | acting: dropped (the joiner's own game shows it) |
| mod | `CombatEngineAPI.getTimeMult()` | acting: a throwaway `MutableStat` whose base is the real value (reads right; changes go nowhere: one player can't slow the battle for all) |
| mod | `CombatEngineAPI.isUIAutopilotOn()` | acting: whether that person flies by hand (`Bridge.manual`; on a joiner: yes). Scripts pair it with "is this the player's ship?" to pick keys or their own AI logic, as MagicLib's subsystems do |
| mod | `CombatEngineAPI.addPlugin(p)` | acting, and `p` keeps that ship in a field: `p` is noted as the joiner's |
| mod | `Mouse.getX()/getY()` | acting: where that person points, converted to this screen |
| mod | `Keyboard.isKeyDown(k)` | acting: the joiner's key (`Bridge.keyDown`; for now always "not pressed", never the host's keys) |
| mod | `ViewportAPI.set/setViewMult/setExternalControl/setCenter` | acting: dropped (this PC's camera is its own player's) |
| mod | `SoundPlayerAPI.playUISound/playUILoop` | acting: played at volume 0 / not played (interface sounds are for this PC's player) |
| mod | `MagicSubsystem.advanceInternal(f)` (invokevirtual) | run with the subsystem's ship acting |
| engine | `Ship.advance(f)` | run with that ship acting |
| engine | `EveryFrameCombatPlugin.advance(f, events)` | a plugin noted as a joiner's runs with that ship acting |

**The acting ship** (`Hooks.acting`, game thread only) is the ship whose frame is running, when a joiner flies it:
`actingFor(s)` is `s` if it carries `wingman_remote` (6.4, `PilotMarks`), its parent if `s` is a module of such a
ship, or the wing's source ship if `s` is a fighter from one; and only while a bridge is set and `Bridge.live()`.
Frames nest and restore (`try/finally`). Inside a joiner's frame, then, a mod's "is this the player's ship?" is
true for that joiner's ship, as it is on the joiner's own screen; outside, everything is as before. A plugin added
from a joiner's frame runs for that joiner only if it holds the ship (or one of its modules or fighters) in an
instance field, checked by reflection at `addPlugin`: a mod's battle-wide manager that happens to be added from
the first ship whose frame runs holds no ship, and must stay the host's.

**Both sides.** The agent is the same on every PC; what differs is the bridge Wingman sets. On the host it is
`AgentLink` (below). On a joiner it is `JoinerAgentLink`, set when the join battle is ready
(`AgentGate.startJoiner`): the acting ships are the copies of ships other people fly (marked `wingman_remote` by
the joiner's own `markPilots`), `moveRemote` does nothing and returns false (who flies what is the host's
decision; the pilot's own PC and the host move them), `cursor` is that pilot's aim from the host's updates (5.1),
and no key is ever pressed. So on every PC, a script running for a ship a person flies treats it as a player's
ship, and only that person's own PC shows the things meant for their screen.

**Wingman's side.** `AgentGate` is the only class that may be loaded without the agent: it checks once for
`wingman.agent.Hooks` (`Class.forName`; the sandbox allows `java.lang.Class`, and the agent's jar is on the system
class path, which the mods' loaders delegate to) and only then touches `AgentLink`, which implements `Bridge`:
- `live()`: the host plugin started, isn't disabled, and its engine is the current one (a bridge left from an
  earlier battle changes nothing).
- `moveRemote(from, to)`: the player flying `from` claims `to` under the same rules as a claim of their own
  (`WingmanHostPlugin.refusal`); `claimByScript` makes `handleClaims` answer with `ASSIGN` + byte 1, and the
  joiner's game sits down in that ship even when it wasn't in a fighter (`case Msg.ASSIGN`). Returns false if
  refused (the first five refusals are logged), and the script then still sees the joiner where they were.
- `cursor(ship)`: the joiner's `InputState.mouseX/Y` (map coordinates).
The bridge is set in `start()` and cleared on a host error. `runSubsystems` fires a joiner's subsystem inside
`AgentGate.runAs(ship)`, so a hand-over the subsystem makes moves the joiner (the old undo of a host switch stays as
a safety net). The agent's report (classes changed, calls swapped by kind, answers changed) is logged when the
battle starts and when it ends.

**Not covered:** screen-wide plugins (not added from a ship's frame) that act on "the player's ship" each frame
still act for this PC's own player only; joiners' keys aren't sent, so a mod hotkey read in a joiner's frame on the host is
never pressed there (the joiner's own copy of the script still sees their key); a script that slows time for its
pilot doesn't slow it for a joiner (on purpose).

**Tests** (`build.agent.sh`, local): `AgentRun` loads fixture classes shaped like mod, engine and MagicLib code
under the real agent and checks 79 answers with and without a bridge, inside and outside a joiner's frame
(modules, plugins, refused moves, a bridge that isn't live). `AgentCheck` runs the patcher over every class in
UAF, MagicLib, the game's jars and vanilla combat scripts, re-reads each changed class (nothing may match again)
and `AgentVerify` loads the changed mod classes under the agent with `-Xverify:all`: 0 errors (69 verified; the
rest need classes not here). The game's own classes can't be verified that way (their obfuscated names are only
accepted with verification off, as the game runs); for them `javap` shows that only the swapped instructions differ.

## 9. Title screen and joining

- **The panel** (`WingmanTitlePlugin`): the title screen's drifting ships are a real battle, so a combat plugin can
  draw there. The CO-OP button and panel are drawn in `renderInUICoords` (the click is noticed there by polling the
  mouse; `processInputPreCoreControls` swallows it). F1 how-to (shown once, versioned by `HELP_VERSION`), F2 report,
  F3 settings, F5 reset blocked systems and objectives. State that must survive a battle is `static`, because the
  plugin is made afresh each time the title screen loads.
- **The lobby page** (F4; opens by itself on the first `ROSTER`): the host's fleet and picks, see 6.5.
- **Joining:** Enter connects (`ClientLink`); `BATTLE_INFO` starts the auto-join countdown (Esc skips this battle);
  `join()` calls `TitleLauncher.start("wingman_join")`. If that fails, the Missions screen's **Wingman: Join Game**
  works the same way, and the mission shows its own join form when not connected.
- **`JoinSetup`:** the battlefield for the mission: the last `BATTLE_INFO` when connected, else it connects and asks
  (up to 2.5 s to connect, 3 s for the answer). Also `kindToMake` (8.2) and `prepareCampaign` (8.3).
- **`TitleLauncher`** is the one class using game internals (0.98a-RC8 names: `TitleScreenState`, `SpecStore`,
  `missionAccepted`), wrapped in `catch (Throwable)`. The mission list comes from a static method named `new`,
  which Java source can't call: the build compiles against a stub `SpecStore.coopNew()` (`tools/stub/`, never
  packaged) and `tools/PatchNew` renames that constant-pool entry to `new` in the compiled class (refusing unless it
  finds exactly one).
- **`JoinForm`** takes every key while open (address, name, password; Tab, Backspace, Delete, Ctrl+V); `SavedJoin`
  stores the last values.

---

## 10. Settings and saved files

**Layers** (`WingmanConfig.load`): defaults in code → `wingman_settings.json` in the mod folder (`#` comments allowed)
→ changes made in game (`saves/common/wingman_settings`, `key=value`) → the join form. Then `tidy()` clamps values and
sets `Dbg.on`, `Conn.compression` and `Conn.udp`. The settings page (`Settings.Menu`; F3 in the CO-OP panel,
Ctrl+Shift+O in battle) is the table `Settings.ITEMS`; a value put back to the file's is dropped from the overrides.
Adding a setting: a row in `ITEMS` and the field, `fromFile`, `get` and `set` in `WingmanConfig`.

| Key | Default | For | Live | What it does |
| --- | --- | --- | --- | --- |
| `hostEnabled` | true | host | no | Battles can be joined |
| `password` | "" | host | yes | Join password |
| `maxPlayers` | 4 | host | no | Players including the host (2-16) |
| `maxShipSize` | CAPITAL_SHIP | host | yes | Largest hull a joiner may fly |
| `readyCheck` | true | host | no | Hold the battle while everyone chooses a ship, then until everyone is ready |
| `campaignLobby` | true | host | no | Open the session on the campaign map: friends connect and pick ships before a battle (6.5) |
| `modCheck` | content | host | no | `content` (utility mods may differ), `all`, `off` |
| `autoJoin` | true | joiner | no | Join the host's battle by itself |
| `rememberShip` | true | joiner | yes | Board last battle's ship |
| `playerTags`, `playerOutlines` | true | both | yes | P-number tags, outlines |
| `announceAddresses` | false | host | no | Show this PC's addresses in battle |
| `port` | 24816 | both | no | TCP and UDP port |
| `snapshotRate` | 30 | host | yes | Updates per second (5-60) |
| `sendEverything` | true | host | yes | Every entity in every update (5.2) |
| `wholeWrecks` | true | host | yes | Break chance ×0 on every ship (`getBreakProb`, applied in `noteDeaths`): wrecks never break, on any screen |
| `compactUpdates` | true | host | yes | Compact update format (5.3) |
| `compressLink` | true | both | no | Deflate the connection |
| `useUdp` | true | both | no | UDP for updates and controls |
| `trimUnseen` | true | host | yes | Unseen enemies about once a second |
| `predictOwnShip` | true | joiner | yes | Local prediction of the player's ship |
| `syncTolerance` | 1 | joiner | yes | Multiplier on puppet tolerances (0-4) (plain mode) |
| `smoothCorrections` | true | joiner | yes | Puppets follow the model with a fading error offset (7.4) |
| `jitterBuffer` | false | joiner | yes | Ships and fighters shown a measured moment in the past (7.4) |
| `hideLatency` | true | joiner | yes | Own flux bar and ammo answer at once (7.5) |
| `steadyControls` | false | host | yes | Joiners' controls bundled with copies and played back evenly (4.7) |
| `bandwidthLimit` | 0 (off) | host | yes | Update bytes per joiner, KB/s before compression; priorities decide what waits (5.4) |
| `scriptedHits` | match | joiner | yes | `match`, `host`, `local` (7.9) |
| `matchWindowMs` | 100 | joiner | yes | Hit matching window (0-500) |
| `shotCounts` | true | joiner | yes | Fire as often as the host's weapons |
| `syncStats` | true | both | yes | Stats follow the host |
| `syncScriptMemory` | true | both | yes | Script memory follows the host |
| `campaignStandIn` | true | joiner | no | Stand-in campaign and fleets (8.3) |
| `hostMessages` | true | both | yes | Battle messages follow the host (8.5) |
| `fighterHangars` | true | joiner | no | Host fighters in the copy's hangars (8.6) |
| `joinerSubsystems` | true | both | yes | MagicLib subsystems follow the joiner (8.7) |
| `damageReports` | true | both | yes | Damage reports (7.10) |
| `debugLog` | false | both | yes | Detailed log lines (`Dbg`), traffic and timing every 30 s (without it: a short traffic line every 2 min) |
| `neverMirrorSystems` | [] | joiner | — | File only: system ids never mirrored |

**Files in `saves/common`** (the game adds `.data`): `wingman_settings` (in-game changes), `wingman_join` (address,
name, password), `wingman_seen` (how-to shown), `wingman_ship` (last ship), `wingman_systems` (system and objective
safety list), `wingman_compat` (report evidence). Passwords are stored as plain text.

**Diagnostics** (detailed log on): `NetStats` (bytes per message type, before and after compression, every 30 s),
`Prof` (time per part of the frame, host and joiner), and the `Wingman` lines named throughout this document.

---

## 11. Safety nets and limits

| Ring | Guard | When it trips |
| --- | --- | --- |
| A message | try/catch per message | Skipped; joiner stops co-op after 40, host drops a player after 20 |
| A feature | `broken` flags (codec, fog, damage listener, reports, stats, notes, armor, asteroids, orders, wings, hit matching, local-ship matching, stand-in campaign, message feed, objective mirror…) | That feature off for the battle; the codec falls back to the full format |
| A script | `GuardedMissileAI`, try/catch around on-hit scripts | That script off for that missile |
| A system or objective | `SystemWatch` breadcrumb | System not mirrored, objective held back, on that PC from the next start; F5 resets |
| A flown ship | `RemotePilotAI.failed` | Ship back to the AI, player told |
| The plugin | `advance()` catch-all | Host: ships to the AI, joiners told, battle ended for them. Joiner: "CO-OP STOPPED" with an offer to leave |

**Limits on input:** frames 256 KB host-side (4 MB joiner-side); 300 messages/s per joiner; 16 pending connections (4
per address); password lockout; names cleaned; counts capped in every decoder (ships 100,000; hurts 2,000; stats and
notes 5,000; objectives 64; skills 120; cargo 300; flags 600; armor changes ≤ cells); NaN, infinity and absurd
values (`Snapshot.bad`: beyond ±10⁷) refused or clamped; controls with bad numbers ignored whole.

**Addresses stay out of the log.** No Wingman log line names an IP address: this PC's addresses are shown only
in the CO-OP panel (and, with `announceAddresses`, in the feed through `Ui.show`, which never logs); connection
errors say "the host". As a safety net `Redact.scrub` (`wingman.net`) replaces IPv4 and IPv6 addresses, and
the host name typed into the join form (`Redact.alsoHide`), with `[address]` in everything `Ui.say` and `Dbg`
log and in connection errors.

**Limits on load:** joiner shows at most 2,000 missiles, 800 fighters, 200 wrecks, 600 asteroids; host tracks 150
wrecks; scripted shot reports 120/s; damage reports 120 per message.

**Wreck break lines (`WreckDice`).** A cut is worked out by `com.fs.starfarer.util.Tesselator` from the wreck's
outline and one random generator, `Tesselator`'s private static `Random` (field `\u00d500000`; a clock re-seed
mode exists but is off, flag `void` = true). Nothing else draws from it. The break-up helper
`combat.entities.H` rolls it twice over a wreck's life: the first cut is worked out when the ship dies (kept,
made later), each later cut when it is made; a joiner's `Ship.splitShip()` makes the same three `Tesselator`
calls at the moment it cuts. Wingman replaces the generator (through `privateLookupIn`) with `WreckDice.Dice`,
the same LCG as `java.util.Random` with readable/settable state. Host: after a frame in which the dice were
rolled, it is re-seeded; the frame's deaths (non-fighter, non-piece) and later cuts are counted, and if exactly
one rolled, its seed is kept (a death's for its wreck's first cut). `WRECK_SPLIT` carries the seed (0: unknown)
and an order (always 0 so far). Joiner: the dice are set to the seed's state before `splitShip()`; a cut that
can't be made with it breaks the chain and the game picks its own. Log: host "wreck cuts sent with their dice
seed", joiner "cut as on the host / cut here". Same cut needs the same outline (armor and earlier cuts match) and
the same float maths (same Java).

**Not covered:** encryption (all traffic is plain; play over a private VPN); objectives created mid-battle; mods that
keep overriding every ship's AI; ship types created at runtime from the campaign.

---

## 12. Build, test and release

- **Build:** `./build.sh /path/to/starsector-core` (or `build.bat`): compile the stubs, compile `PatchNew` and
  `PatchPackage`, compile `src/` for Java 8 with the stubs ahead of the game jars, patch `TitleLauncher.class` and
  `MessageSpy.class`, jar the `wingman` package to
  `jars/Wingman.jar`. The jar is committed so the mod works from a download. Before that, `agent/src` is compiled
  and jarred with `agent/MANIFEST.MF` (`Premain-Class`) to `jars/WingmanAgent.jar` (also committed), and Wingman is
  compiled with those classes on the class path (they are not packed into `Wingman.jar`).
- **Tests** (plain Java against the built jar, real sockets on 127.0.0.1; add `channels` to use the other driver):
  - `NetTest`: packing, player cap and numbering, version and mod refusals, every message's round trip, compact
    updates over 300 ticks, a lossy network (25% loss, reordering, a half-second blackout), compression, UDP,
    controls ordering, lobby, battlefield and campaign data.
  - `SyncBench` (not a pass/fail test): bandwidth (TCP compressed, UDP with headers, raw by kind) and joiner
    smoothness (distance from the host's positions one delay ago, and jitter) on a simulated battle through the
    real codec and a model of the joiner's motion. Arguments: `rate`, `seconds`, `delayMs`, `jitterMs`, `ships`,
    `fighters`, `missiles`, `seed`, `accel` (0: joiner extrapolates in a straight line, as before 0.6).
  - `AttackTest` (`-Xmx512m`): junk, oversized and half-sent frames, compression bombs, hello fuzzing, name
    spoofing, password guessing, connection and message floods, UDP junk, 30,000 mangled inputs for each of 13
    decoders, impossible numbers; an honest player must stay served throughout.
- **Agent tests:** see 8.9.
- **Player download:** `dist/make.sh` → `Wingman-<version>.zip` with both jars, `data/`, `mod_info.json`,
  `wingman_settings.json`, `dist/README.md` as README, `RELEASE_NOTES.md`, `LICENSE`, `NOTICE`.
- **Release checklist:** `debugLog` false in `WingmanConfig` and `wingman_settings.json`; version in `mod_info.json`,
  README titles and release notes; `Msg.PROTOCOL_VERSION` raised whenever a message's layout changes; changelog.
