# Changelog

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
