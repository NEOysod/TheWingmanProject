# Changelog

## 0.8-beta
Protocol 59: host and joiners need this build.

**Fixes from playtesting**

- The title screen's own menu could stay hidden for good after a few battles (the lobby hid it, and back from a
  battle it was taken to be showing). It is shown again whenever the lobby isn't up.
- A carrier's fighter panel on a joiner could list a wing twice: when a wing was lost and replaced, the old one
  stayed in the fleet's deployed list naming the carrier. It is taken out now.
- The battle keeps running when the host opens the command map (Tab), while friends are in it: a pause on the
  host pauses everyone. Space still pauses. Host setting `keepRunningInCommandMap` (on by default).
- Player tags (P1, P2...) sit clear of the officer portrait the game draws by a ship, with the player's name after
  the number in a smaller font.
- **Settings on the campaign map:** Ctrl+Shift+O opens the same settings page there (the campaign waits while it
  is up). The session kept open for friends takes the changes when it closes.
- **Player list for the host**, top right in battle: each player in their colour with the ship they fly (or
  "watching", "no ship", "autopilot", and "link quiet" when their connection goes silent). Four rows at a time;
  more scroll with the mouse wheel over the list. F3 hides it with the tags. Host setting `playerList`.
- Sixteen player colours (P1 to P16), one for each player a game can have; before, P5 and up were all white.
- The ready check and ship choice start when the host's battle does, even if the host left the flagship out or
  took no ship (flying the command shuttle); before they waited until the host deployed a ship.
- A joiner who changes their name while connected is reconnected under the new name (the host only hears the name
  on connecting, so the old one stuck until the host started hosting afresh).
- Ship cards in the lobby show D-mods (on their own line, near the top), S-mods marked "(S)", and hull mods built
  into the hull.

**Mods behave on joiners as on the host**

- **Hotkey abilities work for joiners.** Abilities a hull mod puts on a key (Special Hullmod Upgrades' subsystems
  on Left Alt, armaa's repair kit) run on the host for a joiner's ship, and never heard the joiner's key. Now the
  keys mods ask about travel with the joiner's controls. Each player keeps their own bindings: if you bound an
  ability to another key in LunaLib, your key counts for it. Needs the agent on the host.
- **Mod settings follow the host.** Mods' LunaLib settings (which effects are on, Special Hullmod Upgrades'
  custom stats, armaa's repair rules) are the host's while a battle runs, so copies of the host's ships behave
  the same. Your keys stay yours; your settings come back after the battle and nothing is saved. Setting
  `hostModSettings`.
- **Ship Mastery System.** The host's masteries, and those of the fleets it fights, now apply to the copies of
  ships on joiners. Before, joiners' copies had none. Your own campaign gets its own masteries back on its next
  load. Setting `shipMastery`.
- **Whole fleets.** The stand-in fleets now hold every ship of the host's fleets, not only those on the field, so
  scripts that look over a fleet (armaa's carrier bonus for strike craft, fleet-wide skills) count the same.
- Fixed: the LunaLib and Ship Mastery copies never ran (the game forbids its mods Java's reflection, which they
  used; they use method handles now, and the build fails if reflection creeps back in).
- Fixed: a ship carrying a weapon in a slot its hull doesn't list (UAF's corvettes, "SYS_FAKE") couldn't be built on
  joiners and was missing there; it is built without that weapon now (the host's ship still fires it).
- **14 more ship stats follow the host:** weapon and beam flux costs, shield upkeep and soft-flux conversion,
  phase cloak upkeep and activation cost, ship system flux cost, the range cap some hull mods set, and reload
  rates. Mostly felt on the flux bar and ammo of the ship you fly.

**Changing ship as a joiner**

- **No more stutter when you change ship.** The ship you just sat down in answers your controls at once. Before,
  for one round trip to the host, your controls pushed it one way while the host's positions pulled it the other.
- The "ship you flew last time" note is written to disk when the battle ends, not on every change of ship.
- A slow frame in the few seconds after a change of ship is noted in the log, with how much of it was co-op code.
- **A ship a joiner leaves gets its own AI back.** A ship given back to the AI (left, taken back by the host, put
  on autopilot, or a carrier freed after its fighter) gets the AI the game would pick in single player. That
  includes a mod's own AI for its ships. Before, it always got the game's usual AI.

**The lobby and the after-action report**

- **Ship cards show the officer and the loadout with pictures:** the officer's portrait, level, personality and
  skills as icons (elite skills framed in gold, with their names below), and each weapon and fighter wing with its
  picture and how many are fitted. The ship's picture has its weapons drawn in their slots, as in the refit screen
  (modules' weapons too), placed, faced and mounted as the hull's .ship file (and a skin's .skin changes) has them. Protocol 58.

- With LunaLib installed, the lobby's heading starts to the right of LunaLib's "Mod Settings" and "Version Checker"
  buttons, and its content below them; they no longer overlap.

- **The lobby is a screen of its own.** On connecting, the CO-OP LOBBY fills the screen (the game's menu steps
  aside): the host's fleet with a card for each ship (picture, class, CR, officer, weapons and wings, hull mods),
  who is here and what they picked, and the last battle. Tab switches to the full report. If the game's menu
  can't be moved aside (another game version), the lobby is the box in the corner as before.
- **After-action report.** At the end of a battle the host sends how it went: victory or defeat, battle time, each
  player's ship, kills (ships and fighters), damage dealt and taken and whether they made it out, and both sides'
  losses. Joiners see it in the lobby.
- **Aftermath.** Once the host has closed the battle's dialog on the campaign map, joiners are told what changed in
  the fleet: credits, ships recovered or gone, and the biggest cargo changes.
- **No more mission results screen for joiners.** Leaving a battle goes straight back to the lobby (on the report).
  If the game's internals differ, the battle ends the usual way.

**Mimics and other scripted enemies (UAF's Lingering Echoes and mimic bounties, Secrets of the Frontier)**

- Each campaign fleet in the battle is now described to joiners (faction, name, flags, and its commander with
  their skills and identity), and a joiner's copies of its ships belong to a stand-in of that very fleet. Mods
  that make enemies from a fleet's flag and commander skill (UAF's mimics: `$UAFmimicFleet`, the `uaf_mimic` skill)
  now see the same fleet, flag and commander on every PC.
- A ship's description also carries its variant's tags (UAF's `uaf_mimic_ship`), its fleet member id, and its
  officer's identity: id, faction, rank, post, personality, tags and memory flags (Secrets of the Frontier tests
  captains like Sirius, Sierra or Barrow by id and memory). Hidden hullmods were already copied.

**Fixes from a long hosted session**

- Carriers' hangars on a joiner's PC: in some copies of the game the hangar's private field has another scrambled
  name, and fighters weren't put in their carriers there. It is now found by its type, from the class file itself.
- The host's log no longer gets a "Loading JSON from [wingman_settings.json]" line every five seconds on the
  campaign map (the file is read once per game; changes made in game are kept apart and read as before).
- The traffic summary names every kind of message ("lobby fleet" instead of "type 55").
- After the host's battle ends, a joiner's game no longer rebuilds ships it clears away while leaving ("was lost or
  destroyed here but is alive on the host; rebuilt" at the end of simulator battles).
- The lobby's ship card shows a multi-part ship whole: each module's picture in its slot, turned as the slot is.
- The campaign lookups line says "not recorded" for a battle that had no stand-in campaign (simulator, missions),
  instead of "every lookup was answered".

**Mods' battle scripts and the campaign**

- **A much larger copy of the host's campaign** for joiners' stand-in campaign: mods' saved data (the sector's
  persistent data), the memory of the sector, the player, the player's fleet and faction, the enemy fleet, its
  commander and faction, and the place; faction standings; the enemy's faction, name and commander; the place's
  name and tags. Plain data only (yes/no, numbers, text and lists or tables of those): nothing a message holds can
  make a joiner's game create a class it names. Everything put into what outlives the battle is taken away after.
- The battle's context (`engine.getContext()`) on a joiner now holds the stand-in fleets.
- **Missed lookups (with the agent).** On a joiner's PC, the agent notes what mods' battle scripts ask the
  campaign's memory and saved data for and don't find; leaving the battle writes the list to the log, split into
  what the host has but couldn't send and what wasn't in the copy.

## 0.7-beta
Protocol 52.

**Lobby and ready check**

- **Friends can connect before the battle.** New host setting "Friends can connect on the campaign map" (on): the
  session opens as soon as a save is loaded. Joiners see a LOBBY page on their title screen with the host's
  fleet (ship, class, size, CR, officer), who is connected, and who picked which ship, and pick the ship they want
  (F; Delete for none). The host sees joins and picks in the campaign message feed. When the battle starts, a
  picked ship that is deployed is theirs at once.
- **Two stages before the battle.** First CHOOSE YOUR SHIP (the list of the host side's ships, Enter or F takes
  one, W watches without a ship), then READY CHECK (Enter ready, Backspace back to change ship). Ready can only be
  given with a ship chosen (or watching). The host's panel shows each player as loading, choosing a ship, or
  flying a ship and ready / not ready, with a count at each stage.
- New messages `ROSTER` and `PICK`; `READY` and `LOBBY` carry "watching".

**Battlefield**

- **The backdrop matches the host's.** Joiners used to get one planet guessed by the game's own rule, so with a mod
  that draws battles differently (Secrets of the Frontier draws every nearby planet and jump point) the backdrop
  didn't match. The host now sends the planets its battle really has, where they are, how big, and how each one
  looks (a named planet's own texture and colours), and the joiner shows exactly those. The background image is
  checked too.

**Netcode**

- **Smooth corrections (joiner, on by default).** Other ships, fighters and missiles now follow what the host's
  updates say exactly, and each correction (an update that changed the guess) fades out over a few frames, kept
  apart from where the ship is drawn. Measured on a simulated battle: copies 3 to 5 times closer to the host's and
  about half as jittery as before. "Off" goes back to the old way (corrected only beyond a tolerance).
- **Jitter buffer (joiner, off by default), for uneven connections.** Other ships and fighters are shown a moment
  in the past (as much as the link's unevenness needs, at most 0.25 s), between updates that have already arrived,
  so late updates make no difference. Measured with updates arriving up to 150 ms unevenly: ships 0.4 units off
  instead of 3.7, and a fifth of the jitter. Your ship, shots and missiles are not delayed.
- **Steady controls (host, off by default), for lossy connections.** Joiners send each set of controls with copies
  of the last two and their own clock; the host plays them back at an even pace, a little late, one set a frame.
  A lost or bunched-up packet costs nothing, and a tap shorter than a frame still happens.
- **Bandwidth limit (host, off by default).** When a big battle needs more than the limit, each update carries what
  matters most to that joiner first (near their ship; ships before missiles; anything firing; above all, whatever
  their copy has drifted furthest on), and the rest a moment later. At half the bandwidth the battle needs, ships
  stay within a unit.
- **My flux and ammo answer at once (joiner, on).** Your own flux bar and ammo change the moment you fire, raise
  shields or vent, and are kept in step with the host's figures with the delay taken out. Before, the bar showed
  the host's figures a round trip late.
- The packer's guess for something left out of several updates in a row no longer keeps its acceleration for ever
  (it fades), so a long-carried guess can't run away. No change in bandwidth.

**Mod compatibility: Wingman's agent (optional, host only)**

- **Mods' "player ship" scripts now work for joiners on the host, for every mod at once.** On the host a joiner's
  ship is an AI ship, so scripts that act only for "the player's ship" never did anything for a joiner there. A
  new optional Java agent (`jars/WingmanAgent.jar`, turned on with one setting in the game's launch settings, see
  the user guide) changes mods' code in memory as the game loads it: while a joiner's ship's frame runs on the
  host (hullmods, ship system, weapon scripts, MagicLib subsystems), `getPlayerShip()` answers that ship, the mouse
  is the joiner's cursor, and `setPlayerShipExternal` moves the joiner instead of the host (with the usual checks;
  the joiner's game follows). Status lines and changes to the battle's speed made for a joiner are dropped on the
  host. Plugins a script adds for a joiner's ship, and that keep hold of it, run for that joiner too.
- Java 22 and later refuse the game's obfuscated names in any class an agent changes (the battle wouldn't load:
  `ClassFormatError ... "this.super"`). The agent now finds out at start and leaves such classes alone there; the
  ship a mod's script runs for comes from wrapping mods' own per-ship methods (hullmods, ship systems, weapon,
  beam and on-hit scripts, plugins), which works on every Java.
- With Fast Rendering's loader the agent now changes the game's and mods' classes (it started, but changed 0
  classes: that loader never hands out a copy of the hooks put into it). Each loader gets its own copy of the hooks,
  and the state they keep is shared between them (an earlier attempt failed to start a battle with "loader
  constraint violation" for CombatEngineAPI).
- Games launched with their own system class loader (Fast Rendering's Java 2x setups) can't take `-javaagent`
  (Java stops at start); `wingman.agent.Launch` starts the agent another way there (see the user guide). A failing
  agent never stops the game any more; it starts without it.
- No bytecode library: each changed call keeps its length, so nothing else in a class moves. Checked against UAF,
  MagicLib, the game's own classes and vanilla scripts (274 calls in 80 classes; the changed mod classes pass the
  JVM's verifier). Without the setting, nothing changes.
- New: `ASSIGN` may carry a "sit down in it" byte (a script on the host moved the player).
- Fixed: a joiner switching carrier → fighter quickly could be pulled straight back into the carrier: the host's late
  answer to the earlier request (the carrier) was taken for a move the host made by itself. A joiner now follows an
  unrequested move only in an answer that has seen its latest request.
- Who flies what is written on the ships again right after ship changes, so an ability Wingman fires for a joiner
  in the frame they changed ship runs for that ship (with the agent: as theirs).
- **The agent works on joiners' PCs too.** There, the ships other people fly (the host's and the other joiners')
  are the "player's ship" for scripts running for them, aiming where that person points; a script can't move
  anyone from a joiner's PC (the host decides). Everyone's PC now agrees on what a player-only script did.
- Never on someone else's behalf, on any PC: moving this PC's camera, interface sounds, status lines, the battle's
  speed. Interface code (MagicUI and its copies, status bars, `ui` packages) is left unchanged, so other people's
  ability bars are not drawn on your HUD. A script reading the battle's speed for someone else's ship reads the
  real value.
- "Is the player flying by hand?" (`isUIAutopilotOn`) answers for the person whose ship it is (their U key), not
  this PC's player. MagicLib's achievements are left alone too, so a joiner's kill is not counted as the host's.
- **Where everyone points is sent with every update** (4 bytes a pilot), and each PC's copies of other people's
  ships point there, so systems and abilities aimed at the cursor aim the same everywhere, with or without the
  agent.
- **Other people's MagicLib subsystems fire on your screen when they used them.** The host relays each use (its
  own and joiners') to the other joiners, whose copies otherwise hold them, instead of firing them by their own
  AI logic at other moments.
- The host's log shows the agent's counters every two minutes (with the traffic line) and every move a script makes.

**For other mods**

- **`wingman.api.WingmanAPI`** (see `docs/MOD_API.md`): who flies which ship (also written on the ships as
  `wingman_pilot` / `wingman_remote`), messages between a mod's copies on the host and joiners (new message `MOD`,
  capped in size and rate), opening and running the host's session from the campaign, and keeping a ship for a
  player (they are seated in it when they join a battle).

**Wrecks**

- **Wrecks stay whole, by default.** New host setting "Wrecks stay whole" (on): no wreck breaks into pieces, in the
  host's battles too, so every screen shows exactly the same wrecks. Turn it off for break-ups as in single player.
- **With break-ups on, the cuts mostly match.** The game cuts a wreck with one random generator of its own (in its
  obfuscated `Tesselator`), seeded differently on each PC. Wingman swaps in one whose state it can read and set:
  the host notes which seed each cut was rolled with (a wreck's first cut is rolled as the ship dies, later ones,
  and any cut off a piece, as they are made) and sends it with the break-up; a joiner sets the same seed before
  the same cut. Tested: the first cut of a Paragon drew the same 136 numbers on both sides and left identical
  outlines. When several ships die or wrecks break in the very same frame the host can't tell whose roll was
  whose, and those cuts are left to each PC. The host logs each roll and cut ("Wingman wreck dice"), and how many
  cuts went with a seed at battle end.

## 0.6.4-beta
Protocol 47, as 0.6.2 and 0.6.3. Includes everything in 0.6.3.

- **Every charge-up weapon checked against the game's own weapon code** (its charge, burst and cooldown state
  machine). Firing on the host is the game's own, with the button held exactly as long as the joiner held it, so
  the game's rules apply as they are: a charge needs the button held to the end unless the weapon is marked
  "auto-charge"; a burst is cut short on release only if it is marked "interruptible".
  - **No more phantom shots on other screens.** A joiner's copy of a weapon is made to fire while the host's
    weapon "is firing", and the game counts charging down as firing, including a charge-up let go of half way.
    So a charge the host's player (or any pilot) abandoned carried on charging on everyone else's screen and fired
    a shot that never happened. A beam likewise lingered while the host's was fading. A beam or charge-up weapon
    whose charge is falling now counts as not firing.
  - **The by-hand launch for a fighter's weapon that ignores the order** (the Rafflesya bomb) follows the same
    rules: auto-charge finishes a begun charge, bursts go out one burst delay apart with one round each, an
    interruptible burst stops on release, and the cooldown starts after the last shot.

## 0.6.3-beta
Protocol 47, as 0.6.2. Includes everything in 0.6.2.

- **Bombs and missiles with a charge-up work exactly as in the game.** 0.6.2 made a quick click count: the host kept
  a weapon firing for up to 0.6 s after the press, which also fired weapons whose charge-up is there so that only
  a held button fires them. Now the host holds the button for exactly as long as the joiner did, and a weapon
  fires only if it was held until charged. The by-hand launch for a fighter's weapon that ignores the order
  (the Rafflesya bomb) waits the same way: the button held for the charge-up time, and again each time the weapon
  is ready while it is held.

## 0.6.2-beta
Protocol 47: everyone in a game needs 0.6.2. Includes everything in 0.6.

- **Weapon groups fire the way the game fires the player's own** (worked out from the game's code; see
  docs/TECHNICAL.md, GroupFire).
  - Holding fire on an alternating group takes turns again. The game moves an alternating group on to its next
    weapon while the button is held only for the local player's ship, so on the host only the first weapon fired
    (the Isenoria's main guns). The host now does that rotation with the game's own timing.
  - Linked groups fire the weapons whose arc covers the cursor, as in the game, also when the host has to fire
    them directly.
  - Each press of the fire button counts for weapons with ammo (bombs, missiles): the host keeps telling each one
    the press fires to fire until it has (up to 0.6 s, 1.5 s while charging up), so a quick click is enough. On a
    fighter, a weapon that still won't fire is fired by hand: its shot is launched from the weapon, using its ammo
    and cooldown. This is what makes UAF's Rafflesya bomb drop.
  - A fighter flown by the AI may have no weapon groups set up at all: a group the joiner fires is then fired from
    its loadout, by slot.
  - The 0.35 s "group did nothing" watchdog lasts one hold of the button instead of the rest of the battle. The
    extra firing of "system" weapons from 0.6 is gone (the game doesn't treat them differently in a group).
- **Back to the carrier when the fighter is done.** A joiner flying a fighter from their own carrier (UAF's
  Rafflesya from the Isenoria) is put back in the carrier when the fighter is lost, lands, retreats or leaves the
  battle. Before, they were left with no ship and had to use Transfer Command.
- **Audit of the game's code** for everything it does differently for the local player's ship
  (docs/GAME_CODE_AUDIT.md). Fixed from it:
  - A joiner's ship running faster than battle time (phase cloak, temporal shell, mod time systems) is moved in
    several steps a frame; the host answered only the first, so it coasted through the rest and turned,
    accelerated and fired a fraction as well as on the joiner's screen. Every step is flown now.
  - The fighter order (Z) is given as the game's own order: wings head for the cursor when told to engage, drop
    what they were doing, and go for the joiner's R target (or come home) as they do for the player's carrier.
  - Hold Fire is passed on to the host (new input bit).
  - Interdictor Array, Energy Lash and Drone Strike aim at the joiner's R target or the enemy nearest their cursor
    (they read an AI flag the host never set, and picked whatever was nearest the ship).

## 0.6-beta
Protocol 46: everyone in a game needs 0.6. Includes everything in 0.5.

- **Half the bandwidth, smoother movement.** Measured on a simulated battle (40 ships, 80 fighters, 200 missiles,
  one joiner): 39 KB/s instead of 80 over TCP, 52 instead of 121 over UDP, with copies that sit 10-15% closer to
  the host's and move 10-25% more evenly (35-50% more evenly over a laggy, uneven connection).
  - **Updates guess acceleration.** Both PCs work out how fast each ship's and missile's speed and turn are
    changing, so a missile speeding up or a fighter in a steady turn no longer has to be re-sent every update.
  - **What is guessed right is left out.** A ship or missile whose every value is where the guess puts it is not
    in the update at all; both PCs carry it on by the guess (at most 30 updates in a row).
  - **Joiners move copies along their curve.** Between updates a copy is carried on along its acceleration and
    turn (for up to a quarter of a second), not in a straight line.
  - Positions may sit half a unit (was a quarter) and speeds half a unit a second off before they are re-sent;
    errors never add up.
  - **30 updates a second by default (was 60).** With the above, 30 is as smooth as 60 was, and over an uneven
    connection smoother: every unevenly timed update is a small correction. Settings saved in game are kept.
- **Fixed: Isenoria's Manual Override threw the joiner back into the carrier at once.** When the joiner took the
  Rafflesya, the host's AI took the carrier and switched Rafflesya Control off; the joiner's copy followed into a
  cooldown with no time on it, which reads as "off", and Isenoria ends the hand-over then. Systems on joiners'
  copies are now switched off the proper way, with their real cooldown. A note goes in the joiner's log if a
  hand-over still ends within 5 seconds.
- Fixed: a joiner flying a fighter could send the host a retreat order for it.
- **No more "fighter being built" sparks on joiners' carriers.** A copy of a carrier never builds fighters (the
  host's are shown), and its rebuild time is stretched so it can't; but a bay that lost a fighter still started a
  rebuild, and showed the game's build sparks for the rest of the battle. Such a rebuild is now cleared at once,
  the way the game clears a finished one, without launching anything.
- Fixed: a fighter taken over by a joiner said "Ship is not combat-ready, controls are locked". The game takes a
  wing's readiness from the fleet record it was made from, and fighters on joiners' screens were made from one
  with none. They now get at least 70% (the host's figure for a fighter can be low or nothing, and a wing's
  launch readiness can't be changed once it exists), and a figure of nothing from the host is no longer copied
  onto a fighter. If a fighter in a joiner's hands is still locked, the game is asked to judge again, and the
  reason goes in the joiner's log.
- The log now names the exact build ("Wingman: build 0.6-beta <date>"), since playtest zips of one version differ.
- **Fixed: a Rafflesya taken over with Manual Override was flown home by its carrier's scripts.** UAF's Rafflesya dock
  gives a fighter a docking AI (or none) when it needs rearming or its carrier pulls back, as it would the local
  player's fighter; on the host that took the joiner's controls away for half a second at a time, long enough to be
  recalled. A fighter a joiner flies now gets their controls back the same frame, and if the carrier's script
  took them before the fighter moved (the game moves ships in its own order), the joiner's commands are given to
  the fighter directly at the start of the next frame, so it never drifts ("parked") or flies home by itself.
- **Fixed: a joiner in a Rafflesya could shoot but not move.** When a joiner took a fighter, the host gave their
  carrier to its AI, and Isenoria's AI switches Rafflesya Control off whenever it has no target; the carrier's dock
  script then recalls every Rafflesya and holds it on the landing pad (in single player the player's own fighter
  is exempt as "the player's ship"; a joiner's isn't). Now, while a joiner flies a fighter from their own carrier,
  the host holds the carrier as they left it, systems untouched, slowing to a stop, until they come back to it or
  take another ship.
- **Fixed: drones from a system that makes them without a fighter wing were never sent to joiners** (UAF's
  Rafflesya launches such drones). Joiners only saw drones their own copy of the system made, which stop once
  they fly the ship. The host now sends these drones like any other; a joiner's own unattributed drones are kept
  out of sight, as its other local drones already were.
- Fixed: a Rafflesya a joiner flies didn't fire its bomb on the host. Two causes: its carrier's dock script tells
  it to hold fire while it means to bring it home (the player's own ship ignores that; a joiner's fighter now does
  too), and the bomb is marked for system use, which the game leaves out of the fire order on a ship the local
  player isn't flying. When a joiner fires a group, the host now also fires that group's system-marked weapons.
- Diagnostics for fighters handed to joiners (until they work like in single player): the host logs what is
  happening to such a fighter every 5 seconds (its AI, lock, landing, docking, drones out, system, ammo, whether its
  wing is calling it home); the joiner logs autopilot changes while flying one, and drones the host removes. (Use "Switch back to Isenoria"
  to send it home to rearm, as in single player.)
- **Speed bursts look and feel right** (Super Nakiha's Dimensional Drift and other systems that multiply speed).
  Other ships: while a system runs, a copy is no longer carried on along its (wild) acceleration, and is held close
  to the host's position instead of trailing and catching up; accelerations used between updates are kept to what a
  ship can do. Your own ship: during a burst and 0.4 s after, the gap to the host is left alone unless large, then
  closed smoothly, instead of being pulled on mid-burst.
- **Teleports show on joiners' screens.** Other ships' Phase Skimmers (the Wolf's) and other teleporters are not
  run on a joiner's copy (it can't know where the real ship goes), so the copy just popped to its new place. Their
  look is now drawn: the system's shimmer while it runs, its sound, and a fading image where the ship jumped from.
  The game's own teleporter kinds (PHASE_DISPLACER, PHASE_TELEPORTER) are also recognised by name now.
- **Traffic in the log.** The host and joiners now write one short line every two minutes with what the mod sent
  and received (and, on joiners, how unevenly updates arrived), so playtest logs show real figures.
- For developers: `test/SyncBench.java` measures bandwidth and smoothness on a simulated battle.

## 0.5-beta
Protocol 45: everyone in a game needs 0.5. Includes everything in 0.4.2.

- **Arma Armatura strikecraft land and repair for joiners.** On the host, Arma Armatura treated a joiner's
  strikecraft as an AI ship: when it was damaged or low on ammo the mod took it over, flew it to a carrier and,
  on relaunch, gave it a fresh AI, so the joiner lost control twice. Now a joiner's strikecraft goes to refit
  only when the joiner turns on autopilot (U), as in single player, and is repaired and rearmed on the host as
  usual (hull, armour, CR and ammo follow). The landing and the launch are shown on everyone's screens, and the
  joiner has the controls back as soon as the ship is out.
- **Fixed: a joiner's game crashed at the end of a battle with Arma Armatura ships** (and could whenever an Armaa
  ship was made in battle). The mod asks where the player fleet is, to look for star coronas; the stand-in
  campaign's fleet was nowhere. It is now put in an empty star system of its own (in hyperspace when the host
  fights there), which is taken away with the rest of the stand-in.
- **Fixed: joiners' battles kept refreshing over one ship.** When a mod's script failed while a ship was being
  built on a joiner's PC (the Arma Armatura crash above), the ship was missing there, the battle check saw one
  ship too few and asked the host for the whole battle again every ~27 seconds, five times. A ship that fails to
  build is now counted as one that can't be shown (logged once per hull), like a ship from a mod the joiner
  lacks, so it causes no refreshes.
- **Fixed: missile on-hit scripts run with no target on joiners** (UAF's Chorus nuke error in the log). When the
  host's missile hit something that isn't a ship here, the script was handed nothing; it now gets the missile or
  asteroid at that spot, or is not run.
- **No IP address in the log, again:** the "connecting to" line on the title screen and join screen still wrote
  the host's address (missed in 0.4.1).
- **Controls kept after a mod resets a ship's AI.** If any mod's script gives a joiner's ship a new AI on the
  host, Wingman puts the joiner back in control within half a second (not while the ship is landing, landed,
  retreating or locked by the game).

- **Carriers own their fighters on joiners' screens.** A joiner's copy of a carrier never launches fighters
  itself; the host's fighters were shown as wings of their own, so the copy's hangars looked empty: the
  carrier panel was greyed out, and carrier scripts that ask a carrier for its fighters found none (UAF's
  Rafflesya Control and Isenoria). Each host wing is now put in the right hangar of the copy. The host now
  also says which hangar by its real number (it counted only hangars with fighters out). New setting
  "Fighters in their carriers' hangars" (joiner, on).
- **Fixed: fighters were still not put in their carriers' hangars** (so Isenoria's Manual Override and the
  greyed-out carrier panel were not fixed after all). The game's field name that Wingman sets has accented
  letters, and the build read the source file in a different text encoding, so the name came out wrong and the
  feature switched itself off with a log line. The name is now written in a form no encoding can change, and the
  build refuses sources with such letters.
- **MagicLib subsystems follow the joiner.** MagicLib reads the keyboard only for the local player's ship, so on
  the host a joiner's subsystems were fired by the ship's AI whatever the joiner pressed. Now the joiner's PC
  tells the host when the player switches one on, the host fires it on the real ship, and holds the rest so the
  AI can't fire them while a player flies the ship. New setting "Extra abilities follow the joiner" (both, on).
- **Taking control of a fighter.** A script that hands the player one of their carrier's fighters (Isenoria's
  Manual Override) now works for a joiner: their game asks for that fighter and the host grants it (a fighter
  only from the ship the player flies). On the host such a switch never moves the host's own controls; a
  script handing the host a fighter that isn't from the host's ship is undone.

## 0.4.2-beta
Same protocol as 0.4 (43): 0.4.x versions play together.

- **Fixed: the settings page vanished at the first key press** (Up/Down, or any key). It read a key after
  handing it back to the game, which the game refuses; on the title screen that switched the whole CO-OP button
  off until restart, in battle it closed the page.
- Panels no longer disappear silently: a drawing error is logged and that frame skipped, and a failure on the
  title screen's settings page closes only that page.
- **Big "PAUSED" on joiners' screens** while the host has the battle paused (only the host can unpause).
- The host no longer keeps the last battle's AI for a player's ship after the battle ends.

## 0.4.1-beta
Same protocol as 0.4 (43): 0.4 and 0.4.1 play together.

- **No IP addresses in the log.** The host's own addresses are no longer written to `starsector.log` (they are
  still in the CO-OP panel), connection messages say "the host" instead of the address, and anything else
  logged has addresses (and the host name typed into the join form) replaced with `[address]`. Logs can be
  shared for bug reports without giving away anyone's address.

## 0.4-beta
Protocol 43: everyone in a game needs 0.4.

- **Battle messages follow the host.** Lines mods' scripts write to the combat message feed (the derelict
  crew's hail, a system's warning, a mod's notices) are sent from the host's game, with their colours, ship
  icons and objective names, and a joiner shows those in place of its own scripts' copies. Objectives held back
  on a joiner now speak there too. The game's own lines are made on each PC as before. New setting "Battle
  messages follow the host" (both PCs, on by default).
- The notice Wingman showed when a held-back objective was captured now appears only with that setting off,
  since the objective's own line says so.

## 0.3-beta
Protocol 42: everyone in a game needs 0.3.

- **Held-back objectives show as themselves.** An objective whose script can't run on a joiner's PC (it needs
  the campaign world, or it crashed there before) used to be replaced by a plain Sensor Array, which also ran a
  sensor effect the host never had. It is now made as its own kind, with its real name, icon and look, and a
  script that does nothing; its tooltip shows the host's text. The change to the objective's kind is made on the
  joiner's PC only for the moment the battle starts, and put back straight after.
- **Neutral ships are copied.** Ships a mod parks on the neutral side, like SOTF Extra Combat Objectives'
  derelict fleet waiting to be captured, now appear on joiners' screens from the start, and change sides with
  the host's when captured.
- **Objectives that end themselves end for joiners too.** When the host's game takes an objective off the field
  (the derelict fleet once captured), joiners' copies go as well, with a notice of who captured a held-back one.
  The objective's own combat messages (the derelict crew's hail) are still seen by the host only.

## 0.2-beta
Protocol 41: everyone in a game needs 0.2.

From the mod compatibility review: three kinds of mod that could crash a joiner or leave screens out of step.
- **Stand-in campaign on joiners.** A joiner's battle starts from the title screen, with no campaign loaded,
  so mods' battle scripts that look at the player fleet, its cargo, the player's skills or campaign flags
  found nothing and could crash the game. The host now sends its campaign facts with the battlefield
  (cargo commodities, credits, level, skills, story points, and up to 600 simple campaign flags), and the
  join mission sets up a stand-in player fleet from them before the battle is built. It is taken away when
  the joiner leaves. A setting turns it off ("Stand-in campaign for mod scripts", joiner).
- **Ships belong to fleets on joiners.** Copies of ships now join stand-in fleets, one per side, so scripts
  that ask a ship for its fleet get one instead of crashing.
- **Mod objectives that need the campaign's data now run on joiners.** With the stand-in campaign in place,
  Secrets of the Frontier's reinforcement objectives (the Hyperwave Transmitter crash) and the add-on's
  asteroid and credit-cache objectives are made for real, under the crash safety net: if one still crashes,
  it gets the Sensor Array stand-in from the next start. The derelict-fleet objective, which needs the
  campaign world itself, keeps the stand-in.
- **Side changes are followed.** When a mod switches a ship to the other side mid-battle (boarding,
  hacking), the host tells joiners, and a player flying it loses it with a notice.

## 0.1-beta
First release. Protocol 40: everyone in a game needs the same version.

**Playing together**
- One player hosts any battle (mission, simulator or campaign battle); up to three friends join and each fly a
  ship from the host's fleet. The host's game runs the only real battle; joiners see a live copy.
- CO-OP panel on the title screen: join form, how-to pages (F1), mod compatibility report (F2), settings (F3).
- Auto-join: joiners wait on the title screen and are taken into the host's battle when it starts.
- Ready check with a ship list (Up/Down, F to take a ship), a roster of players, and the last ship you flew
  remembered.
- Player numbers and colours: P1 (host) green, P2 blue, P3 yellow, P4 white, as tags and thin outlines on
  each player's ship; F3 in battle hides and shows them.
- Only the host gives fleet orders, pauses and changes battle speed. The host can remove a player
  (Ctrl+Shift+number) or take a ship back (Ctrl+Shift+T), and can limit the ship size friends may fly.
- Disconnects pause the battle and hand the ship to the AI; the joiner's game reconnects by itself.
- Mod list check: joiners must run the same content mods as the host, and are told exactly what differs.

**Keeping the games alike**
- Ships, fighters, missiles, wrecks, asteroids, nebula, objectives and fleet orders are copied from the host.
- Loadouts, hullmods, officers and their skills, and the fleet commander's fleet-wide skills apply on every
  copy; 43 key stats are kept equal to the host's.
- Mod content runs on every PC: weapon, missile, ship system and hullmod scripts, with hits that have on-hit
  effects matched to the host's. Battle objectives from mods (such as Secrets of the Frontier's) are made on
  joiners too; those whose scripts need a running campaign get a stand-in at the same spot. Ships that a
  mod's script brings into the battle are matched to the host's.
- Your own ship answers your controls at once and is kept in step with the host's.

**Built for real connections**
- Updates carry only what changed, compressed; position updates and controls go by UDP where it gets
  through, with TCP taking over by itself where it doesn't.
- The host's work is done on demand, and an idle host pays almost nothing.

**Safety nets**
- Each part fails on its own: an error switches off only that feature for the battle, never the game.
- Mod ship systems and objectives that crashed a joiner's game are not mirrored again on that PC.
- Guard rails against hostile connections: connection caps per PC, size and rate limits, password lockout
  after 3 wrong tries, cleaned player names, and impossible numbers refused.
