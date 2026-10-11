# Changelog

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
  And a fighter flown by the AI may have no weapon groups set up at all (the game makes them for a ship a player
  flies): a group the joiner fires that the ship doesn't have is now fired from its loadout, weapon by weapon.
  Each press of the fire button now counts for weapons with ammo (bombs, missiles): the host keeps telling each
  one in the pressed group to fire until it has (up to 0.6 s, 1.5 s while charging up), so a quick click is
  enough. On a fighter, a weapon that still won't fire is fired by hand: its shot is launched from the weapon,
  using its ammo and cooldown. The host's fighter report shows the presses it saw and what each asked for.
- A joiner flying a fighter from their own carrier (UAF's Rafflesya from the Isenoria) is put back in the carrier
  when the fighter is done: lost, landing, retreating or gone from the battle. Before, they were left with no ship
  and had to use Transfer Command. The host hands the carrier back to them and their game sits them down in it.
- Weapon groups on a joiner's ship are now fired the way the game fires the player's own (worked out from the
  game's code; see TECHNICAL.md, GroupFire). Holding fire on an alternating group takes turns again: the game
  moves an alternating group on to its next weapon while the button is held only for the local player's ship,
  so on the host only the first weapon fired (the Isenoria's main guns); the host now does that rotation with
  the game's own timing. Linked groups fire the weapons whose arc covers the cursor, as in the game, also when
  the host has to fire them directly. Presses that fire ammo weapons follow only those weapons, so an
  alternating missile group no longer fires every launcher at once on a press. The 0.35 s watchdog lasts one
  hold instead of the rest of the battle. The "system weapons" extra fire is gone (the game doesn't treat them
  differently in a group).
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
