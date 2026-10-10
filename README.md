# The Wingman Project 0.4-beta

Co-op multiplayer for Starsector battles. One player hosts a battle (a mission, the simulator, or a campaign
fight) and up to three friends join it, each flying a ship from the host's fleet. Combat only: the campaign
itself stays single-player.

For Starsector **0.98a-RC8**. Host and joiners must run the same game build.

**This is a beta.** It has been played mostly by two people. Expect rough edges; reports are welcome
(see "If something goes wrong"). The first time you start the game with the mod, a short how-to opens on
the title screen. It is always available again: open the CO-OP panel and press F1.

**The full player's guide is `docs/USER_GUIDE.md`**: hosting, joining, keys, every setting, firewall and
troubleshooting.

## Install
1. Unzip so the folder is `Starsector/mods/Wingman`.
2. Enable **The Wingman Project** in the launcher.
3. Every player needs the same mods, in the same versions, as the host. Interface-only mods marked as
   "utility" may differ. A joiner whose mods differ is refused and shown the list of differences.

## Hosting
Nothing to set up. Every battle you start is open to join.

- Friends need to reach your PC on port **24816** (TCP; UDP on the same number is used too where it gets
  through, and is optional): the same LAN, a virtual LAN (Radmin VPN, Tailscale,
  ZeroTier, Hamachi), or a port forward on your router.
- Your addresses to read out are in the CO-OP panel on the title screen. They are not shown during a
  battle unless you switch on "Show my addresses in battle", so they don't end up on a stream.
- After you deploy, the battle holds on a **ready check** until everyone has loaded and pressed ready. Press
  Enter to start (Ctrl+Enter to start without waiting).
- Fleet orders are yours alone. Joiners see them but cannot give any.
- If a player drops, the battle pauses with a notice and their ship goes back to the AI.
- **Password.** Without one, anyone who can reach your PC on the port can join. Set one in the settings
  (F3 in the CO-OP panel) if you host on a shared virtual LAN.
- Players are numbered: the host is **P1** (green), joiners **P2** (blue), **P3** (yellow), **P4** (white),
  shown next to the ship each one flies (**F3** in a battle hides and shows them). **Ctrl+Shift+2**
  removes P2, and so on. They cannot rejoin until you
  restart the game.
- **Ctrl+Shift+T** takes back the ship you have targeted from the player flying it. "Largest ship a joiner
  may fly" in the settings limits how big a ship joiners may take at all.
- In a campaign battle, a ship lost while a friend flies it is lost for real.
- Your name as host is the one in the CO-OP panel: clear the address box, type the name, press Enter.

## Joining
1. On the title screen, click **CO-OP** in the top right corner.
2. Type the host's address and your name (and the password, if the host set one), press **Enter**. It
   connects straight away, so a wrong address, password or a mod mismatch shows here. Ctrl+V pastes.
   Everything typed is remembered for next time.
3. Stay on the title screen. When the host's battle begins you are taken into it after a three-second
   countdown (Esc skips that battle; Enter joins at once). With "Join the host's battles by myself" off
   in the settings, press **Enter** yourself instead.
4. Deploy the single stand-in ship.
5. **Choose your ship in the ready check:** Up/Down moves through the host's ships, F takes one. If
   you flew one of them last time, you are already seated in it. Then press Enter for ready.
6. During the battle, target a friendly ship and use the game's **Transfer Command** (see Settings >
   Controls) to change ship.

**Leaving with your ship.** Either give your own ship the game's Retreat order and fly it out, or fly to
the bottom edge of the map and press Enter when the RETREAT prompt appears; the ship then leaves under its
own power and you go back to watching.

**When the battle ends** you are taken out of it automatically and stay connected. Stay on the title
screen and you go into the host's next battle the same way.

If the second Enter cannot start the battle (this can happen after a game update), open Missions and start
**Wingman: Join Game** instead; it does the same thing.

## Settings
In the game: **F3** in the CO-OP panel on the title screen, or **Ctrl+Shift+O** during a battle. Up/Down
picks a setting, Left/Right changes it, Delete puts it back to its default, Tab turns the page. Each setting
says whether it matters to the host, the joiner or both, and (in a battle) whether it takes effect at once
or from the next battle. Changes are remembered.

`wingman_settings.json` in the mod folder holds the defaults, with a comment on each; it does not need editing.

## What is shared
The host's game is the only real battle; everyone else sees a live copy of it.

- Ships, fighters and missiles: position, hull, armor, flux, shields, phase cloak, ship systems, combat
  readiness, weapon aim and fire, ammo, EMP damage.
- The battlefield: map size, backdrop, nebula, asteroids, objectives, the host's fleet orders.
- Wrecks: where each one drifts, when it breaks up and when it is gone. (The line a wreck breaks along is
  each PC's own; the pieces and their paths are the host's.)
- Loadouts, hullmods, officers and skills.
- Battle speed (only the host can change it) and pause.
- Mod content: weapon, missile, system and hullmod scripts from mods run on every player's copy.
- Missile hits, and hits by guns that have an on-hit effect: whether one hits, what it hits and exactly
  where is the host's call. Joiners see the hit, and any effect a mod attaches to it, at the same spot and
  moment. Ordinary bullets and beams are judged on each PC.
- **Mod compatibility report:** F2 in the CO-OP panel lists what scripted content your mods bring and how
  each kind is handled, and what has been seen of it in play. "Worth a look" marks items that keep needing
  help; those are the ones to mention in a report.

## Safety nets
- A link that goes silent for 12 seconds is treated as lost on both sides, with a clear notice.
- A joiner whose connection drops tries to get back in by itself, every few seconds for about half a
  minute. If it works, the battle is loaded again and you are put back in your ship if it is still free.
- Ship stats are kept equal to the host's on every copy of a ship, the simple values mods' scripts keep on
  ships are copied from the host, weapons fire shot for shot with the host's, and damage the joiner's own
  game did not produce is still shown. Each has a switch in `wingman_settings.json` (`syncStats`,
  `syncScriptMemory`, `shotCounts`, `damageReports`).
- Every few seconds the host says how many ships, fighters and wrecks there should be. A joiner whose copy
  has drifted refreshes itself and sits you back in your ship.
- Enemies that nobody on your side can see (in the fog of war) are reported to joiners only about once a
  second, and in full again the moment they come into view. One that is firing or being hit is always
  reported. `trimUnseen` in the settings turns this off.
- If the ready check has been waiting on someone for a minute, the host is reminded that Ctrl+Enter starts
  without them.
- If a mod's ship system appears to have crashed a joiner's game, that system is no longer mirrored on
  that PC from the next launch on. The CO-OP panel lists any systems switched off this way; **F5** there
  switches them all back on.
- When the link backs up, the host sends updates less often for a while instead of dropping them.
- Limits on message size and rate, and on how many fighters and missiles a joiner will build.
- If the co-op part itself hits an error, it stops cleanly: on the host, joiners are released and told; on a
  joiner, a notice offers to leave and rejoin. The game keeps running either way.

## Known limits
- Built and tested mostly with two players over a virtual LAN. Four is the design target.
- A joiner sees their own controls respond at once; everything else arrives with the link's delay. Effects
  driven by mod scripts can be a few frames early or late compared with the host.
- Drones launched by a ship system are shown as the host's, like fighters.
- The CO-OP button is also drawn over the Missions and Settings screens.
- Starting the battle from the CO-OP panel relies on game internals of 0.98a-RC8. Everything else uses the
  modding API only.

## If something goes wrong
- **"Connection lost"**: your game is already trying to reconnect; wait, or press Enter to leave the
  battle. The host's game pauses when a player drops and the host unpauses it as usual.
- **Connection refused / could not reach**: check the address, that the host is in the game, and that port
  24816 is allowed through the host's firewall.
- **"Battlefield differs"**: you joined before the host's battle had begun. Press Enter on the notice to
  hop out and straight back in on the right map.
- **"Different game version" / "Wrong password"**: as it says; fix it and connect again.
- **Anything else**: turn on "Detailed log for bug reports" in the settings (Troubleshooting page) on both PCs, reproduce it, and send both
  `starsector.log` files (in the `starsector-core` folder).

The mod is written to fail soft: if a part of it hits an error, that part switches itself off for the
battle with a `Co-op:` message rather than stopping the game.

## For modders
`docs/TECHNICAL.md` describes how each part works. Source is in `src/`; `build.bat` / `build.sh` rebuild the
jar against your copy of the game.

## Safety
Playing with this mod carries similar risks to hosting a private Minecraft server: hosting opens a network
port, the connection is not encrypted, players are not verified, and in a campaign battle ships flown by
friends are lost for real if they die. **Only play with people you know and trust**, set a password, and
prefer a private virtual LAN (Radmin VPN, Tailscale, ZeroTier) over forwarding a port. The mod is provided
as is, without warranty; the author is not responsible for any loss or mishap arising from its use. See
`RELEASE_NOTES.md` for the full warning.

## Licence
GNU General Public License, version 3 or later; see `LICENSE` and `NOTICE`. You may use, study, change and
share the mod. If you share a changed version, or a mod that contains this mod's code, you must share its
source under the same licence. An additional permission (in `NOTICE`) lets it run alongside Starsector and
other mods, which keep their own licences.
