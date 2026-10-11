# The Wingman Project 0.5 — User Guide

Co-op for Starsector battles. One player hosts a battle and up to three friends join it, each flying a ship
from the host's fleet. Only the battle is shared: the campaign, the fleet and everything won or lost belong
to the host.

For Starsector **0.98a-RC8**. This is a **beta**: expect rough edges, and see
[Reporting a problem](#reporting-a-problem) when you hit one.

**Contents**
1. [Before you play](#1-before-you-play)
2. [Hosting](#2-hosting)
3. [Joining](#3-joining)
4. [In the battle](#4-in-the-battle)
5. [Keys](#5-keys)
6. [Settings](#6-settings)
7. [Connection and firewall](#7-connection-and-firewall)
8. [If the game feels laggy](#8-if-the-game-feels-laggy)
9. [Playing with other mods](#9-playing-with-other-mods)
10. [Troubleshooting](#10-troubleshooting)
11. [Known limits](#11-known-limits)

---

## 1. Before you play

**Install**
1. Unzip so the folder is `Starsector/mods/Wingman`.
2. Enable **The Wingman Project** in the launcher.

**Everyone needs the same things**
- The same Starsector build (0.98a-RC8).
- The same version of this mod as the host.
- The same mods in the same versions as the host. Interface-only mods marked "utility" may differ. A joiner
  whose mods differ is refused and shown the list of differences.

**The first time** you start the game with the mod, a short how-to opens on the title screen. It is always
there again: open the **CO-OP** panel (top right of the title screen) and press **F1**.

---

## 2. Hosting

There is nothing to set up. Every battle you start (a mission, the simulator, a campaign fight) can be joined,
and in a campaign friends can connect as soon as you load your save, while you are still on the map.

1. **Let friends reach you.** They need to reach your PC on port **24816**. Pick one:
   - the same home network,
   - a virtual LAN such as Radmin VPN, Tailscale, ZeroTier or Hamachi,
   - a port forward on your router (TCP and UDP 24816).
2. **Give them your address.** Open the CO-OP panel on the title screen; your addresses are listed there.
   For Radmin, it is the one starting with 26. They are not shown during a battle unless you switch on
   "Show my addresses in battle", so they don't end up on a stream or a screenshot.
3. **Set a password** if anyone else can reach your PC (a public Radmin or Hamachi network, for example).
   In the CO-OP panel press **F3**, pick "Join password" and type one. Without a password, anyone who can
   reach the port can join.
4. **Load your save.** Friends can connect now. They see your fleet in their lobby and pick the ship they
   want to fly; your message feed says who joined and who picked what. (Setting "Friends can connect on the
   campaign map"; off: only once a battle starts.)
5. **Start a battle.** Deploy the ships your friends picked if you want them to have them. After you deploy,
   the battle waits while everyone **chooses a ship** and then on the **ready check**. Your panel shows each
   player as loading, choosing a ship, or flying one and ready or not. Press **Enter** to start once everyone
   is ready, or **Ctrl+Enter** to start without waiting.

**What only the host can do**
- Give fleet orders. Joiners see them but cannot give any.
- Pause, and change the battle speed.
- Remove a player: **Ctrl+Shift+** their number (Ctrl+Shift+2 removes P2). They cannot rejoin until you
  restart the game.
- Take a ship back from a player: target it and press **Ctrl+Shift+T**.
- Limit how large a ship joiners may fly ("Largest ship a joiner may fly" in the settings).

**Worth knowing**
- In a campaign battle, a ship lost while a friend flies it is lost for real. Choose who flies what.
- If a player drops, the battle pauses with a notice and their ship goes back to the AI. Unpause as usual.
- Your name as host is the one in the CO-OP panel: clear the address box, type the name, press Enter.

---

## 3. Joining

1. On the title screen, click **CO-OP** in the top right corner.
2. Type the **host's address** and **your name**, plus the **password** if the host set one. Tab moves
   between boxes, Ctrl+V pastes. Press **Enter**.
   - It connects at once, so a wrong address, a wrong password or a mod mismatch shows up here.
   - What you typed is remembered for next time.
3. **The lobby.** If the host is on the campaign map, the **LOBBY** page opens: the host's fleet (ship, class,
   size, CR, officer), who else is connected, and who picked which ship.
   - **Up / Down** moves through the fleet. **F** picks the marked ship for the next battle; **Delete** drops
     your pick. Ships marked *host's flagship*, *mothballed*, *can't be flown* or with another player's name
     can't be picked.
   - **Esc** goes back to the CO-OP panel; **F4** there opens the lobby again.
4. **Stay on the title screen.** When the host's battle begins, the panel counts down three seconds and
   takes you into it. Press **Enter** to go at once, or **Esc** to sit that battle out.
   (With "Join the host's battles by myself" switched off, press Enter yourself once the battle has begun.)
5. Deploy the single stand-in ship.
6. **Step 1 of 2: choose your ship.** If the host deployed the ship you picked in the lobby, you are seated in
   it and go straight to step 2. Otherwise, once the battle has loaded, you get the list of the host's ships,
   largest first.
   - **Up / Down** moves through the list. **Enter** or **F** takes the marked ship.
   - Ships marked *taken*, *the host's* or *over the host's size limit* can't be taken.
   - **W** goes without a ship: you watch, following the host, and can take one later with Transfer Command.
   - Without a lobby pick, if you flew one of these ships last time, you are already seated in it (see
     "Put me back in my last ship" in the settings).
7. **Step 2 of 2: ready check.** It shows your ship and every player's. Press **Enter** for ready
   (**Backspace** goes back to step 1 to change ship). The battle starts when the host presses Enter.

**When the battle ends** you are returned to the title screen, still connected. Stay there and you are
taken into the host's next battle the same way, for as long as the session lasts.

If you leave a battle that is still going, you are not pulled back into it; open CO-OP and press Enter if
you change your mind. The panel also lists who else is in the host's game.

If the second Enter cannot start the battle (this can happen after a game update), open **Missions** and
start **Wingman: Join Game** instead. It does the same thing.

---

## 4. In the battle

**Flying**
- Your own ship answers your controls at once, as in single player. It is quietly kept in step with the
  real ship on the host.
- Everything else you see is the host's battle arriving over the network, a moment late.
- **U** hands your ship to the host's AI (autopilot) and takes it back.
- To change ship during the battle, target a friendly ship and use the game's **Transfer Command** (see
  the game's Settings > Controls for its key). You cannot take a ship another player is flying.

**Leaving with your ship**
- Give your own ship the game's Retreat order and fly it out, **or**
- fly to the bottom edge of the map and press **Enter** when the RETREAT prompt appears. The ship then
  leaves under its own power and you go back to watching.

**If your connection drops**
- Your game tries to reconnect by itself, every few seconds for about half a minute.
- If it gets back in, the battle is loaded again and you are put back in your ship if it is still free.
- The host's game pauses when a player drops; the host unpauses.

**Who is who**
- Every player has a number and a colour: the host is **P1** (green), then **P2** (blue), **P3** (yellow)
  and **P4** (white), in the order they joined. A player who drops and comes back keeps their number.
- The tag sits just off the top right of the ship each player is flying, on every player's screen, and a
  thin diamond outline in the same colour marks the ship itself.
- **F3** during a battle hides the tags and outlines, and shows them again. (It stays that way until you
  close the game.) The ready check shows the same numbers. "Player tags over ships" and "Outline around
  players' ships" in the settings switch them off for good.

**Officers and skills**
- Every ship has the same officer, with the same skills, as on the host, and the fleet commander's
  fleet-wide skills apply to every player's copy of the fleet.
- A ship's speed, turning, weapon ranges, rates of fire and the like are also kept equal to the host's
  figures directly, so a skill or hullmod that behaves differently on your PC cannot pull them apart.
- Fighters that a mod gives officers of their own carry those officers too.

**What is the host's call**
- Where every ship, fighter and missile is, and its hull, armor, flux and shields.
- Whether a missile hits, and hits by weapons with a special on-hit effect.
- Ordinary bullets and beams are judged on each PC, so a near miss can look slightly different between
  players. The damage that counts is always the host's.

---

## 5. Keys

| Where | Key | What it does |
|---|---|---|
| Title screen | Click **CO-OP** | Open or close the co-op panel |
| CO-OP panel | Enter | Connect; then join the host's battle (or skip the countdown) |
| CO-OP panel | Esc during the countdown | Don't join this battle |
| CO-OP panel | Tab / Up / Down | Next or previous box |
| CO-OP panel | Ctrl+V | Paste |
| CO-OP panel | Delete | Clear the box |
| CO-OP panel | F1 | How-to pages |
| CO-OP panel | F2 | Mod compatibility report |
| CO-OP panel | **F3** | **Settings** |
| CO-OP panel | F5 | Switch crashed-out mod systems back on (only shown when there are any) |
| CO-OP panel | Esc | Close |
| Battle (anyone) | **Ctrl+Shift+O** | **Settings** |
| Battle (anyone) | F3 | Hide / show the player tags and outlines |
| Battle (host) | Enter / Ctrl+Enter | Start after the ready check / start without waiting |
| Battle (host) | Ctrl+Shift+2…9 | Remove that player (by their P number) |
| Battle (host) | Ctrl+Shift+T | Take back the targeted ship |
| Lobby (joiner, title screen) | Up / Down, F, Delete | Choose a ship in the host's fleet; pick it; drop your pick |
| Lobby (joiner, title screen) | Enter / Esc | Join the host's battle (when there is one) / back to the panel (F4 opens the lobby again) |
| Step 1, choose your ship (joiner) | Up / Down, Enter or F, W | Choose a ship; take it; go without one (watch) |
| Step 2, ready check (joiner) | Enter / Backspace | Ready / not ready; back to step 1 to change ship |
| Battle (joiner) | Transfer Command | Take the helm of the targeted friendly ship |
| Battle (joiner) | U | Autopilot on / off |
| Battle (joiner) | Enter at the bottom edge | Retreat with your ship |

---

## 6. Settings

Open with **F3** in the CO-OP panel, or **Ctrl+Shift+O** during a battle.

- **Up / Down** picks a setting. **Left / Right** changes it. **Tab** turns the page.
- **Delete** puts a setting back to its default.
- Text settings (password, port) are typed directly; Backspace deletes.
- Changes are saved as you make them. A `*` marks a setting you have changed.
- Each line says who it matters to: **[host]**, **[joiner]** or **[both]**. A "both" setting must be set
  on both PCs.
- In a battle, the page says whether a change takes effect now or from the next battle.

You do not need to change anything to play. The defaults aim for the smoothest picture.

### Hosting and joining
| Setting | Default | What it does |
|---|---|---|
| Join the host's battles by myself | on | Joiner: on the title screen, you are taken into each battle the host starts. |
| Put me back in my last ship | on | Joiner: you start each battle in the ship you flew last time, if it is there and free. |
| Player tags over ships | on | P1–P4 next to the ship each player flies, in their colour. |
| Outline around players' ships | on | A thin diamond in the player's colour around their ship. |
| Show my addresses in battle | off | Host: also show your addresses in the message feed when a battle starts. |
| Friends can join my battles | on | Off: the mod does nothing in your own battles. |
| Join password | none | Joiners must type the same one. After 3 wrong tries from one PC, it must wait 30 seconds. |
| Players, host included | 4 | 4 is what the mod is built for. More means more bandwidth. |
| Largest ship a joiner may fly | CAPITAL_SHIP | CAPITAL_SHIP means no limit. |
| Ready check before a battle | on | Hold the battle while everyone chooses a ship, then until everyone is ready. |
| Friends can connect on the campaign map | on | Friends connect as soon as you load a save, see your fleet and pick their ships. Off: only once a battle starts. |
| Mod list check | content | content: mods must match except utility mods. all: every mod. off: no check. |
| Port | 24816 | Must be the same for everyone. |

### Smoothness
| Setting | Default | What it does |
|---|---|---|
| Updates per second | 30 | How often the host sends positions. 30 is the best balance; 20 on a weak connection. More is not smoother over an uneven connection. |
| Send everything, every update | on | Anything moving just as expected is left out anyway. Off: things that changed only a little are skipped too, and fighters go every other update. Less bandwidth, slightly less smooth. |
| Send updates as changes only | on | Each update carries only what changed. Leave on. |
| Compress the connection | on | Compresses everything sent over the main connection. Leave on. |
| Updates and controls by UDP | on | A lost packet costs one update instead of a freeze. Falls back by itself if blocked. |
| Send unseen enemies less often | on | Enemies nobody on your side can see are reported about once a second. |
| My ship answers my controls at once | on | Off: your ship waits for the host's answer before moving. |
| My flux and ammo answer at once | on | Joiner: your flux bar and ammo change the moment you fire, raise shields or vent, kept in step with the host. |
| Smooth corrections | on | Joiner: other ships follow the host's exactly and each correction fades out over a few frames. Off: the older way. |
| Jitter buffer (uneven connection) | off | Joiner: other ships are shown a moment in the past (at most a quarter second), so late updates make no difference. Try it if ships stutter. |
| Steady controls (lossy connection) | off | Host: joiners' controls arrive with copies and are played back evenly, a little late. Try it if a joiner's ship ignores quick taps or lurches. |
| Bandwidth limit per joiner (KB/s) | off | Host: for a slow upload. When a big battle needs more, what matters most to each joiner goes first. |
| Difference from the host left alone | 1 | With smooth corrections off: raise if things shake; lower to follow the host more exactly. |

### Keeping the games alike
| Setting | Default | What it does |
|---|---|---|
| Hits by shots with a script | match | match: your own hits show at once and the host's fill in what you missed. host: only the host's hits are shown. local: each PC judges for itself. |
| Same-hit window (ms) | 100 | With "match": how far apart your hit and the host's may be and still count as one. |
| Weapons fire as often as the host's | on | Each weapon on a copy of a ship fires exactly as many shots as the real one. |
| Ship stats follow the host | on | Speed, turn rate, flux, ranges, rate of fire. |
| Script memory follows the host | on | Simple values that mods' scripts keep on a ship are copied from the host. |
| Damage reports | on | Damage from scripts is shown on joiners even if their own game did not produce it. |

### Troubleshooting
| Setting | Default | What it does |
|---|---|---|
| Detailed log for bug reports | off | Writes co-op diagnostics to `starsector.log`. Turn on before reporting a problem. |

`wingman_settings.json` in the mod folder holds the defaults with a comment on each. It does not need editing;
anything changed in the settings page takes its place.

---

## 7. Connection and firewall

The mod uses port **24816** in two ways:

- **TCP 24816** is the main connection. It is required.
- **UDP 24816** carries position updates and controls. It is optional: if it is blocked, the mod notices
  and sends those over TCP instead, and the game works as before, just less tolerant of a poor connection.

**On the host's PC**
- The first time you host, Windows usually asks whether to allow Java / Starsector through the firewall.
  Allow it for private networks (and public, if your virtual LAN counts as public). That covers both TCP
  and UDP.
- If friends cannot connect at all, add an inbound rule for TCP 24816. For UDP, add one for UDP 24816.

**Joiners** need nothing opened.

**Over a virtual LAN** (Radmin and the like) use the host's virtual LAN address. No router changes are
needed.

**Over the internet without a virtual LAN** the host forwards TCP and UDP 24816 on their router to their PC
and gives friends their public address. Set a password.

**Is UDP being used?** With the detailed log on, the joiner's log says "UDP in use" and the host's log
lists, per player, how many updates went by UDP and by TCP.

---

## 8. If the game feels laggy

Work down this list; stop when it is good enough.

1. **Check the basics.** A joiner runs a full copy of the battle, so needs about the same PC as single
   player would. Two copies of the game on one PC is heavier than real play.
2. **Wi-Fi or a long route?** Leave "Updates and controls by UDP" on. If the host's log shows it fell back
   to TCP, open UDP 24816 on the host's firewall.
3. **Host's upload is limited?** On the host, lower "Updates per second" from 30 to 20. (Raising it above 30
   costs bandwidth and is not smoother over an uneven connection.)
4. **Still too much?** On the host, set "Bandwidth limit per joiner" to what the upload can carry per friend
   (say 32), or turn off "Send everything, every update".
5. **Other ships stutter or twitch on an uneven connection?** On the joiner, turn on "Jitter buffer". Other ships
   are then a moment (at most a quarter second) behind, but move evenly.
6. **A joiner's ship ignores quick taps or lurches?** On the host, turn on "Steady controls".
7. **Your own ship rubber-bands on a very bad connection?** On the joiner, try turning off "My ship
   answers my controls at once".

All of these can be changed during a battle with Ctrl+Shift+O.

---

## 9. Playing with other mods

Most mods just work: ships, weapons, hullmods, ship systems and missiles from mods are copied like vanilla
ones, and their scripts run on every player's copy, on the host's timing. What causes trouble is not how big
a mod is but what its combat scripts assume. A joiner's battle is a copy started from the title screen, with
no campaign loaded, in which every ship except your own is a puppet the host moves.

### How the mod handles mod content

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

### Optional: Wingman's agent (mods' "player ship" scripts, on every PC)

On the host, a ship a joiner flies is an AI ship as far as the game knows, so a mod's script that does something
only for "the player's ship" (a "take control of a fighter" override, an ability aimed at the cursor, a slow-motion
effect, a status line) never does it for a joiner there. **Wingman's agent** fixes that for every mod at once: while the
host's game runs a joiner's ship (its hullmods, ship system, weapons' scripts, MagicLib abilities), those scripts
see that ship as the player's ship, the mouse as the joiner's cursor, and a hand-over of "the player" to another
ship moves the joiner, not the host. Things meant only for the player's own screen and speed (the status lines in
the corner, slowing the whole battle down) do nothing for a joiner on the host; the joiner's own game shows them.

It works the same way on joiners' PCs: there, the host's ship and the other joiners' ships are treated as their
pilots' ships, so a mod's player-only effect for them plays out on your screen as it does on theirs (without
moving your camera, playing their interface sounds or drawing their status bars on your screen). It is optional;
each player who adds it gets this on their own PC, and the host's matters most. It changes nothing on disk: it works on the mods' code in memory as the game loads it, and only while co-op runs.

To turn it on, add one setting to the game's launch settings, with the game closed. Find `-Xms` (the memory setting) and put the setting just before it, with a space after it:

- **Windows:** in `vmparams` in the Starsector folder (next to `starsector.exe`; open it with Notepad):
  `-javaagent:..\mods\Wingman\jars\WingmanAgent.jar`
- **Linux:** in `starsector.sh`:
  `-javaagent:./mods/Wingman/jars/WingmanAgent.jar`
- **Mac:** in the file where the game's `-Xms` setting is, `-javaagent:` followed by the full path to
  `mods/Wingman/jars/WingmanAgent.jar`.

If you start the game with a custom Java launcher (a `.bat` file that runs `java.exe @SomeFile.txt`, as the
newer-Java packs do), the settings are in that `.txt` file and `vmparams` isn't read: put
`-javaagent:../mods/Wingman/jars/WingmanAgent.jar` on a line of its own at the top of the `.txt` file.

(If you renamed the mod folder, use your folder's name instead of `Wingman`.) To check it works, look in
`starsector.log`: "Wingman agent: running" when the game starts, and "Wingman agent: on" when a co-op battle starts; "not started" means the
setting isn't being read. To turn it off, delete the setting again.

**If the game won't start after adding it,** delete it again: your game's Java may lack the part agents need.
Please report that, with your Starsector version and operating system.

Some things it can't do yet: a mod's key that is read in the joiner's ship's script on the host is never pressed
there (the joiner's own copy of the script still sees their key, so effects on their own screen work as before),
and a mod's battle-wide script that does something to "the player's ship" every frame still does it for the host
only.

### What kind of mod can cause problems

| Risk | Kind of mod | What can happen | Covered? |
| --- | --- | --- | --- |
| **Was a crash, now covered** | Combat scripts that read the campaign: the player fleet, its cargo or credits, the player's skills or story points, campaign flags. Common in battle objectives, "rewards on kill" and special encounters. | A joiner has no campaign loaded. It now gets a stand-in copied from the host's (cargo, credits, level, skills, story points, simple flags), so these scripts find something. | Mostly. Not copied: the player character itself (if the game has none on the title screen), faction standings, markets, and flags holding anything other than yes/no, numbers or short text. Ship systems and objectives that still crash are switched off on that PC after one crash. |
| **Was a crash, now covered** | Hullmods or skills that read a ship's fleet or fleet commander during battle (`member.getFleetData()`). | A joiner's copies now belong to stand-in fleets, one per side; the players' side is led by a copy of the host's character. | Yes. Fleet-size bonuses a script works out from that fleet may differ a little from the host's; the key stats are kept equal anyway. |
| **Crash (joiner)** | Combat scripts that assume the player's ship is a real, armed ship. | A joiner without a ship sits in an invisible stand-in with no weapons; a script that reads its weapons or officer may fail. | Partly: the stand-in is a normal small ship, so most scripts are fine. |
| **Covered** | Carrier scripts that look at the carrier's own fighters (`getAllWings()`), and carrier panels. | Used to find no fighters on a joiner's copy (greyed carrier panel, effects missing). | Yes: the host's fighters sit in the copy's hangars. |
| **Covered** | Mods that put their own AI on a ship (Arma Armatura's carrier refit, scripted manoeuvres). | Used to take a joiner's ship away from them on the host: docked it for repairs and gave it a new AI. | Yes: a strikecraft refits only on the joiner's autopilot, and the joiner's controls are put back after a mod resets the AI. |
| **Covered** | MagicLib subsystems on a joiner's ship. | Used to be fired by the ship's AI on the host whatever the joiner pressed. | Yes: they follow the joiner's key. |
| **Covered with the agent** | Scripts that do something only for "the player's ship" (abilities, overrides, status lines). | On the host a joiner's ship is an AI ship, so such a script never does it for a joiner there. | With Wingman's agent (optional, host only, see above): yes, for scripts that run as part of the ship. Without it: only the cases Wingman knows (MagicLib subsystems, Isenoria). |
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

## 10. Troubleshooting

| What you see | What to do |
|---|---|
| "Could not reach" / connection refused | Check the address. Check the host is in the game. Check TCP 24816 is allowed through the host's firewall. |
| "Version mismatch" | Host and joiner have different versions of this mod. Both need the same version. |
| "Different game version" | Host and joiner are on different Starsector builds. |
| "Your enabled mods don't match the host's" | The list shows what differs. Match the host's mods and versions. |
| "Wrong password" / "needs a password" | Type the host's password into the CO-OP panel. |
| "Too many wrong passwords" | After 3 wrong passwords, a PC must wait 30 seconds before trying again. Check the password with the host, wait, and connect again. |
| "Game is full" | The host can raise "Players, host included" in the settings. |
| "Battlefield differs" | You joined before the host's battle had begun. Press Enter on the notice to hop out and back in. |
| "Connection lost" | Your game is already reconnecting. Wait, or press Enter to leave the battle. |
| "No updates from the host" | The host has left the battle, or the host's game is running very slowly. With two copies on one PC, the one in the background slows right down: click into the host's window for a moment. Enter keeps waiting; Ctrl+Enter leaves. |
| You are not taken into the host's battle | Be on the title screen (not in Missions or a campaign). Check the setting is on. If you pressed Esc or left that battle, press Enter. |
| The second Enter does not start the battle | Open Missions and start **Wingman: Join Game**. |
| Effects appear twice or go missing | Set "Hits by shots with a script" to "host". |
| A mod system has no visual effect | See the CO-OP panel: it may have been switched off after a crash. F5 switches it back on. |
| Something odd after changing a setting | Delete on that setting puts it back to its default. |

The mod is written to fail soft. If one part of it hits an error, that part switches itself off for the
battle with a `Co-op:` message and the game keeps running.

### Reporting a problem

1. Turn on "Detailed log for bug reports" on **both** PCs (it is off by default).
2. Reproduce the problem.
3. Send both `starsector.log` files (in the `starsector-core` folder), and say what you saw, on which
   ship or weapon, and roughly when.

---

## 11. Known limits

- This is a beta. Most play so far has been two players; four is the design target.
- The settings page, the compact update format, compression, UDP and fleet-wide skills have had little
  play yet. If something is off, the switches in the settings page turn each of them off.
- Other ships can take up to two seconds to show a weapon coming back from repair, or armor mended by a
  script.
- Flux bars on other players' and AI ships move in small steps.
- Effects driven by mod scripts can be a few frames early or late compared with the host.
- The CO-OP button is also drawn over the Missions and Settings screens.
- Wrecks stay whole by default (host setting "Wrecks stay whole"), so every screen shows the same wrecks. With it
  off, wrecks break up as normal, at the same moment everywhere, but a break line can come out a little
  different on a joiner's screen.
- Starting the battle from the CO-OP panel relies on game internals of 0.98a-RC8. If a game update breaks
  it, the Wingman: Join Game mission still works.
