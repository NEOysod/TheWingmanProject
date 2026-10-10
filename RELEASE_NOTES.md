# The Wingman Project 0.1-beta — Release Notes

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
- Starting the battle from the CO-OP panel relies on game internals of 0.98a-RC8. If a game update breaks it,
  the mission **Wingman: Join Game** still works.

---

## ⚠ Safety and responsibility

**Playing with this mod carries similar risks to hosting a private Minecraft server.** Please read this before
you play.

- **Hosting opens a network port.** While you host, anyone who can reach your PC on port 24816 can try to
  join. Without a password, they get in.
- **The connection is not encrypted, and players are not verified.** A name is just text. The join password
  crosses the network as plain text, so anyone on the same network could see it. Playing over a private
  virtual LAN (Radmin VPN, Tailscale, ZeroTier) is much safer than forwarding a port on your router.
- **Your addresses are shared.** Joiners know the host's address and the host sees theirs. Log files
  (`starsector.log`) contain addresses and player names: check them before posting them anywhere public.
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
