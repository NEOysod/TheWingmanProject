# The Wingman Project 0.8-beta

**Co-op multiplayer battles for Starsector.** One player hosts a battle; up to three friends join it and each
fly a ship from the host's fleet, side by side, in real time.

> **Beta.** Played mostly by two people so far. It works, but expect rough edges, and please report what you
> find (see below).

`RELEASE_NOTES.md` has the full description, how it works, which kinds of mods can cause trouble, and the safety warning. Please read the safety
part before you host.

## What you need

- Starsector **0.98a-RC8**, the same build for everyone.
- The **same mods, in the same versions**, as the host. Interface-only "utility" mods may differ. If yours
  differ, you're told exactly what to fix.
- A way to reach the host's PC: the same home network, a virtual LAN (Radmin VPN, Tailscale, ZeroTier), or
  port **24816** forwarded on the host's router.

## Install

1. Unzip so the folder is `Starsector/mods/Wingman`.
2. Enable **The Wingman Project** in the launcher.
3. Start the game. A short how-to opens on the title screen the first time. It's always in the **CO-OP**
   panel (top right of the title screen) under **F1**.

**Optional: Wingman's agent (on each PC that wants it; the host's matters most).** Lets mods' "player ship"
scripts (UAF's fighter take-over, abilities aimed at the cursor, and the like) work for every player's ship on
every PC, not only for your own. With the game closed, find `-Xms` in the
game's launch settings and put this just before it, followed by a space:
- Windows, in `vmparams` (Starsector folder): `-javaagent:..\mods\Wingman\jars\WingmanAgent.jar`
- Linux, in `starsector.sh`: `-javaagent:./mods/Wingman/jars/WingmanAgent.jar`
- Mac: `-javaagent:` and the full path to `mods/Wingman/jars/WingmanAgent.jar`
- A custom Java launcher (a `.bat` running `java.exe @File.txt`): on a line of its own at the top of that `.txt`:
  `-javaagent:../mods/Wingman/jars/WingmanAgent.jar`

"Wingman agent: running" in `starsector.log` means it works. If the game won't start with it,
delete it again and tell us.

## Quick start

**Host**
1. Open the CO-OP panel and give friends one of the addresses shown there (for Radmin, the one starting
   with 26).
2. Set a password: **F3** in the CO-OP panel, then "Join password".
3. Load your save: friends can connect now, see your fleet and pick their ships (your message feed says who
   picked what).
4. Start any battle. Everyone chooses a ship, then a ready check waits for everyone. **Enter** starts it;
   **Ctrl+Enter** starts without waiting.

**Join**
1. Click **CO-OP**, type the host's address, your name and the password, and press **Enter**.
2. In the LOBBY (the host's fleet): **Up/Down** and **F** pick the ship you want. **F4** opens it again.
3. Stay on the title screen. When the host's battle begins, you're taken into it.
4. Step 1: your picked ship is yours if the host deployed it; otherwise **Up/Down**, **Enter** takes a ship
   (**W**: watch without one). Step 2: **Enter** when you're ready (**Backspace**: change ship).
5. After the battle you're back on the title screen, still connected, ready for the next one.

## Keys

| Who | Key | What it does |
|---|---|---|
| Anyone, title screen | **CO-OP**, then F1 / F2 / F3 | How-to / mod check / settings |
| Anyone, battle | **Ctrl+Shift+O** | Settings |
| Anyone, battle | **F3** | Hide / show player tags (P1 green, P2 blue, P3 yellow, P4 white) |
| Anyone, battle | **U** | Hand your ship to the AI and back |
| Joiner, lobby (title screen) | Up / Down, **F**, Delete, F4 | Choose, pick, drop your pick; reopen the lobby |
| Joiner, before the battle | Up / Down, **Enter**, W, then Enter / Backspace | Choose and take a ship (W: watch), then ready / change ship |
| Host, battle | Ctrl+Shift+**2–9** | Remove that player |
| Host, battle | Ctrl+Shift+T | Take back the ship you've targeted |

Change ship during a battle with the game's **Transfer Command** on a friendly ship. Every setting has a short
explanation on the settings page.

## If something goes wrong

- **Connection lost:** your game reconnects by itself. Wait a few seconds.
- **Can't connect:** check the address, that the host is in the game, and that port 24816 is allowed
  through the host's firewall.
- **"Wrong password" / "Too many wrong passwords":** check the password with the host. After 3 wrong tries,
  wait 30 seconds.
- **Anything else:** turn on "Detailed log for bug reports" in the settings (Troubleshooting page) on both
  PCs, make it happen again, and send both `starsector.log` files (in the `starsector-core` folder). Wingman
  keeps IP addresses out of the log; player names are in it.

## Safety

Like hosting a private Minecraft server: hosting opens a network port, and the connection isn't encrypted.
**Only play with people you know and trust**, set a password, and prefer a private virtual LAN over port
forwarding. Provided as is, without warranty; the author is not responsible for any loss or mishap. Full
warning in `RELEASE_NOTES.md`.

## Licence and source

Free software under the GNU General Public License, version 3 or later (`LICENSE`, `NOTICE`). Source code:
https://github.com/NEOysod/TheWingmanProject
