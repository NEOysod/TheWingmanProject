# Changelog

## 0.5-beta
Protocol 44: everyone in a game needs 0.5. Includes everything in 0.4.2.

- **Carriers own their fighters on joiners' screens.** A joiner's copy of a carrier never launches fighters
  itself; the host's fighters were shown as wings of their own, so the copy's hangars looked empty: the
  carrier panel was greyed out, and carrier scripts that ask a carrier for its fighters found none (UAF's
  Rafflesya Control and Isenoria). Each host wing is now put in the right hangar of the copy. The host now
  also says which hangar by its real number (it counted only hangars with fighters out). New setting
  "Fighters in their carriers' hangars" (joiner, on).
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
