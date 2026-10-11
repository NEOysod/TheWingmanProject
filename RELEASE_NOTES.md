# The Wingman Project 0.6.4-beta — Release Notes

**Co-operative multiplayer battles for Starsector.** One player hosts a battle; up to three friends join it and
each fly a ship from the host's fleet, side by side, in real time.

> **Beta.** This mod has been played mostly by two people. It works, but expect rough edges, and please report
> what you find (see [Reporting problems](#reporting-problems)).

---

## What it is

- **Combat only.** Missions, the simulator and campaign battles can all be shared. The campaign itself stays
  single-player: the fleet, the cargo and everything won or lost belong to the host.
- **Up to 4 players**, the host included. More are allowed but not recommended.
- **Joiners fly the host's ships.** A joiner arrives with no ship of their own, then takes the helm of any
  friendly ship the host allows.
- **The host is in charge.** Only the host gives fleet orders, pauses, and changes the battle speed.

## Requirements

- Starsector **0.98a-RC8**. The host and every joiner must run the same game build.
- The **same mods, in the same versions**, as the host. Interface-only mods marked "utility" may differ. A
  joiner whose mods differ is refused and shown exactly what differs.
- A way for friends to reach the host's PC: the same home network, a virtual LAN (Radmin VPN, Tailscale,
  ZeroTier, Hamachi), or a port forward. The port is **24816** (TCP; UDP on the same number is used too where
  it gets through, and is optional).

## Install

1. Unzip so the folder is `Starsector/mods/Wingman`.
2. Enable **The Wingman Project** in the launcher.
3. Start the game. A short how-to opens on the title screen the first time; it is always in the **CO-OP**
   panel (top right of the title screen) under **F1**.

---

## How it works

The host's game runs the only real battle. Every joiner sees a live copy of it, kept in step many times a
second over the network:

- **Ships, fighters and missiles**: where they are, hull, armor, flux, shields, phase, ship systems, weapon
  aim and fire, ammo, EMP damage.
- **The battlefield**: map, backdrop, nebula, asteroids, objectives, wrecks, and the host's fleet orders.
- **Loadouts, officers and skills**: every ship has the same officer and skills as on the host, and the fleet
  commander's fleet-wide skills apply on every copy. Speed, turn rate, ranges, rates of fire and other key
  stats are also kept equal to the host's directly.
- **Mod content**: weapon, missile, ship system and hullmod scripts from your mods run on every player's
  copy, on the host's timing. Hits with special on-hit effects are matched to the host's. Battle objectives
  added by mods (such as Secrets of the Frontier's emplacements and reinforcement beacons) appear for
  everyone, and ships their scripts bring into the battle are shown as the host's.

**Your own ship answers your controls at once**, like single player, and is quietly kept in step with the
real ship on the host. Everything else arrives with the connection's delay.

**Built for real connections.** Updates carry only what changed, compressed. Position updates and controls go
by UDP where possible, so a lost packet costs one update instead of a freeze; TCP takes over by itself
where UDP is blocked.

**It fails soft.** If one part of the mod hits an error, that part switches itself off for the battle with
a `Co-op:` message, and the game keeps running.

---

## How to play

### Hosting

1. Open the CO-OP panel on the title screen and give friends one of the addresses listed there (for Radmin,
   the one starting with 26).
2. **Set a password** (F3 in the CO-OP panel, "Join password") if anyone else can reach your PC.
3. Start any battle. After you deploy, a **ready check** holds the battle until everyone has loaded and
   pressed ready. **Enter** starts it; **Ctrl+Enter** starts without waiting.

### Joining

1. Click **CO-OP** on the title screen, type the host's address, your name and the password (if any), and
   press **Enter**. You stay connected for the whole session.
2. **Stay on the title screen.** When the host's battle begins you are taken into it after a short countdown.
3. **Choose your ship in the ready check**: Up/Down to pick, **F** to take it. If you flew one of these ships
   last time, you are already in it. Then press **Enter** for ready.
4. When the battle ends you return to the title screen, still connected, and join the next one the same way.

### In the battle

- Every player has a number and colour: the host is **P1** (green), then **P2** (blue), **P3** (yellow),
  **P4** (white), shown next to each player's ship. **F3** hides and shows the tags.
- Change ship with the game's **Transfer Command** on a friendly ship. **U** hands your ship to the AI and back.
- Leave with your ship by giving it the Retreat order, or at the bottom edge of the map with Enter.
- If a player drops, the battle pauses and their ship goes back to the AI. Their game reconnects by itself.

### Keys

| Who | Key | What it does |
|---|---|---|
| Anyone, title screen | Click **CO-OP**, then F1 / F2 / F3 | How-to / mod compatibility report / settings |
| Anyone, battle | **Ctrl+Shift+O** | Settings |
| Anyone, battle | **F3** | Hide / show player tags |
| Joiner, ready check | Up / Down, **F**, Enter | Choose a ship, take it, ready |
| Host, battle | Enter / Ctrl+Enter | Start after the ready check / start without waiting |
| Host, battle | Ctrl+Shift+**2–9** | Remove that player (by their P number) |
| Host, battle | Ctrl+Shift+T | Take back the ship you have targeted |

### Settings

Everything can be changed in game: **F3** in the CO-OP panel, or **Ctrl+Shift+O** in a battle. Nothing
needs editing by hand, and every setting has a short explanation on the settings page.

---

## Known limits

- Mostly tested with two players, much of it on one PC. Four players over the internet is the design
  target, and the least tested.
- With two copies of the game on one PC, the one in the background slows right down. Joining can then be
  slow. Two PCs don't have this problem.
- Effects drawn directly by mod scripts (particles, sounds, arcs) can be a few frames early or late compared
  with the host.
- Fleet-wide skills whose strength depends on fleet size come out at full strength on a joiner's copy. Speed,
  range, flux and the other kept-equal stats are corrected to the host's either way.
- Wrecks break up at the same moment on every screen, but the break lines are the game's own random pick on
  each PC, so the pieces can be shaped a little differently. Where pieces are and which exist follow the host.
- Phase cloaks and similar systems slow the whole battle down ("bullet time") for the ship the local player
  flies. A battle has one clock, the host's: joiners don't get that slowdown when they phase, and when the host
  phases the battle slows for everyone.
- Starting the battle from the CO-OP panel relies on game internals of 0.98a-RC8. If a game update breaks it,
  the mission **Wingman: Join Game** still works.

---

## Mod compatibility

Most mods just work: ships, weapons, hullmods, ship systems and missiles from mods are copied like vanilla
ones, and their scripts run on every player's copy, on the host's timing. What causes trouble is not how big
a mod is but what its combat scripts assume. A joiner's battle is a copy started from the title screen, with
no campaign loaded, in which every ship except your own is a puppet the host moves.

### How Wingman handles mod content

- **F2** in the CO-OP panel shows the mod compatibility report: what scripted content your mods bring, how
  each kind is handled, and what has been seen of it in play. "Worth a look" marks items that keep needing
  help. Those are the ones to mention in a report.
- Effects a mod draws directly (particles, sounds, arcs) follow the script that makes them. They can be a
  few frames early or late compared with the host, and occasionally differ.
- If a mod's **ship system** or **battle objective** appears to have crashed a joiner's game, it is no longer
  mirrored on that PC from the next launch on. Ships still move and take damage correctly; only its effects
  are missing (an objective still shows with its own name and look, but does nothing on that PC). The CO-OP panel lists what was
  switched off this way, and **F5** switches it all back on.
- Battle objectives whose scripts need the campaign's data (Secrets of the Frontier's reinforcement
  beacons; the asteroid and credit-cache objectives from its Extra Combat Objectives add-on) run on a joiner's
  PC using the stand-in campaign (see below). Those that need the campaign world itself (the add-on's
  derelict-fleet objective), or any that crash anyway, are held back: on a joiner's screen the objective has
  its real name, look and tooltip (the host's text), but its script runs only in the host's game. What it
  does reaches joiners the usual way: ships it brings in or parks (the derelict fleet, which sits on the
  neutral side until captured) are copied from the host, and when the host's game takes the objective off
  the field, so does the joiner's, and its messages (such as the derelict crew's hail) come from the host.
- **Battle messages** that mods' scripts write to the message feed (an objective's hail, a system's warning)
  are sent from the host's game, and a joiner shows those instead of its own scripts' copies, so every screen
  reads the same. The game's own lines (ship destroyed, objective taken) are made on each PC as before. A
  setting turns it off ("Battle messages follow the host", needed on both).
- **Carriers own their fighters on every screen.** The host's fighters are put in the hangars of the joiner's
  copy of their carrier, so the carrier panel shows them and carrier scripts (UAF's Rafflesya Control and
  Isenoria) find them. Setting: "Fighters in their carriers' hangars" (joiner).
- **MagicLib subsystems** (extra abilities on their own keys, used by UAF and many other mods) follow the
  joiner: pressing the key on a joiner's PC fires the ability on the host's real ship, and the ship's AI no
  longer fires it by itself while a player flies it. Setting: "Extra abilities follow the joiner" (both).
- **Scripts that hand the player another ship** (UAF's Isenoria Manual Override, which passes control to one
  of its fighters) work for joiners: the joiner is given that fighter, and the host stays in their own ship.
- **Arma Armatura strikecraft** land on a carrier for repairs and ammo when the joiner turns on autopilot (U),
  as in single player, and not by themselves while the joiner flies. Everyone sees the landing and the launch,
  and the joiner has the controls back once the ship is out. If any other mod's script takes the controls of a
  joiner's ship on the host, they are given back within half a second.
- Ships a mod's script brings into the battle (turrets, reinforcements, summons) are matched to the host's;
  any extra one the joiner's own game makes is switched off at once and removed after 3 seconds.

### What kind of mod can cause problems

| Risk | Kind of mod | What can happen | Covered? |
| --- | --- | --- | --- |
| **Was a crash, now covered** | Combat scripts that read the campaign: the player fleet, its cargo or credits, the player's skills or story points, campaign flags. Common in battle objectives, "rewards on kill" and special encounters. | A joiner has no campaign loaded. It now gets a stand-in copied from the host's (cargo, credits, level, skills, story points, simple flags), so these scripts find something. | Mostly. Not copied: the player character itself (if the game has none on the title screen), faction standings, markets, and flags holding anything other than yes/no, numbers or short text. Ship systems and objectives that still crash are switched off on that PC after one crash. |
| **Was a crash, now covered** | Hullmods or skills that read a ship's fleet or fleet commander during battle (`member.getFleetData()`). | A joiner's copies now belong to stand-in fleets, one per side; the players' side is led by a copy of the host's character. | Yes. Fleet-size bonuses a script works out from that fleet may differ a little from the host's; the key stats are kept equal anyway. |
| **Crash (joiner)** | Combat scripts that assume the player's ship is a real, armed ship. | A joiner without a ship sits in an invisible stand-in with no weapons; a script that reads its weapons or officer may fail. | Partly: the stand-in is a normal small ship, so most scripts are fine. |
| **Covered** | Carrier scripts that look at the carrier's own fighters (`getAllWings()`), and carrier panels. | Used to find no fighters on a joiner's copy (greyed carrier panel, effects missing). | Yes: the host's fighters sit in the copy's hangars. |
| **Covered** | Mods that put their own AI on a ship (Arma Armatura's carrier refit, scripted manoeuvres). | Used to take a joiner's ship away from them on the host: docked it for repairs and gave it a new AI. | Yes: a strikecraft refits only on the joiner's autopilot, and the joiner's controls are put back after a mod resets the AI. |
| **Covered** | MagicLib subsystems on a joiner's ship. | Used to be fired by the ship's AI on the host whatever the joiner pressed. | Yes: they follow the joiner's key. |
| **Covered** | Mods that switch a ship to the other side mid-battle (boarding, hacking, mind control). | The host notices and tells joiners; a player flying that ship loses it and is told. | Yes. |
| **Out of step** | Mods that create ship types or variants at runtime from the campaign (procedural hulls, generated skins). | The joiner's game doesn't have that ship type, so the ship is invisible there (it still counts for the ready check). | Reported in the log; the ship is skipped. |
| **Out of step** | Combat scripts that pick at random (random variant, random proc, random target). | Each PC picks differently, so visuals can differ. Damage, positions and deaths are always the host's. | Damage and deaths: yes. Visuals: no. |
| **Out of step** | Mods that keep setting their own AI on every ship during battle. | On a joiner it would override the do-nothing AI that puppets use, so ships twitch and fire on their own between updates. | No. Mods that choose an AI when a ship is created are fine. |
| **Out of step** | Mods that behave differently in missions (`isMission()`). | A joiner's battle is a mission, so such a mod may run its "mission" rules there. | No. |
| **Can't join** | Different content mods or versions. | The joiner is refused and told exactly what differs. | Yes (mod list check). |
| **Can't join** | Mods that replace or hide the title screen battle. | The CO-OP panel may not appear. | Use the **Wingman: Join Game** mission instead. |
| **Heavy** | Mods that fill the field with projectiles or fighters (very large salvos, swarms). | Big updates and a busy joiner. A joiner shows at most 2,000 missiles and 800 fighters. | Capped. |
| **Fine** | Interface-only mods: HUDs, combat chatter, camera, sound, graphics (GraphicsLib, shaders). | Each player's runs on its own; nothing to keep in step. Marked "utility", they may even differ between players. | n/a |
| **Fine** | Ship, weapon and faction packs; AI mods that choose an AI when a ship is created; campaign-only mods. | Copied like vanilla content, or never touch the battle. | n/a |

### If a mod gives trouble

1. Note the mod, the ship, weapon, system or objective, and what happened.
2. Turn on "Detailed log for bug reports" (Troubleshooting page) on both PCs, make it happen again, and send
   both `starsector.log` files. A crash leaves the culprit's name near the end of the joiner's log.
3. Meanwhile:
    - **A ship system:** add its id to `neverMirrorSystems` in `wingman_settings.json` so it is never
      mirrored on joiners.
    - **Something you can't place:** play without that mod for co-op sessions.
    - **A "utility" mod that does change battles:** set "Mod list check" (host's settings, "Hosting and joining" page) to "all" so it must match too.

---

## ⚠ Safety and responsibility

**Playing with this mod carries similar risks to hosting a private Minecraft server.** Please read this before
you play.

- **Hosting opens a network port.** While you host, anyone who can reach your PC on port 24816 can try to
  join. Without a password, they get in.
- **The connection is not encrypted, and players are not verified.** A name is just text. The join password
  crosses the network as plain text, so anyone on the same network could see it. Playing over a private
  virtual LAN (Radmin VPN, Tailscale, ZeroTier) is much safer than forwarding a port on your router.
- **Your addresses are shared.** Joiners know the host's address and the host's PC sees theirs. Wingman keeps
  IP addresses out of `starsector.log` (player names are in it), but other mods may not: glance over a log
  before posting it anywhere public.
- **In a campaign battle, ships flown by friends are at stake.** A ship a joiner loses, by accident or on
  purpose, is lost for real.
- **Built-in guard rails** (tested with a hostile fake player): nothing received over the network is
  ever run as code; message sizes and rates are limited; connection floods are capped per PC; after 3 wrong
  passwords a PC must wait 30 seconds between tries; broken or impossible data is refused. These limit
  damage, they don't make it safe to play with strangers.
- **The mod has not been independently security-audited.**

**Only play with people you know and trust.** Set a password, prefer a private virtual LAN, and close the game
or switch hosting off ("Friends can join my battles") when you are done.

**Disclaimer.** This mod is provided "as is", without warranty of any kind, as stated in its licence. The
author is not responsible for any loss, damage or other mishap arising from its use, including lost ships or
save games, game crashes, or anything that happens because of who you choose to play with or how you
connect. You use it at your own risk.

---

## Reporting problems

1. Turn on **"Detailed log for bug reports"** in the settings (Troubleshooting page) on **both** PCs. It is off
   by default in this release.
2. Reproduce the problem.
3. Send both `starsector.log` files (in the `starsector-core` folder), and say what you saw, on which ship or
   weapon, and roughly when. **F2** in the CO-OP panel shows the mod compatibility report, which is useful
   to include too.

## Licence

Source code: https://github.com/NEOysod/TheWingmanProject

The Wingman Project is free software under the **GNU General Public License, version 3 or later** (see `LICENSE` and
`NOTICE`). You may use, study, change and share it. If you share a changed version, or a mod that contains
this mod's code, you must share its source under the same licence. An additional permission allows it to
run alongside Starsector and other mods, which keep their own licences. Starsector itself and other mods are
not part of this mod and remain under their own terms.

## Credits

The Wingman Project by **NEOysod**. Thanks to Fractal Softworks for Starsector and its modding API, and to everyone
who tests and reports problems.
