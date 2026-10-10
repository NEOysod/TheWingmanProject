# The Wingman Project: how it works

Co-op multiplayer for Starsector battles. One player hosts; up to 3 friends join and each fly a ship
from the host's fleet against the AI. Combat only.

**Status: the network layer is tested, and every game call compiles against the real 0.98a API, but
the mod has never been run inside the game.** Expect the first launch to turn up behaviour problems. If something goes wrong the mod
switches itself off for that battle and prints a line starting with `Co-op:`; the details are in
`starsector-core/starsector.log` (search for `Wingman`). Send that part of the log back.

## Install
Unzip so you have `Starsector/mods/Wingman/mod_info.json`, enable "The Wingman Project" in the launcher.
Everyone needs the same game version, this mod, and the same ship/weapon mods as the host.

## Try it alone first (two copies of the game on one PC)
1. Hosting is on by default (`"hostEnabled": true`). Leave `hostAddress` as `127.0.0.1`.
2. Start Starsector, start any mission with a few ships on your side, deploy them all.
   You should see `Co-op: hosting on port 24816 ...`.
3. Start Starsector a second time. Missions -> **Wingman: Join Game** -> deploy the one ship.
   You should see `Co-op: joined ...`. You start as a spectator riding along with the host.
4. Pick a ship: target a friendly ship and use the game's **Transfer Command** order (the key is in
   Settings -> Controls). When the shuttle arrives you should see `Co-op: you have the helm.`

## Play with friends
- Host: nothing to change, hosting is on by default. TCP port 24816 must be reachable: same LAN, or a virtual LAN
  (Tailscale, Radmin VPN, ZeroTier), or a port forward on the router.
- Joiners: click the **CO-OP** button in the top right corner of the title screen. Type the host's address
  and your name, press Enter. It connects there and then, so a wrong address or a mod mismatch shows at
  once. Once connected, press Enter again to join the host's battle: the join mission starts straight from
  the panel. Esc closes the panel.
- Starting the mission from the panel is the one thing in this mod that uses the game's internals rather
  than its modding API (`TitleLauncher.java`, built for 0.98a-RC8). If a game update breaks it, the panel
  says so and you start **Wingman: Join Game** from the Missions screen instead; everything else is unaffected.
- The same form also appears inside the mission if there is no connection when it starts:
  start **Wingman: Join Game**. If there is no connection to a host, a join screen appears over the
  battle: type the host's address (add `:port` only if the host changed the port) and your name, press
  Enter. Tab switches field, Ctrl+V pastes. Both are remembered in the game's `saves/common` folder, so next
  time the mission connects by itself; the screen returns only when the host can't be reached. After a new
  address, start the mission once more so it can copy that host's battlefield.
- Host: when a battle starts, the message feed lists this PC's addresses (Radmin's `26.x.x.x` first) to read
  out to friends.

## Settings (`wingman_settings.json`)
`playerName`, `hostEnabled`, `maxPlayers` (default 4, up to 16, not recommended above 4),
`readyCheck` (default true), `modCheck` (`content`, `all` or `off`), `snapshotRate` (default 20 per second), `hostAddress`, `port`.

## Mod list check
When someone joins, their game sends its list of enabled mods (id, name, version) and the host compares
it with its own. If they differ, the joiner is refused and disconnected, and a pop-up on their screen
lists each difference: a mod they are missing, an extra mod the host doesn't have, or a version that
doesn't match. The host sees a line naming who was refused and why.
- `"modCheck": "content"` (default): mods marked as utility in their own `mod_info.json` (UI and tool
  mods, which add no ships or weapons) are allowed to differ.
- `"modCheck": "all"`: every enabled mod must match.
- `"modCheck": "off"`: no check; missing ships are then reported by the ready check instead.
The host's setting is the one that counts. Changing mods needs a game restart, so the check runs once,
at the moment of joining.

## Ready check
After the host finishes deploying, the battle is held paused and a READY CHECK panel appears on every
screen. Each joiner's game counts the ships it has built and reports that to the host, so the panel
shows per player: still loading (`40/62 ships`), loaded, ready, or missing ships because their mods
differ from the host's.
- Joiners: press **Enter** to mark yourself ready (only possible once every ship is accounted for),
  Enter again to cancel.
- Host: press **Enter** to start once everyone is ready. **Ctrl+Enter** starts anyway. With nobody
  connected, Enter starts straight away.
- Friends can join while the panel is up. Someone who joins after the battle has started just joins
  in, with no second ready check.
- Set `"readyCheck": false` to skip it.

## Disconnects
If a player drops out mid-battle, the host's game pauses (and so does everyone else's), their ship
goes back to the AI, and a pop-up names who left. **Enter** dismisses it; the battle stays paused
until the host unpauses as usual. A joiner who loses the host gets a "connection lost" pop-up, their
own game pauses, and it tries to reconnect by itself (see "Reconnecting" below).

The same lines also appear in the normal combat message feed, in case the panel fails to draw.

## Fleet orders and objectives
Only the host commands the fleet. Joiners see the host's orders in their own command screen:
- **Objectives** (nav buoys, sensor arrays, comm relays) appear where the host has them, and show who
  holds each one according to the host.
- **Orders** the host gives (capture, defend, rally, engage, escort and so on) are copied into the
  joiner's command screen with the ships assigned to them.
- **Retreat and search-and-destroy orders** given straight to a ship are copied too.
- **A joiner's own orders are removed again** within a fraction of a second, with a short notice. They
  would do nothing anyway: the ships on a joiner's screen are puppets.
- Full retreat is switched off for joiners.
Not shown yet: the host's command points. Objectives of a kind added by another mod, or more than six of
one kind, can't be shown.

## Battlefield (map size, nebula, asteroids)
A map's size can only be chosen before a battle starts. So the join mission asks the host about its
battlefield **at the moment you click the mission in the list**, and sets up the same map size and
backdrop. The briefing's first line says what it found.
- **Order matters:** the host starts their battle first, then the joiner clicks "Wingman: Join Game".
  If you clicked it earlier, click another mission and back again to refresh it.
- If the host can't be reached or isn't in a battle, a large default map is used and a pop-up in the
  battle explains how to fix it. Everything else still works.
- **Nebula** is copied from the host when the maps match. **Asteroids** are mirrored from the host
  twice a second.
- **Backdrop:** the background image, glow and tint, starfield, hyperspace animation and the planet
  behind the battle are copied. The planet and hyperspace are only known for campaign battles (they
  come from where the host's fleet is); a battle the host starts from the mission list has no planet
  to copy. A planet whose look was customised by a mod shows as its base type.
- Clicking the mission connects you to the host straight away, so the host will see you join (and
  listed as loading in the ready check) before you have started the mission.

## Loadouts, hullmods and officers
A joiner's copy of each ship is built from the host's: hull, every weapon in every slot (including ones a
hullmod puts there), weapon groups, fighter wings, vents and capacitors, hullmods (with which are built in
with a story point, permanent or switched off), and the officer with their skills. The hullmods' and skills'
own scripts then produce the effects, as on the host. After building, the copy's max flux, hull and speed
are compared with the host's; max flux is corrected to match and any difference is written to the log.
Fleet-wide effects from the host's commander skills are not copied.

## Battle speed
The battle runs at the host's speed for everyone, strictly.
- When the **host** flies a phase ship and cloaks, time slows for all players, as it does for the host.
- When a **joiner** flies a phase ship and cloaks, nothing slows down: on the host that ship simply acts
  faster, the way an AI-flown phase ship does.
- Speed-up mods only work for the host. A joiner's speed-up key is overridden.

## Autopilot
Pressing the autopilot key (U by default) on a ship you fly hands it to the **host's** AI, the same AI
that flies every other ship, and pressing it again gives you the controls back. The host sees a line
each time.

## Carriers
The regroup / engage fighter command works on a ship you fly: it is sent to the host with your other
controls. Fighters also go for the ship you have targeted, since your target is sent too.

Every fighter on the host is mirrored one for one, and grouped into the same wings it flies in on the host.
Fighters your own copy of a carrier launches by itself are cleared away at once; what you see are the
host's. The joiner's log has a `Wingman fighters` line every 30 seconds with the counts.

## Choosing a ship
Joiners start with no ship. Use the normal Transfer Command order on any friendly ship to fly it, and
again to switch. The host's own ship and ships another player is flying can't be targeted by Transfer
Command at all (and if a claim still slips through, you are put back in spectator view with a message). When you leave a ship, or disconnect, its AI takes over again.
If the host transfers into a ship a joiner is flying, the joiner is bumped to spectator.

## How it works
The host's game is the only real simulation. About 20 times a second it sends every ship, fighter and
missile: position, facing, hull, flux, shield and which weapons are firing. A joiner's game is an
empty stage where each of those is a puppet moved to match. Anything the joiner's own game creates by
itself (fighters launched by a carrier, missiles from a launcher) is deleted, so only what the host
has is on screen. The joiner's controls are sent to the host, where they fly the ship through the
same commands the AI uses. Hits on the joiner's screen are visual only.

## What works in this build / what doesn't yet
Intended to work: connecting, player cap, ships appearing and moving, hull/flux/shields/combat
readiness, armor damage, ammo counts, overloads, weapons and engines knocked out by EMP, weapons
turning and firing on screen, host-synced fighters and missiles, ready check before the battle,
pause and pop-up on disconnect, choosing a ship with Transfer
Command, flying it (move, turn, strafe, fire selected group, autofire switches, target with R,
shield/phase, ship system,
vent), ship
destruction, pause follows the host.

Not yet / known rough edges:
- Bullets and beams are still drawn by the joiner's game from "this weapon is firing", so individual
  shots are not exact. Missiles and fighters are.
- MIRV warheads are drawn as the parent missile. Missiles created by scripts rather than a weapon
  are not shown. Ship-system drones are simulated locally. Station modules are not shown.
- Fighters appear and disappear at the carrier rather than playing the launch/landing animation.
- Your own ship responds with a delay equal to your ping (no client-side prediction yet).
- Fighter- and missile-heavy battles use noticeably more bandwidth; lower `snapshotRate` if needed.

## Guard rails
Rules the mod enforces whatever else happens:
- **No ship on a joiner's screen can take hull damage locally.** A guard on every ship turns it away; hull
  is the host's number, and a ship dies only when the host says it died.
- **A ship the host still has must exist on the joiner's screen.** If the local game loses or destroys one
  anyway, it is rebuilt from the host's description (up to three times) and the joiner is put back at
  its helm if it was theirs. Each rebuild is logged as `Wingman guard:`.
- **Impossible numbers are thrown out.** A ship or missile state containing not-a-number or absurd values
  is dropped and the last good state kept; hull, flux and battle speed are clamped to sane ranges; flux
  never exceeds what the ship can hold.
- **Bad controls are ignored.** Controls from a joiner with impossible numbers are discarded whole.
- **One bad message doesn't end the session.** A message that can't be read is skipped and logged. Only a
  steady stream of them stops co-op (joiner) or drops the player (host).

## Bandwidth meter
Every 30 seconds while connected, each machine writes a `Wingman traffic` line to `starsector.log`:
how many KB/s it sent and received, how many updates were skipped because the link was backed up, and
which kinds of message used the most. The host's line also gives the number of ships, fighters and
missiles in the battle at that moment, so the cost can be related to battle size.

To keep the link from backing up, the host only sends a ship, fighter or missile when it has done
something a joiner could not have worked out alone: each one is assumed to carry on at the speed and turn
rate last sent, and is sent again as soon as it strays from that by more than a unit or two, or anything
else about it changes (hull, flux, shield, a turret moving, a gun firing). Everything is repeated at least
twice a second regardless, and in full straight after any update that could not be delivered. The host's
traffic line says what share of states needed sending.

## Ship systems, phase cloaks and hullmods
For every ship the host sends which stage its ship system and its right-click system (phase cloak, damper
field, a mod's own) are in: idle, charging up, active, winding down, cooling down. A joiner's copy of the
ship is put into the same stage, and the system's own script then runs in the joiner's game as written, so a
phase ship fades out and back, and a mod's system shows whatever it shows. While both sides agree the copy
runs by its own clock; it is corrected at once if one is running and the other is not, and after a quarter
of a second for smaller differences. On the ship you fly, the systems answer to your own keys and are only
corrected if the host still disagrees after most of a second (the real ship could not do what you asked).

Hullmod scripts need no message of their own: they already run every frame on every copy of a ship, and
react to that copy's flux, hull, systems and so on, which are the host's.

What a system does to the world is still the host's: a copy's teleport, speed boost or damage is overruled
by the host's positions and numbers. Drones a system launches are the joiner's own and are not matched to
the host's.

## Weapon scripts from mods
A mod can attach scripts to a weapon in several places: one that runs every frame, one that runs when it
fires, one on the beam, one when the shot hits, and one that steers a missile. On a joiner all of them run
in the joiner's own game, on the joiner's copies of the ships, exactly as the mod wrote them.

Missiles used to be the exception: the missile a joiner's copy of a weapon launched was deleted and a bare
copy of the host's was put in its place, which threw away whatever the mod's scripts had done to it at
launch. Now the missile launched here is kept and simply paired with the host's (same make, same ship,
nearby), and from then on the host says where it is. A missile released by another missile's script is
paired the same way. Only when nothing was launched here within a quarter of a second is a bare copy built,
and if the real one then turns up late it replaces the copy. A missile launched here that the host never
confirms is removed after half a second. The joiner's `Wingman fighters` log line counts each case.

What still differs: a script decides things from what it sees in the joiner's game (flux, ammo, targets,
timing), which follows the host closely but not to the frame, so an effect can come a few frames early or
late. Damage is never taken from a joiner's scripts; only the host's count.

## Missile lifetime
A missile's fuel runs by the host's clock. With every update the host says how long the missile has flown,
and at launch how long it can fly and when it arms; a joiner's copy is set to those numbers and is never
allowed to run out of fuel before the host's does. It burns out when the host's burns out and is removed
when the host's is. If a joiner's copy ends by itself while the host's flies on (it hit something on the
joiner's screen that the host's missed), it is put back after half a second, and from then on it can no
longer hit anything on the joiner's side.

## Missiles with scripts
Each missile is rebuilt on a joiner by its exact make, so a warhead released by another missile is that
warhead and not a copy of the missile that carried it. A missile steered by a mod's own script, or one that
splits into warheads, also runs that script on the joiner's copy, so the flash, sound and flares the script
makes are the mod's real ones. The host still decides where the missile is and what it is aimed at. A script
that throws an error on the joiner is switched off for that one missile (a line in the log says so) and the
missile just follows the host. Effects a script draws directly from somewhere other than the missile's own
script (a weapon's on-fire effect, for instance) are not mirrored.

## Rebuilding
`build.bat` (Windows) or `./build.sh /path/to/starsector-core` recompiles the jar against your game.
`test/NetTest.java` exercises the network layer without the game.

## Early guard rails
- **Keep-alive** (`Conn`): the writer thread sends a `PING` when it has had nothing to send for a second;
  the reader swallows them and stamps `lastHeardAt`. Both `HostSession.pump()` and `ClientLink.getState()`
  close a link silent for `LINK_TIMEOUT_MS`. It is done on the network threads because the game thread does
  not run mod code while the game is loading.
- **Limits** (`Conn.restrict`, `MAX_OUTBOX`): connections accepted by the host take frames up to 256 KB and
  300 messages a second; any link with 20,000 reliable messages queued is cut.
- **Access** (`HostSession.setAccess`, `kick`): `HELLO` carries the game build and the join password.
- **Battle end** (`HostSession.endBattle`): sent by the host plugin when `isCombatOver()`, or by the title
  plugin if the host is back on the title screen with a battle still open. The joiner calls `endCombat`.
- **Drift check** (`Msg.CHECK`): counts and id sum of ships and fighters, compared on receipt (TCP keeps
  it in order with spawns and removals). Three misses in a row send `SYNC_REQ`; at most three per battle.
- **System breadcrumb** (`SystemWatch`): ids of mod systems are written to the common folder before first
  being forced on, marked safe after five seconds, and blocked if still marked at the next launch.
- **Adaptive rate** (`WingmanHostPlugin.rate`): two thirds on each skipped update (floor 8), back up by a
  fifth of the configured rate after five clean seconds.
- **Retreat** (`InputState.RETREAT`, `RETREAT_NOW`): the host applies the game's own `orderRetreat`.

## Missile hits
A joiner's missiles have their collision class set to `NONE` from the moment they exist (`takeOver`, and
`untouchable` for locally made ones not yet paired). When a tracked missile disappears on the host with
`didDamage()` true, the host sends `MISSILE_HIT` (id, target ship, point, heading, velocity, shield or hull)
instead of `MISSILE_DESPAWN`. The joiner (`strike`) puts its copy 6 units short of the point, flying at it,
restores its collision class and lets the game make the hit on the next frame, which gives the real
explosion, sound and on-hit script. If the missile is still there 0.1 s later (`finishStrikes`), the hit is
carried out by hand at the exact point: the on-hit plugin is called with a `NoDamage` result, the missile's
explosion spec is spawned, and the missile is removed.

## Scripted shots and the compatibility report
- **Scripted shots.** `WingmanHostPlugin.trackShots` watches non-missile projectiles whose spec has an on-hit
  class; when one leaves the field with `didDamage()` it sends `SHOT_HIT` (ship, weapon, kind, target,
  point, heading, shield or hull), droppable, at most 120 a second. On a joiner,
  `ghostScriptedShots` sets the same kinds of shot to collision `NONE` as they appear and removes each as
  it reaches an enemy ship (shield radius, or 0.6 of the collision radius). `replayShot` fires a fresh shot
  from the same weapon 8 units short of the host's point; if the game has not made the hit 0.1 s later the
  on-hit plugin is called by hand. Beams are not covered: a beam effect runs every frame on each PC.
- **Compatibility report** (`Compat`). The scan uses `getAllWeaponSpecs`, `getAllShipSystemSpecs` and
  `getAllHullModSpecs` with `getSourceMod()` and the script class names, without creating any script. The
  evidence is per-kind counters (`good` / `odd`) fed from the missile and shot hit paths, revived and
  unbuildable missiles and failed scripts, saved at the end of a battle. Missile steering scripts and
  battle-wide plugins cannot be listed in advance; steering scripts are logged when first seen.

## Wrecks
A non-fighter that dies keeps its net id as a wreck (`WingmanHostPlugin.wrecks`). `trackWrecks` reports a wreck
gone with `DESPAWN`, and gives each new piece (`isHulk() && isPiece()`, not yet known) an id and a parent
(the nearest known wreck it still overlaps), sent as `WRECK_SPLIT`. Wreck positions ride in `SNAPSHOT` as
bare ship states under their own dead reckoning (sent when knocked off course, or every two seconds).
On a joiner, `makeInvulnerable` sets the break probability to zero and `DESPAWN_DESTROYED` sets the hulk
chance to one, so a destroyed ship always leaves exactly one wreck; `followWrecks` seals it against local
damage, moves it to the host's state, and carries out reported break-ups with `ShipAPI.splitShip()` (which
keeps the larger piece as the same object, as on the host). Late joiners get `SPAWN` + `DESPAWN_WRECKED`
(no explosion) for each wreck and then the break-ups in order. Fighter wrecks are not tracked.
(Some details changed later; see "Reconnecting, unseen enemies, armor changes, wreck guard rails".)

## Reconnecting, unseen enemies, armor changes, wreck guard rails

### Reconnecting
`WingmanClientPlugin.step`: when the link fails after a welcome (and not after a refusal), the joiner pauses,
remembers the ship it was flying (`rejoinShip`, `rejoinHull`) and calls `ClientLink.connect` again every
3 seconds, up to 10 times, with the saved address, name and password. On success the host treats it as
a new player and sends the whole battle (`RESET`, spawns, ..., `ASSIGN -1`); on that `ASSIGN` the joiner
puts the player in the remembered ship's puppet, `watchPlayerShip` notices and sends the usual `CLAIM`,
and the host grants or refuses it like any other.
Host side (`HostSession.pump`): a `HELLO` with the name of an existing player, from the same address,
whose old link is closed or has been silent for 1.5 seconds, replaces that player (`Player.replaced`);
the host plugin releases the old ship without pausing. Otherwise the old link runs into the normal
12-second timeout and the usual "player disconnected" pause.

### Unseen enemies
`WingmanHostPlugin.unseen(entity)` = side 1 and `engine.getFogOfWar(0).isVisible(entity)` is false. All players
are on side 0 and share its fog of war, so this is the same for everyone and is decided once per update,
not per player. An unseen ship or fighter is sent once a second (not every update) unless it fired or
its hull changed; an unseen missile every two seconds. `Sent.hidden` marks it, and the first update after
it comes into view is sent with weapon angles. Spawns, removals, hits and damage are never held back.
If the fog can't be read the feature switches itself off for the battle. Setting: `trimUnseen`.

### Armor as changes
`DamageState.Ship.armorMode`: `ARMOR_SAME` (armor left out), `ARMOR_FULL` (whole grid) or `ARMOR_CELLS`
(count, then cell index + value for each changed cell). `HostDamage` keeps the last state sent per ship,
`armorAgainst(last)` picks the mode (whole grid if a third or more changed), and the ten-second refresh
and any new joiner reset it to whole grids. The joiner merges with `mergeOnto(previous)`; changes with
nothing to build on are set aside until the next whole grid.

### Wrecks
- `CHECK` carries the wreck count and id sum too. The joiner counts wrecks it shows, break-ups it has
  yet to carry out, and `lostWrecks` (ones it knows of but could not make). A mismatch five checks
  running causes one refresh per battle at most.
- `DESPAWN_NO_WRECK`: the ship blew up and the host is over its wreck limit, so nobody tracks the wreck.
- A break-up the game refuses (`splitShip()` returns null) is retried six times at once, then every
  2 seconds until 30 seconds have passed; then the piece goes to `lostWrecks`.
- A whole wreck that disappears on a joiner while the host still has it is rebuilt once
  (`rebuildWreck`: the ship is spawned at the host's position and destroyed with no explosion).
- Wrecks left alone are sent every 4 seconds. Pieces that form while nobody is connected are tracked.

### Other
Ready check: after 60 seconds of waiting on someone, the host gets a line and a message about Ctrl+Enter.
The CO-OP panel title shows the mod's version (`Mods.ownVersion`).

## Timing and cheaper updates
- `Prof` (debug only): `begin()`, `mark(part)`, `end()` around the parts of `step()` on both sides; reported
  every 30 seconds next to the traffic line as `Wingman time (host)` / `(joiner)`.
- Fighters: in `buildSnapshot`, a fighter with `(tick + id)` odd is skipped unless it fired, fired last time
  or its hull changed. So each fighter is looked at 10 times a second at the default rate.
- Joiner controls: encoded 30 times a second, sent if the bytes differ from the last ones sent or 1/15 s
  has passed.
- `trackWrecks` piece search and the joiner's stray-asteroid sweep run every 250 ms.
- Compression (not used): the 0.98a-RC8 script loader (`com.fs.starfarer.loading.scripts.B`) refuses
  `java.io` (bar a short list), `java.nio.file.File*`, reflection, `javax.script` and `java.util.prefs`.
  `java.util.zip.Deflater` / `Inflater` are not on that list and work on byte arrays, so they should load.

## Small fixes: shots, hits, weapon lookup, campaign watch
- `trackShots` read an `IdentityHashMap` entry after `it.remove()` (throws); now reads first, and the
  call is fenced so only scripted-shot reporting stops if it fails.
- a replayed shot hit whose target is not a ship gets the nearest missile or asteroid within 90 units
  as its target, or the script is skipped.
- `weaponIdOf` falls back to `getActuallyAllWeaponSpecs()` (includes SYSTEM weapons) when nothing
  else names a weapon for a missile.
- `WingmanModPlugin` (`modPlugin` in mod_info.json) adds a transient `EveryFrameScript` on game load that
  calls `HostSession.endBattleIfOpen()` once a second while the game state is CAMPAIGN.
  `HostEnvironment.tick` advances its idea of each asteroid by `engine.getTotalElapsedTime(false)`, not by
  wall-clock time. Joiner input timers have a few ms of slack so 15/s is met at 60 fps.

## The joiner's own ship is predicted
Before: every frame the own ship's velocity was overwritten with the host's and its position eased toward
the host's (12/s), so every movement input took one round trip plus the easing to show. A system such as
UAF's Dimensional Drift (acceleration x50 for 1.5 s) lost its whole character that way.
Now (`WingmanClientPlugin.apply`, `own && predictOwnShip`): velocity and position are left to the local game,
which is already applying the player's commands to that ship. Each frame the gap to the host's
dead-reckoned position is measured; up to `15 + 0.12 * hostSpeed` units is treated as delay and ignored;
beyond it the position is pulled at `3 + min(9, over/25)` per second, velocity is blended toward the host's
when more than 60 over, and beyond 700 the ship is put straight on the host's position. Facing was already
local. Autopilot (U) and every other ship use the old path.
Input: checked every frame, sent on change at up to 60/s, floor 15/s.

## Tolerances
`closing(err, tol, amount, rate)` gives the share of a gap to close this frame: `amount` (i.e. 1/s) inside
the tolerance, plus `rate`/s on the excess outside it; when paused, 1 if outside, else 0.
Position tolerances (x `syncTolerance`): ships `3 + 0.05 v`, fighters `5 + 0.06 v`, missiles `6 + 0.06 v`
(v = host speed, units/s, so the second term is 50-60 ms of travel: the size of update-arrival jitter).
Heading: ships `1.5 + 0.05 w` degrees, missiles `3 + 0.05 w`. Snap distances unchanged (500 / 300).
Ship velocity is blended to the host's at 25/s instead of set outright. Wrecks and the own ship are separate paths.

## Host clock in updates, reconciliation
**Timestamps.** `Snapshot.hostTime` = host `gameClock`. Joiner: `gap = clock - hostTime` (clock offset plus
delay). The smallest gap of the last 8 updates is the promptest delivery; an update's lateness is its gap
minus that, capped at 0.1 s, and its states are stamped `clock - lateness` instead of `clock`. The cap and
the short window bound the effect when the two games run at different speeds (host at low frame rate).

**Reconciliation.** `InputState.seq` (16 bits on the wire, written last; `InputState.differ` ignores it).
Host: `RemotePilotAI.fly` sets `appliedSeq` / `sinceApplied` on the player's input; `buildSnapshot` puts
them in `pilotedSeq[]` / `pilotedAge[]` (age in 1/100 s, 2.55 = nothing usable). Joiner: `seqClock[seq&255]`
= battle clock when each input was sent; `pastT/X/Y` = own ship position each frame (256 frames).
`reconcile()` on a fresh ack: `when = seqClock[ack] + age`; own position at `when` is interpolated from the
record; `recon = host position - that`. `apply()` then closes `recon` beyond `3 + 0.02 v` units at
`4 + min(8, over/25)` per second, shifting the record by the same amount (`shiftPast`) so the correction is
not counted twice; over 60 the velocity is blended to the host's, over 700 the ship is moved outright.
Until the first usable ack the speed-based slack (see "Tolerances") is used.

## Backed-up links
- `Conn.sendDroppable` refuses only when the head of the outbox has waited over 150 ms, a write has been
  blocked that long, or over 5000 messages are queued. (It used to refuse at more than 6 queued messages,
  which a missile salvo exceeds within one frame on a perfectly good link.)
- `WingmanClientPlugin.keepPace`: every half second, host battle time elapsed / joiner battle time elapsed
  (from `Snapshot.hostTime` and the local clock). If off by over 3%, `pace` is moved 70% of the way and the
  engine time mult is set to `hostTimeMult * pace` (0.25 to 1.1). Restarts on pause, speed change or a gap.
- `sendEverything` (host): bypasses dead-reckoning skips and fighter halving in `buildSnapshot`; unseen
  enemies are still trimmed, weapon angles still only when changed.

## Starting systems on a puppet
Crash: `ShipSystemAPI.forceState(IN/ACTIVE)` on a DISPLACER-type system (`com.fs.starfarer.combat.systems.int`
in 0.98a-RC8) leaves its destination field null (it is set in `useSystem()`), and `systemActivated()` then
throws inside `Ship.advance`, outside any mod code. `followSystem` now, for the main system starting from
rest: clears cooldown, ensures one charge, calls `ship.useSystem()` (not on the own ship, which has the
player's key); `forceState` is the fallback after 0.3 s more, and only for systems with a mod stats script
whose class is not a known teleporter class (hard-coded name plus the class of any `displacer`,
`displacer_degraded` or `phaseteleporter` met). Stage alignment of an already running system, stopping, and
phase cloaks are unchanged.

## Cursor-aimed systems, teleports
Read from the 0.98a-RC8 classes: `Ship.useSystem()` is `giveCommand(USE_SYSTEM, null)`; `Ship.advance` turns
that command into `system.fire(param)`, which stores `param` as the system's `targetLoc`
(`ShipSystemAPI.getTargetLoc()`). `Ship.getMouseTarget()` returns one mutable `Vector2f` owned by the ship.
The DISPLACER class has two modes: the skimmer mode goes `range` along the ship's velocity (or facing when
nearly still) and never looks at the cursor; the teleporter mode goes to `targetLoc`, else to the mouse
target, clamped to range. An unshielded omni shield also faces the mouse target.
So `RemotePilotAI` now writes the joiner's cursor into `ship.getMouseTarget()` every frame and uses the
system with `giveCommand(USE_SYSTEM, new Vector2f(cursor), 0)`.
Joiner: `Puppet.jumped` is set when an update's position is further from the dead-reckoned one than
`150 + 0.3 * (|v| sums)`; `apply` then places the ship outright. `followSystem` returns early for a
teleporter-class system on a ship that is not the player's own.

## Ship systems used by a joiner
Before: `InputState.SYSTEM` = local `system.isActive()`, and the host used the system on that flag's rising
edge. Anything not "active" for a visible time, or re-used while active, was missed.
- `InputState.sysUses` (8 bits): joiner-side count of uses (`WingmanClientPlugin.noteSystemUse`). A use is: a
  charge gone (systems with charges); else leaving IDLE/COOLDOWN; else, still at rest, cooldown risen by over
  0.2 s. Changes made by the mod itself (forced stage, corrected charges) are not counted.
- `InputState.sysOrders`: 1 + `DroneLauncherShipSystemAPI.getDroneOrders().ordinal()`, 0 otherwise.
- Host `RemotePilotAI`: pending uses (max 4, dropped after 1.5 s) are issued one per 0.1 s as
  `giveCommand(USE_SYSTEM, cursor)`; drone systems are stepped until the order matches; a toggle system
  that is on while the joiner's flag is off is switched off.
- `RemotePilotAI.answerForSystem`: sets `AIFlags.SYSTEM_TARGET_COORDS` (cursor) and
  `AIFlags.TARGET_FOR_SHIP_SYSTEM` (ship target, else `Misc.findClosestShipEnemyOf(ship, cursor, FRIGATE, 4000)`)
  on its own flags and on `ship.getAIFlags()`. The vanilla scripts (`EntropyAmplifierStats.findTarget` etc.)
  read these when `ship != engine.getPlayerShip()`.
- `Snapshot.pilotedSysAmmo[]`: charges on the real ship; `matchSystemReadiness` copies them to the joiner's
  own ship after 1.5 s of disagreement and clears a local cooldown the host does not have after 1 s.
Engine classes read for this (0.98a-RC8): WEAPON-type systems (`systems.S`) fire their slot weapons at the
system's `targetLoc`; DISPLACER (`systems.int`); DRONE_LAUNCHER (`systems.oO0O`).

## System use guard rail; what "at the cursor" means
- The game's own player control (`CombatState.controlPlayerShip`, 0.98a-RC8) issues the system command as
  `USE_SYSTEM` with `new Vector2f(x, y)` (the cursor in world coordinates) for every system. `RemotePilotAI`
  does the same. What happens to that point is up to the system: `systems.S` (WEAPON type, e.g. Canister
  Flak, flares) hands it to its slot weapons, which are fixed and fire along their slot; STAT_MOD scripts
  ignore it unless they call `getTargetLoc()`; the DISPLACER class uses it only in teleporter mode.
- System type to engine class (from `loading.specs.do`): WEAPON `S`, ENGINE_MOD `L`, SHIELD_MOD `ooOO`,
  STAT_MOD `oo0O`, FAST_RELOAD `if`, AMMO_RELOAD `E`, TELEPORTER and DISPLACER `int`, PHASE_CLOAK `P`,
  DRONE_LAUNCHER `oO0O`, EMP `D`. Only `int` keeps state from `useSystem()` that `systemActivated()` needs.
- `WingmanClientPlugin.teleports(sys)`: `getSpecAPI().getSpecJson().optString("type")` is DISPLACER or
  TELEPORTER, or the class is a known teleporter class. Only these are exempt from `forceState`.
- `confirmSystemUse`: after a counted use of a non-toggle system, waits for host stage != IDLE or host
  charges below the count at the time of use; resends once after 0.8 s; then logs a warning (once per system
  id) and `compat.odd("system:<id>")`; successes are `compat.good`.
- Lidar Array (`LidarArrayStats`): purely stage-driven (IN: dishes turn to centre and ping, other weapons
  held; ACTIVE/OUT: hardpoint ballistic/energy only, range and rate of fire up). It needs nothing from the
  pilot, so it runs on the host from the use command, on the joiner's own ship from the key, and on other
  joiners' copies from the mirrored stage.

## Script-tended missiles
Case studied: UAF `uaf_lion_roar` (.wpn `everyFrameEffect` = `uaf_lionEffect`, which is also the
`OnFireEffectPlugin`). `onFire` adds the missile to the script's own list. `advance` each frame, per listed
missile: once clear of its ship sets collision class and mass, shoves ships and flames out missiles in its
path, pins it to an armor cell of a ship it is inside ("stuck"); when `flightTime > 0.8 * maxFlightTime`
it queues `uaf_mapField.addOrderNuke` at its position and calls `engine.removeEntity(missile)`.
- Joiner `takeOver`: `MissilePuppet.tended` = the launcher's `getEffectPlugin()` exists and is not a
  `com.fs.starfarer.` class. For a built copy, that plugin's `onFire` is called (the engine does not call
  on-fire hooks from `spawnProjectile`).
- `applyMissile`: tended missiles get `setMaxFlightTime(host's)` and are never extended.
- Host `trackMissiles`: a missile gone with `getHitpoints() > 0 && !didDamage() && !isFizzling() && !isFading()`
  is sent as `DESPAWN_SCRIPTED` (4).
- Joiner on `DESPAWN_SCRIPTED`: tended or own-AI missiles go to `releasing` for 0.6 s (`endWatched`); gone by
  then = the local script ended it (`compat.good`), else removed and `compat.odd` + one warning per kind.
  A missile with no script here gets the plain explosion, also counted. `reviveMissiles` leaves a tended
  missile that vanished past 60% of its flight alone for 2.5 s.
- Sticking needs no message: the host's stuck missile reports a position on the hull every update and the
  joiner's script pins its own copy the same way.

## Launchers firing ahead of the host
Puppet weapons are force-fired on every frame the last update's firing bit is set (`apply`). The bit is the
host's `isFiring()` or a latched "fired since last update", so it stays set for at least one update interval,
which at a low host frame rate is several joiner frames; a launcher whose refire delay is shorter than that
(this is the likely case for UAF `uaf_lion_roar`; not confirmed from a log) fired again. `noteLaunch` records each new local missile against its
`getWeapon()`; `launchSettled` clears it when the missile is adopted (confirmed) or dropped as a stray
(refuted, which adds the weapon to `silenced`); `mayLaunch` allows a forced fire only below the unconfirmed
limit (1 if `usesAmmo() && getMaxAmmo() <= 12`, else 3) and not while silenced. A cleared firing bit lifts
the silence.

## Modules, drones, stats, script memory, damage reports, shot counts
**Modules.** `eligible()` no longer excludes `isStationModule()`. `trackShips` announces a ship's modules
(`getChildModulesCopy()`) just before the ship; the ship's `ShipSpawn.moduleIds` lists them and each module's
spawn has `kind = MODULE` and `slotId`. Joiner: module spawns are held (`heldModules`); `spawnShip` builds a
variant for each (`buildVariant`) and sets it with `setModuleVariant(slot, variant)` before spawning the whole
ship; the children the game then creates are matched by `getStationSlot().getId()` and registered as puppets
with `module = true`. A module puppet's position and facing are left to the game (it is attached); everything
else is synced like any ship. Modules and drones never become tracked wrecks (`DESPAWN_NO_WRECK`), cannot be
claimed or ordered, and are not rebuilt by the rescue pass.
**Drones.** `kind = DRONE`. The joiner spawns one as an ordinary ship from its hull id (collision class
FIGHTER) and drives it like any puppet. Drones its own copy of a drone system launches are "ghosted" each
frame (`ghostDrone`: no AI, no collisions, alpha 0, guns held) rather than removed, since the system would
only launch replacements.
**Stats.** `ShipStats` lists 36 stats (20 `MutableStat`, 16 `StatBonus` measured as `computeEffective(1000)`).
Host `HostExtras.sendStats` (4/s) sends per ship a bit mask and the values that changed by over 0.5%
(`Msg.STATS`). Joiner keeps them in `Puppet.hostStats` and ten times a second per ship calls
`ShipStats.force`: remove our own modifier (`wingman_host`), read the local final value, and if it differs
add `modifyMult(host / local)` (or `modifyFlat` when the ratio is unusable). Fighters are not included.
**Script memory.** `HostExtras.sendNotes` (2/s): `getCustomData()` of the engine and of each non-fighter
ship; values that are Boolean, integer types, Float/Double or a String of at most 64 characters, under keys
of at most 80 characters not starting with `wingman`, at most 40 per ship. New, changed and removed
values go out (`Msg.NOTES`); a key that has changed on six checks running is marked restless and never sent
again. Joiner: `setCustomData` / `removeCustomData` on the puppet, or put/remove on the engine's map.
**Damage reports.** Both sides register an engine-level `DamageTakenModifier` (it changes nothing). Host
(`HostExtras.modifyDamageTaken`): per ship hit, sums pre-mitigation damage and EMP over 250 ms, keeping the
type, point, shield flag and source of the heaviest blow (`Msg.HURTS`). Joiner (`LocalDamage`): keeps what
its own game aimed at each ship over the last 0.3-0.6 s. On a report, if `amount - local > 40 + 0.5 * amount`
the shortfall is applied with `engine.applyDamage` at the reported point (no sound); the ship's
invulnerability modifiers make it harmless, and the mod's own call is not counted as local damage.
**Shot counts.** Host `FireTrack.shots[i]` counts cooldown restarts per weapon; `ShipState.shots` carries
(weapon, count) pairs after the firing bits, flagged by bit 32 of the flags byte. Joiner counts its own
restarts the same way: `ahead[i]` = local minus host, clamped to +-6 and dropped after 1.5 s without change.
Once a count has been seen for a weapon (and it is not a beam): fire if `ahead < 0`, or if the firing bit is
set, `ahead == 0` and the last local shot was over 0.12 s ago; otherwise hold. The launcher gate still
applies on top.

## Damage reports and drone systems
- `HostExtras.modifyDamageTaken` ignores damage whose `param` is a `BeamAPI`, or a `DamagingProjectileAPI`
  with a non-null `getWeapon()`. What remains (param null, a ship, some other object, or a projectile no
  weapon fired) is reported as before.
- `followSystem` returns early for a `DroneLauncherShipSystemAPI` on a ship that is not the player's own.

## Release checklist
- `debugLog` back to `false` in `WingmanConfig` and `wingman_settings.json` (on in test builds, off in releases).

## Settings page; scripted hits compared with the host's

**Settings.** `Settings.ITEMS` is the table of settings the page shows (key as in `wingman_settings.json`, label,
who it matters to, whether it can change mid-battle, the steps Left/Right go through). `WingmanConfig.load()` is
now `fromFile()` + the overrides from `saves/common/wingman_settings` (`key=value` lines, only keys in the
table) + the join form + `tidy()`. `WingmanConfig.get/set(key, text)` are the only by-name access. `Settings.Menu`
holds the picked row and handles keys; on the title screen it edits a fresh config, in a battle it edits the
plugin's live `cfg` in place and lists the changed keys so the plugin can do what a field change alone does
not (host: `session.setAccess` for the password, clamp the current rate; joiner: `slack`). A setting equal to
the file's value is dropped from the overrides.

**Which "script activations" can be compared at all.** The game calls scripts; it does not announce them.
What either side can observe without touching the scripts: a shot with an on-hit script striking
(`didDamage()` on the projectile), a missile striking or being ended by a script, a ship system changing
state, damage with no weapon behind it, an entity appearing. Particles, sounds, arcs and anything else a
script draws directly leave no trace in the API. So "compare the host's activation with ours, copy it if ours
is missing" is done per observable kind:

| activation | host tells | joiner's own counts? | missing here |
|---|---|---|---|
| on-hit script of a shot | SHOT_HIT at impact | yes, compared | host's hit replayed |
| on-hit script of a missile | MISSILE_HIT | no: the missile is the host's, collisions off | struck at host's point |
| script ends a missile (fuse) | DESPAWN_SCRIPTED | the copy runs its own script | ended when the host's is |
| ship system | state in snapshot | own ship: yes, confirmed in 0.8 s | started / re-sent |
| script damage | HURTS | yes, by amount | shortfall replayed |

**Comparing shot hits (joiner, `scriptedHits: "match"`).** Scripted shots are tracked in `ghostShots` with a
second cell: 0 watched, 1 hit noted, 2 made to pass through ("host" mode). A watched shot whose `didDamage()`
turns true (or that is gone between frames having done damage) becomes an `OwnHit` {kind, source ship,
target, clock}. On SHOT_HIT: `claimOwnHit` takes an unclaimed own hit of the same kind and source on the same
target (modules count as their ship) no older than window + 0.25 s (own-ship shots land before the host's
report can arrive); else if `shotAboutToHit` finds a live own shot of that kind within speed x window + 120
units of the host's point, the report is held until window; else `fireReplay` at once. A held report that
times out removes that candidate shot and replays. One own hit answers one host hit, so with rapid fire the
pairing may shift but the number of copies stays (host hits - own hits). Own hits never claimed are only
counted (`hitsOwnOnly`): an effect the host did not have cannot be taken back. Counters are in the joiner's
debug status line. Any exception sets `matchBroken` and the battle carries on in "host" style replays.

## Updates as changes; compressed link

**`SnapshotCodec`** (net package, no game classes, fully covered by NetTest). `Encoder` (host, one per battle)
and `Decoder` (joiner, one per battle) each keep the previous update as `id -> int[]` (17 numbers per ship,
8 per missile) plus the last turret angles per ship. Wire form per entity: zigzag varint of (id - previous
id in the list), a bit mask of the fields that differ, then for each a varint: zigzag(current - previous),
or current XOR previous for the bit-set fields (flags, firing lo/hi, system stages). Turret angles: 0 = no
news, 1 = count + mask of moved turrets + their new angles, 2 (full updates only) = the remembered angles
for a ship with no news, so someone starting from that update holds the same as everyone else. An entity not
in an update is forgotten by both ends and next sent against zeros. Header: kind (0 changes, 1 full), a
random per-battle `epoch`, tick, and for changes the tick they are built on. The decoder returns null unless
it has a previous update with the same epoch and that tick; any read error clears its memory.

A full update is the same code measured against nothing, so there is one format. The memory after an update
is that update's numbers whichever form it travelled in, so some players can be sent the full form and the
rest the changes of the same tick. `HostSession.Player.needKey` starts true and is set when a droppable send
is refused for that player, when they send `NEED_KEY` or `SYNC_REQ`, and for all when the host switches the
setting on mid-battle. `broadcastUpdate(delta, key)` picks per player. The host plugin falls back to the old
`SNAPSHOT` format for the rest of the battle if encoding ever throws; joiners read both.

Precision: position 1/16 unit (was a float), flux to the whole unit (was a float); everything else as
before. The joiner-side `Snapshot` object is unchanged, so nothing downstream knows the difference.

**Link compression** (`Conn`, `Zip`). One deflate stream per direction (level 1, raw, SYNC_FLUSH after each
batch of frames the writer sends), so each message is compressed with the earlier ones as its dictionary.
Handshake inside `Conn`, invisible to the game: after the hello (joiner) or welcome (host) a side that can
inflate queues `ZIP_OK`; a side receiving `ZIP_OK` queues `ZIP_ON`, sent plain, and deflates everything
after it; the reader, on `ZIP_ON`, feeds the rest of its buffer and all later bytes through an inflater.
`java.util.zip` is touched only by `Zip`'s nested classes, and `Zip.available()` catches a refusal by the
game's class loader (not on its block list in 0.98a-RC8). Frame size limits apply after inflating.
`NetStats.wireOut/wireIn` count real bytes.

Measured here (simulated, all ships active, 60 updates/s, per joiner; real battles will differ):

| ships/fighters/missiles | old | changes | changes + deflate |
|---|---|---|---|
| 10/20/30 | 110 KB/s | 44 | 23 |
| 30/60/150 | 400 | 153 | 64 |
| 60/120/400 | 920 | 345 | 120 |
| 100/200/800 | 1700 | 627 | 205 |

Host packing time on the game thread rose from about 0.09 to 0.2 ms in the 60/120/400 case; deflate runs on
the writer thread.

## Guessing and bands in the update format; work done on demand

**Codec.** `guessShip/guessMissile(base, dt)` advance the remembered numbers: position by speed x dt, heading
by turn rate x dt, missile flight clock by dt. `dt` is the difference of the two updates' `hostTime` (sent
exactly, so both ends compute the same float; 0 if not in (0, 1]). Fields are written and read against the
guess instead of the previous value. Heading fields wrap (`around`).

Encoder only: per field, if |truth - guess| is within `SHIP_BAND` / `MISSILE_BAND` (position 4 steps = 1/4
unit, heading 8/65536 turn, speeds 2 steps, flight 2 steps; flux `ShipState.fluxStep`, set by the host to 1%
of capacity, and never across zero), the remembered value becomes the guess and nothing is sent; otherwise
the truth. The memory therefore holds what the joiner reconstructs, not the truth, and the next comparison
is against that: error is bounded by the band and cannot accumulate. `ShipState.exact` (ships players fly)
turns the bands off. The full ("key") form now carries the remembered numbers, so a player starting from it
holds what the others hold. Both forms are made in one pass (`pack`).

**On demand.** `HostExtras` always registers the engine damage listener and keeps `hit` (ships damaged since
`HostDamage` last ran); `HostDamage.tick(.., hit)` reads a ship only if hit, `touch`ed (ammo changed, from
`trackFiring`), flown, never sent, or `(id + round) & 7 == 0`; with `hit == null` (listener unavailable) it
reads all as before. Stats: `touch(id)` from `buildSnapshot` on a change of flags or system state; read if
touched, flown, `busy` (a system not idle), new, or `(id + round) & 3 == 0`. Notes: `(id + round) & 1`.
`unseen(e, last)` caches the fog answer in `Sent` for 250 ms. `trackShots` runs on alternate frames (a shot
gone in between is still reported from the removal path). Joiner: `Puppet.statsDue`; `scanScriptedShots`
on alternate frames in "match" mode only ("host" mode must catch a shot the frame it appears).

Simulated (per joiner, 60/s): 60/120/400 battle 920 KB/s old -> 190 as changes -> 73 with deflate.

## UDP for updates and controls; per-player packers with history

**Codec.** `Encoder` is now per player (`HostSession.Player.encoder`, new each battle) and keeps a ring of the
last `HISTORY` (64) `State`s by tick: what that player holds after each update (per ship a `Rec` of numbers,
turret angles and shot totals; per missile its numbers). `encode(snapshot, builtOn)` builds on that state if
it is still held, else in full (`lastKey`). States are immutable; an entity that did not change shares its
arrays with the previous state. `Decoder` keeps the same ring; `decode` returns null with `result` OLD (tick
not newer than the last read: reordered or duplicate) or NOTHING_TO_BUILD_ON (ask for a key). `ackEpoch()/
ackTick()` name the last update read. The periodic key is now only for TCP (`KEY_EVERY`).

Events must survive loss: `ShipState.shotTotals` (host: `FireTrack.totals`, a running count per weapon) is
packed like the turret angles (`writeBytes/readBytes`: changed entries only); the decoder emits `shots` as
the difference from the state it last *read* (not the one this update was built on) and sets the firing bit
for those weapons. Turret angles are always handed on by the decoder for the same reason. The host plugin
now always attaches angles in compact mode (`last.angles`, so one-step tremble is still not news).

**Transport** (`Udp`). Datagrams, joiner to host: token(8) kind(1) then HELLO | ACK epoch(4) tick(4) | INPUT
epoch tick controls. Host to joiner: token kind then PONG | UPDATE tick(4) piece(1) pieces(1) data (pieces of
at most 1100 bytes; the joiner keeps pieces for the newest 8 ticks). TCP messages: `UDP_OFFER` token, port
(after WELCOME); `UDP_READY` (joiner saw a PONG); `UDP_MODE` 1/0 (host: send your controls by UDP / by TCP).
`ClientLink` handles the offer and mode in a `Conn.Tap` on the reader thread, so the title screen and the
battle plugin need know nothing; `sendControls`, `ack`, `pollUpdate` are what the plugin uses. `Udp.Host`
has a receive thread (token -> player; sets `ackTick`, `udpInput`) and a send thread with a bounded queue.

`HostSession.broadcastUpdate(Snapshot)` per player: UDP if `udpReady`, an address is known and acks are no
older than 3 s (gaps in our own sending excused); otherwise TCP, returning to UDP once 5 s have passed and a
datagram was heard in the last 0.5 s. UDP: base = `ackTick` if still in the ring. TCP: base = last sent.
Keys always go by TCP and are taken as held at once. Controls by either road go through
`InputState.decodeIfNewer` (sequence number, 16-bit wrap; anything is taken if what is held is a second old).

`java.net.DatagramSocket` is not on the game's block list (0.98a-RC8); `Udp.available()` and the callers
catch a refusal and carry on with TCP. Tested here: a simulated road losing 25% with reordering and a
half-second blackout (every update read is right, none unreadable, one key), and real UDP over localhost.
Not tested: the game itself, a real network, firewalls.

## Fleet commander skills; officered fighters; more stats

`ShipSpawn.fleetSkillIds/Levels`: the skills of `fleetMember.getFleetCommander()` for the ship (for a fighter,
its carrier's member; falling back to the side's `CombatFleetManagerAPI.getFleetCommander()`), default persons
skipped. Joiner: `underCommander(member, sp)` before `spawnFleetMember`: a stand-in `PersonAPI` per distinct
skill set (`commanders`), given with `FleetMemberAPI.setFleetCommanderForStats(person, null)`. In 0.98a-RC8
that method only stores the person and marks stats for update (the fleet argument is unused), and
`FleetMember.getFleetCommander()` returns it. Not verified in game: that every fleet-scope skill then acts on
the spawned ship; skills whose strength depends on fleet size are computed against the joiner's (empty)
fleet data and may come out at full strength, which the stat correction then evens out for the 43 stats.

Officered fighters: host `buildSpawn` no longer excludes fighters from the captain block; "own" means the
captain is non-default and not the carrier's captain object. Their ids go into `officered`, which
`HostExtras` treats as ships for stats and notes. Joiner `spawnFighter` sets the officer on the wing's fleet
member before spawning and on the leader `ShipAPI` after (`officerOf(sp)` is shared with ships).

`ShipStats.COUNT` 43: 36-38 recoil per shot / max / decay (MutableStat); 39 `getDynamic().getMod(PHASE_CLOAK_
SPEED_MOD)`; 40 peak CR duration; 41 phase cloak cooldown; 42 CR loss per second (StatBonus). System charges
(`getSystemUsesBonus`) are deliberately not forced: the charge count itself is already the host's.

## Auto-join

`WingmanTitlePlugin`: a `BATTLE_INFO` read on the title screen (the host only sends it to players it has let in,
at the start of a battle or when they connect during one) starts `autoJoinAt = now + 3 s` if `cfg.autoJoin`,
unless `autoJoinSkip` (Esc during the countdown), `leftOnPurpose` or `launchFailed`. `advance` calls `join()`
when it is due and no overlay is up. `WingmanClientPlugin.leaveBattle` sets the statics `hostBattleOver` (the
host sent BATTLE_END: clear the "host is in a battle" line and the skip flags) and `leftOnPurpose` (the player
walked out of a live battle: no auto-join until BATTLE_END or a manual Enter). Statics, because the title
screen's plugin is made afresh each time the title screen loads. `LOBBY` messages read on the title screen
fill the "In this game" line.

## Ship list in the ready check; roster; remembered ship

`Lobby` gains `maxShipSize` and per entry `ship` ("Name (Hull)"), filled by the host from `Player.ship`;
`handleClaims` marks the lobby dirty so the roster follows claims at once. Joiner: `pickable()` (own side, not
fighter/module/drone, alive; biggest first, then by id), `cannotFly(pp)` (host flagship, `transferState == 1`
from the snapshot's piloted list, hull size over the lobby's limit). F calls
`engine.setPlayerShipExternal(ship)`; `watchPlayerShip` then sends the usual CLAIM and the host's ASSIGN
decides, so the list adds no new authority path. Up (200), Down (208) and F (33) are consumed while the
ready check is up.

`takeRememberedShip()` runs once per battle when the joiner is synced and has no ship: reads
`saves/common/wingman_ship` (hull id, name), picks the free ship with that hull and name, or the only free
one of that hull, and boards it the same way. `rememberShip(pp)` writes the file on every granted ASSIGN.

## Player numbers and tags

`HostSession.Player.slot`: 2 onwards, the lowest free number at join; a reconnecting player (the `old`
replacement path) keeps theirs. `Lobby.Entry` carries `slot` and `shipNetId`; the lobby already goes out once
a second and whenever a claim changes, so joiners learn who flies what within a second. `PlayerTags.draw`
maps world to interface coordinates by the viewport's lower-left corner and visible size (rather than
`convertWorldXtoScreenX`, whose units vs. the UI scale setting were not certain), places "P<n>" at the
ship's centre plus 0.75 x collision radius on both axes, and caches one label per number. Host: P1 is
`engine.getPlayerShip()`, joiners from `Player.ship`. Joiner: P1 is the host's flagship puppet, others from
the lobby entries. Drawn first in `renderInUICoords` so panels cover them. `kick(n)` now looks up the slot.
The host's address list is logged instead of `Ui.say`.

## The map-size rejoin loop; outlines

From a same-PC log: every join logged "map: host 12000 x 12000, here 40000 x 40000 (DIFFERENT)", then a
"battlefield differs" rejoin, then the same again. `JoinSetup.fetch()` (called by the join mission's
definition, before the battle exists) drained the link, sent SYNC_REQ and waited 1.5 s for BATTLE_INFO; the
host answered later than that, so the default 40000 map was used every time. Now `JoinSetup.remember(b)` is
called wherever a BATTLE_INFO is read (title screen, join battle) and `battleOver()` on BATTLE_END; `fetch()`
uses the remembered one when connected and only asks (now 3 s) when there is none. A stale one (host left a
battle without BATTLE_END) costs one rejoin, after which the fresh one is remembered.

`PlayerTags.diamond`: GL line loop at the ship centre +/- 1.15 x collision radius on each axis (screen
units), 1 px, alpha 0.55, player colour; the tag at +0.75 r on both axes lies outside it.

## Guard rails against hostile connections

Found and checked by `test/AttackTest.java` (run: `java -Xmx512m -cp jars/Wingman.jar:<test classes> AttackTest [channels]`).
It starts a real host with one honest player, attacks it, and after each attack checks the honest player is
still served. 58 checks.

- **Door caps (`HostSession.admit`)**, on the accepting thread, before a `Conn` (and its two threads) exists:
  at most `MAX_STRANGERS` = 16 connections not yet let in, `MAX_STRANGERS_PER_PC` = 4 from one address. Each
  new arrival also sweeps the list: closed ones go, and ones that haven't sent one whole message in
  `SILENT_LIMIT_MS` = 5 s are closed. This works when `pump()` isn't running (the campaign map). A connection
  leaves the list (`settled`) once its hello has been handled. Measured: a 1,500-connection flood from one
  address went from 600 threads to a peak of 24; an honest player from another address joined during it.
- **`Conn(RawIO, maxFrame, maxPerSecond)`**: strangers get 256 KB / 300 per second from the constructor on,
  instead of `restrict()` from the next `pump()` (the frame buffer is allocated from the announced length, so
  the window mattered).
- **ZIP_ON only after our ZIP_OK** (`zipInvited`). Both sides offer
  before the other switched on. A deflate bomb from a let-in player is cut at the first oversized frame
  (9 MB inflated, heap unchanged).
- **Password lockout**: per address, `PASSWORD_TRIES` = 3 wrong (non-empty) passwords, then
  `PASSWORD_WAIT_MS` = 30 s between tries; cleared by a successful join. Measured 1 guess/second (was 174).
- **`HostSession.cleanName`**: control characters, C1, zero-width, line/paragraph separators, bidi controls and
  BOM dropped, whitespace collapsed, `MAX_NAME` = 24 characters. Applied to the hello and, on the joiner, to
  names in the lobby.
- **Number checks**: `ShipSpawn` (position, facing, max flux/hull/speed, skill levels), `BattleInfo` (size,
  planet) and `Orders` (points) throw `IOException` on NaN, infinity or |v| > 1e7 (`Snapshot.bad`), which the
  client skips as one bad message. `Extras.decodeStats` drops NaN/infinite values from the mask; large finite
  ones stay (mods use them) and are range-checked where applied. Snapshots and controls already had checks.
- Fuzzing: each of 13 decoders fed 30,000 mutated messages (cut, bit flips, 0xFF/0x7F runs, random, extended,
  NaN inserted): only clean `IOException`s, no other error type, no out-of-memory, slowest 5.6 ms.
- Protocol unchanged (38).

## Objectives and ships from mods

Found with Secrets of the Frontier 0.15.1 and SOTF Extra Combat Objectives 1.2.1. Both add objective kinds in
`data/config/battle_objectives.json` (21 between them), each with an `effectClass`, placed by SotF's
`SotfBattleCreationPluginImpl` through `MissionDefinitionAPI.addObjective` when a campaign battle is set up.
Their effects spawn ships when held: `SotfEmplacementEffect` a fixed turret (random variant, random spot near
the objective, rebuilt 60 s after it dies, swapped to the other side when the objective changes hands),
`SotfReinforcerEffect` reinforcement waves, SOTFECO's threat swarms ships and wings.

**Objectives.** `BattleInfo` now carries the host's objectives (type, x, y; at most 64, checked for impossible
values). `HostEnvironment.battleInfo` lists `engine.getObjectives()`. `JoinSetup.Info` passes them to
`MissionDefinition`, which calls `addObjective` for each, then still adds the 18 vanilla spares as a fallback.
`ClientOrders.onObjectives` now picks, for each host objective, the unused local one of that kind whose spot
at the mission's start was nearest (`home`), so the exact ones are used and spares stay parked. Their effect
classes then run on the joiner as on the host; ownership is still forced from the host every frame.
`ClientEnvironment.onBattleInfo` treats a host objective kind with no local objective left as a mismatch
(same "BATTLEFIELD DIFFERS" path, with its 30 s loop guard).

**Ships the joiner's own scripts make** (`WingmanClientPlugin.localShip` and friends). Before, `removeLocalStrays`
removed only fighters, drones and missiles; a non-fighter ship made by a script stayed, with its AI, beside the
puppet of the host's. Now:
- First sight: `PuppetAI` + `makeInvulnerable`, noted in `localShips` with the battle clock.
- `spawn()` for a plain ship (kind SHIP, no modules) first looks for a waiting local ship that is `sameShip`
  (side, hull id, and the host's weapon in every slot) and uses it as the puppet (`takeLocalShip`), moved to
  the host's spot; a script-pinned station has its `fixedLocation` moved too.
- A ship built from a SPAWN is remembered in `freshlyBuilt` for 3 s (`FRESH_WINDOW`); a matching local ship
  appearing in that time replaces it (`swapIn`), keeping target, turret angles and damage state; never the
  ship the player is in.
- Unmatched after 3 s (`LOCAL_SHIP_GRACE`): removed, with its modules. A variant picked at random differently
  on each PC simply doesn't match: the host's copy is shown and the local one goes.
Both maps are cleared on RESET. Any error switches the matching off for the battle (`localShipsBroken`).

Not covered: objectives a script creates mid-battle (the API can't make them on a joiner then), and a script
that tracks its local ship after it was removed unmatched (it then believes its ship is still alive, so a
"rebuilding" blueprint drawn by the script won't show on that joiner).

## Objectives whose scripts need the campaign

Two joiners crashed at battle setup with `NullPointerException ... SectorAPI.getPlayerFleet() is null` in
`SotfReinforcerEffect.setUpRemoteAIForSide` (line 389), called from its `init`, called from the game's
`BattleObjective` constructor inside `CombatEngine.init`. Nothing of ours is on that stack, so it can't be
caught. The joiner's game is at the title screen: `Global.getSector()` exists but has no player fleet.

`JoinSetup.kindToMake(type)`, used by `MissionDefinition` for each host objective, reads every kind's
`effectClass` from the merged `data/config/battle_objectives.json` and asks `SystemWatch.allowObjective`:
- vanilla effect (`com.fs.starfarer.`) or none: made as is;
- listed in `SystemWatch.CAMPAIGN_ONLY_OBJECTIVES` (exact class, or a package ending in a dot): never made;
  `sensor_array` (`STAND_IN_OBJECTIVE`) is made at the same spot and the kind goes into `JoinSetup.standIns`.
  Listed after scanning both mods' classes for `SectorAPI.getPlayerFleet/getPlayerPerson/...`:
  `data.scripts.combat.obj.SotfReinforcerEffect`, `SOTFECO.combatObj.SOTFECO_derelictFleetObj`,
  `SOTFECO.combatObj.campaign.`;
- any other mod effect: `objective:<type>` is written as pending (`P`) to the ship-system safety file before
  the battle is made, and trusted (`T`) when the joiner's battle ends normally (`objectivesSurvived`, from
  `leaveBattle`). A `P` found at the next start becomes `B`, and that kind gets the stand-in. F5 resets.
`ClientEnvironment`'s objective check skips kinds in `standIns`; `ClientOrders.onObjectives` falls back to
any unused objective whose mission spot is the host's exact spot (`standInAt`), so the stand-in is placed,
owned and ordered like the real one. On the host nothing changes: the real objective and the ships its
script brings are the host's, and joiners see those ships as usual.
