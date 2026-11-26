# Changelog

## Next (on the test branch)

Protocol 64: everyone needs this build (0.9.4-beta can't play with it).

- **One handler for fast ship systems and teleports, vanilla or any mod's.** Fast movement is now told from how a
  ship moves, not from which system it has: a *burst* (faster, or changing speed faster, than its own engines
  can: burn drive, plasma jets, a phase ship, a mod's dash, a knock) or a *jump* (Phase Skimmer, Phase Teleporter,
  a mod's blink, a script). Which systems do it is read from their description (type, "movement" tag, the speed
  hints they give the AI) and learned by watching; what's learned is kept on your PC (saves/common/wingman_movers).
  - **Phase Skimmer and other jumps on the ship you fly no longer jitter.** Your ship runs on your PC a moment
    ahead of the real one and is kept with it by comparing the two at the same moment; around a jump that was off
    by up to the whole jump (300 units for a skim, from one frame of timing), so the ship was pulled back and
    forth. Now comparisons around a jump are left out, the real ship's jump is waited for, and if only one side
    jumped (no charge left on the other) your ship is put straight where the real one is instead of sliding.
  - Jumps of other ships are seen at any speed (a skim on a ship going over ~400 was missed and slid across).
  - A mod's teleporting system nobody described is learned after two jumps and no longer run on copies (where it
    jumped somewhere else first).
  - Ships whose system doesn't move them (shields, dampers) are no longer held tighter while it runs, which made
    them shake a little.
- **Smoother turning, missiles, shields and shots:**
  - **Spins**: a ship turning faster than it can by itself (maneuvering jets, a mod's spin, knocked round by a
    hit) is turned straight on from the host's turn rate and held close, instead of along a curve that
    overshot as the spin started and stopped.
  - **Missiles** get the same handler: one that jumps (a script) is put straight there; one that bursts (a MIRV
    or a Sabot splitting, a mod's dashing missile) is carried straight and held close. A homing missile's turn
    isn't taken for a burst.
  - **Shields** of other ships keep turning between updates at the rate they were, instead of moving in small
    steps 30 times a second.
  - **Shots from other ships' guns catch up with the host's.** A copy's gun starts firing when the news arrives,
    a little behind the host's shot (half the time between updates, plus however late that update was), so its
    hit flashed after the damage had already shown. Each shot now leaves the barrel as before and makes up that
    distance (at most 60 ms of flight) over its first tenth of a second, so it hits when the host's does.
- **Flux, armor and hull as exact as the host's.** Now that bandwidth isn't the worry:
  - every ship's flux and hard flux go exactly (before, only once the bar had moved by a hundredth; the first try
    at this left a second filter in place that still held a ship back for that, now gone too): about 10% more
    upload in a big battle;
  - armor, disabled weapons and engines, and ammo go as often as updates do for a ship that was just hit (before:
    four times a second);
  - your own flux bar is held to the host's within a quarter of a percent (before: a whole percent, closed
    slowly), and put right at once if a vent or a shot made it a tenth apart. Comparisons right at a shot are left
    out, so it doesn't jiggle while you fire;
  - hull already went exactly (65,536 steps).
- **Collisions:**
  - ships touching on a joiner's screen (ramming, a ship nosed into a station, a crowd) no longer shake. This
    game's own collisions pushed the copies apart while the host's updates pulled them back where the real ships
    are; now a push made only on this PC (a collision, a local explosion's knock, its speed clamped here) is taken
    back, and the host's collisions, which decide where ships really go, are followed;
  - your own ship still bounces at once when you ram something, and for a moment after, small differences from
    the host's (whose bump comes a moment later and may not be quite the same) are left alone until both agree.
- **Everything the host held back to save bandwidth, now sent:**
  - turret angles in 65,536 steps a turn (were 256: 1.4°, and only sent again after 2.8°), so a copy's long-range
    shots and beams point where the host's do; each turret keeps turning between updates at the rate it was;
  - shield direction and missile headings in 65,536 steps too (were 256), a shield sent again after 0.1° (was 3°)
    and a missile after 0.5° (was 4°);
  - fighters in every update (were every other one unless firing or hit);
  - damage from explosions, mines and scripts with every update (was 4 a second), so its hits and sounds aren't
    up to a quarter of a second late;
  - ship stats with every update for a ship whose stats are changing (was 4 a second), so a copy's speed and
    turning follow a system the moment it starts;
  - other mods' script values 15 times a second, every ship (was twice a second, half the ships each time).
  In a big simulated battle (40 ships, 80 fighters, 200 missiles) the updates now cost about 16% more than
  0.9.4-beta's (48 to 56 KB/s per joiner); fighters in every update and the quicker stats, damage and script
  values add some on top. All of it stays far under the host's 6 MB/s limit.

## 0.9.4-beta

For Starsector 0.98.5a-RC9. Everyone in a game needs the same game build and this version (protocol 63: 0.9.3
can't play with it).

- **Starsector 0.98.5a-RC9.** The hotfix renamed game internals again: the title menu (now `title.B`), the join
  mission (`title.Object.N` / `title.Object.for`, the mission id getter back to RC5's name), held-back objectives
  (`specs.S`, the spec store's lookup now `super`), carrier bays (same class, fields back to RC6's names), the
  teleporter class (`systems.J`) and the fighter wing class. RC9's names go first in each table; RC8 and older still
  work through the same tables (the message feed is built for RC9, as the build reads its name from the game).
- Fixed: built-in weapons were missing from the refit page's weapon groups when a hull mod or the ship's variant
  put them there (a modded hull's built-in gun), so a group holding one looked empty. The host now sends them; they
  show as "(built in)", can be grouped, and count in the stats.
- Fixed: a second set of flux, hull and CR bars on the host's ship, seen by a joiner who had taken a ship. It
  belonged to the joiner's hidden stand-in ship, which rode along on the host's ship to keep the camera on the
  fight; it now does that only while the joiner has no ship, and is parked out of sight otherwise.
- Protocol 63.
- **Chat on the campaign map.** For anyone who'd rather not talk on Discord: a CHAT box under the host's CO-OP
  panel and under PLAYERS in a joiner's lobby. The host's starts as a small CHAT tab (with how many lines are new),
  so it stays out of the way. Click it or press **Ctrl+Shift+C**, type, Enter sends; the mouse wheel scrolls back.
  Lines are kept short and a flood is cut off on the host.
- **Ping readout.** Each joiner's round trip to the host is measured every 2 s and shown on the host's CO-OP panel
  and in everyone's lobby (grey, orange, red). In a battle, your own ping sits at the top of the screen and says
  SPIKE when it jumps; setting *Ping in battle* can show everyone's instead, or nothing.
- **Rejoin after a drop.** A joiner whose link drops in the lobby is reconnected as soon as the host can be
  reached (3 s, 5 s, then every 10 s, for 10 minutes). Not after a refusal or a kick. Setting
  *Rejoin by myself after a drop*, on by default.
- **Size of Wingman's pages** (Settings, Interface): 0.75 to 2, on top of the game's own UI scale. Every page was
  checked at 1280 x 720: the refit page folds its stats on a short screen, its weapon-group buttons use shorter
  words, and the lobby's report keeps each figure on one line.
- **Colour-blind player colours** (Settings, Interface), after Okabe and Ito's palette.
- **Keys can be changed** (Settings, Keys): settings, chat, officer links, friend refits, wish lists, player tags,
  and the host's take-back and remove. A key used twice is pointed out. Messages name the key as set.
- **F1 lists the keys** on the officer links, friend refits and wish list pages, and **?** on the CO-OP panel.
- Fixed: on the refit page's weapon groups, "1-5 of 7 weapons" was drawn over the first weapon.
- **The CO-OP panel and the chat shrink separately**, each with its own **-** button, to a small tab. With both
  shrunk, the campaign map keeps one small row of tabs in the corner: CO-OP (players), CHAT (new lines), and
  REFITS or WISH LISTS only when something waits there; a click on a tab opens it. **Ctrl+Shift+P** (can be changed)
  shrinks both at once and, again, puts them back as they were. Remembered on your PC.
- **UI pass.** A **?** button on the officer links, friend refits and wish list pages lists their keys (as F1
  does). WISH LISTS has a button at the bottom of the CO-OP panel next to OFFICER LINKS. The ready check before a
  battle (host and joiners, both steps) takes clicks on its keys. Buttons and hints name keys as set in Settings,
  Keys, and the hints no longer run status text into the key names. The page size is capped at what the screen
  has room for (1.5 at 1920 x 1080, 1 at 1280 x 720) rather than drawing off the edge. Checked at 1280 x 720,
  1366 x 768, 1920 x 1080, 2560 x 1440 and 3840 x 2160. At 1280 x 720 the refit page's search hint no longer runs
  onto the filter buttons, and the lobby's PLAYERS box, squeezed by level-up cards, no longer draws past its edge.
- **OFFICER LINKS button** at the bottom of the CO-OP panel: opens the officer links page on the friend whose row
  is open, or where you left it. Ctrl+Shift+L still works.
- **Menus can't get stuck.** On the campaign map, Wingman's pages (settings, officer links, friend refits, wish lists)
  open one at a time (two at once each took the keys and fought over Esc), and a page that is open but no longer
  drawn closes itself within a second and a half, so nothing invisible is left taking the keys or the mouse (and
  the campaign runs on if it was running). The wish lists' key is now **Ctrl+Shift+W**: Ctrl+Shift+L opened the
  officer links page first, so the wish lists could only be opened with their button. In battles, popups (a
  disconnect, a wrong map, waiting for the host...) have buttons that take clicks as well as their keys, and a
  click on a popup no longer fires the guns underneath. If the title screen's co-op button ever fails and switches
  itself off, the game's own menu is shown again rather than left hidden.
- **Fix: joiners' after-battle report sometimes didn't update.** It was sent only when the host's battle plugin saw
  the battle decided; a battle the host left another way (from the menu, or with everything retreated) ended with no
  report, and joiners kept the one before. The host now keeps the report up to date every two seconds, and whatever
  ends the battle sends it if it hasn't gone out.
- **Fix: joiners didn't hear (or see) overloads.** A ship's copy on a joiner's screen is put into its overload by the
  host's word, and the game plays the overload sound and shows "OVERLOADED" only for one it reaches itself. Now an
  overload that has just begun on the host is heard and shown on joiners' screens too, for every ship, theirs
  included. Likewise a ship system that the copy couldn't switch on itself (its flux or cooldown here stood in the
  way) and was pushed into running plays its start sound.
- **Fix: the build stamp in the log** ("Wingman: build ...") had said 0.9.3-beta since 0.9.3; build.sh now writes the
  version and build time into every jar.
- **Log: what a joiner's controls change.** The joiner's two-minute traffic line now ends with which controls were
  sent as changed and how often ("controls changed: accelerate 240, shield/phase 3900, ..."), so a control that
  flickers frame to frame, or a ship acting on its own, shows in a playtest's log.
- **Weapon groups set live:** on a joiner's refit page, SET GROUPS NOW puts the groups as planned on the ship at
  once, without a market or the host's yes (they cost nothing): one press sends them, not every click. The rest of
  the plan stays as it was; weapons it adds are grouped when that refit is done. Needs a host on this version (the
  button only shows then); an older host still takes groups with a refit.
- **Fix: the officer page couldn't be clicked**, and the arrow keys couldn't reach the middle column of officer
  cards (Left/Right changed the friend). Clicks are now read from the mouse while the page is drawn, as the friend
  refit page does (the CO-OP panel too); the arrows move through the cards, and Tab / Shift+Tab change the friend.
- **Starsector 0.98.5a-RC8.** The hotfix renamed game internals again: the title menu, the join mission, held-back
  objectives, carrier bays, the teleporter class and the battle message feed (whose way in is now named differently
  too: the build reads that name from the game as well, and points MessageSpy at it). RC8's names go first in each
  table; RC7, RC6 and RC5 still work through the same tables (the message feed is built for RC8). The title menu's
  candidates are each tried in full now, as a name can be another class in another build.
- **Big games (checked with 32 players):** the CO-OP panel scrolls (the wheel, or the "more above/below" lines)
  once its friends fill most of the screen's height, keeping the host's row on top and an open row in view; the
  friends' tabs on the officer page and the refits' tabs on the friend refit page scroll left and right (arrows at the
  ends, the wheel over them) and keep the one picked in view; a joiner's PLAYERS box goes to one line each, in two
  columns, and scrolls with the wheel past that (you are listed right after the host); the battle report's pilots
  table packs its rows tighter; "In this game" names the first few and says how many more.
- **The CO-OP panel on the campaign map** (host) does more than list who is here: click a friend to open their row,
  with their linked officer (ASSIGN or CHANGE opens the officer page on them), how their refits are taken, and any
  refit of theirs waiting (REVIEW). Each row shows the ship they picked, their officer and their refit setting. REFITS
  in its header opens the friend refit page while any wait (the separate button stays away while the panel shows).
- **Refits: look only, ask me, or trust**, per friend, from the CO-OP panel or the refit page. Look only (new): they
  can plan refits but a refit sent is refused with a note. Ask me: each one waits for you, as before. Trust: done as
  they arrive (checks still run) until they reconnect, as T did. T still toggles trust.
- **Officer page redone** (Ctrl+Shift+L, or from the CO-OP panel): friends as tabs; your officers as cards with
  portrait, level, personality, a mark per skill (gold: elite) and who has them; the picked one's skills listed at
  the side, a note when linking moves them off another friend or ship, and LINK and UNLINK. Everything works with the
  mouse; the keys are as before.
- **Friend refit page redone:** the refits waiting as tabs, each change a row from what was there to what goes in
  (with pictures, and where it comes from or goes), slots named by size and kind, cost and checks at the side with
  the friend's refit setting, and DENY / ACCEPT at the foot ("ACCEPT AT A MARKET" until docked). A, D, T, Left/Right
  and Esc as before.
- **Officer level-up as cards** (joiner): when your linked officer can level up, YOUR SHIP shows the skills the
  host's game offers as cards (icon, name, aptitude, a line about it); click one and CONFIRM. A number key picks one,
  the same number again sends it.
- **Fix: officer skill pop-ups stuck on the host's screen.** When a friend picked another ship while the host had the
  fleet or refit screen open, their linked officer moved and the screen rebuilt its officer rows under the mouse; the
  pop-up of the one hovered stayed until a reload. The officer still moves at once; the open screen now shows it
  when it is next opened, instead of rebuilding under the mouse.
- **Loadout selectors in friend refits**, for any mod that has them (Arma Armatura's strikecraft arms, Diable
  Avionics' Virtuous, and others built the same way). A joiner's refit page gets a LOADOUT section: one row per
  selector (R ARM, L ARM, HEAD...), with every option and the weapons it fits; pick one with Left/Right or a click.
  Nothing is written for a particular mod: the host finds each cycle by trying it on a copy of the ship (the
  selector off, the game works the ship out again, the mod's own hull mod puts on the next one), and on accepting
  steps the real ship round to the option picked the same way. The weapon slots a selector owns are left off the
  weapons list (the host refuses changes there), and their weapons aren't asked for from the host's cargo. A refit
  made in the game's own screen is read the same way. Checked against Arma Armatura 3.3.9 (Tartiflette's selector
  code in data.hullmods.armaa_weaponSwap and the other swaps).
- **Refit in the game's own refit screen** (joiner): on a ship's refit page, G puts that ship, exactly as the host
  has it, into a new mission, "Wingman: Refit". Open it from Missions, click the ship, Refit, and change what you
  like in the game's real refit screen. Back in CO-OP the changes load onto the refit page, with what the host has
  and what it costs; send them as any friend refit (the host checks it all again). No game internals: the game's
  own saved name for the mission's ship is read back through the API. The mission's screen shows every weapon and
  hull mod; the host's check catches what they don't have. S-mods can't be built in there.

- **Starsector 0.98.5a-RC7.** The hotfix renamed game internals again (the title menu, the join mission, held-back
  objectives, carrier bays, teleporters, the battle message feed). Each is now looked up from a table of names by
  game build, newest first, so RC5, RC6 and RC7 all work with one jar (the message feed is built for RC7).
- **Defaults** stay as recommended: 30 updates a second, compact and compressed updates over UDP, no bandwidth
  limit, steady controls and the jitter buffer off (switch them on for a friend with packet loss or stutter).
- **Starsector 0.98.5a-RC6.** The hotfix renamed the game internals behind the full-screen lobby, starting the join
  battle from the title screen, script messages in battle, matching wreck break-ups, held-back objectives, quiet
  carrier bays and teleporting systems; all found again (RC5 names kept as the fallback). The build now reads the
  battle message feed's names from the game itself (`tools/GameNames`). Host and joiners still need the same RC.
- **Joiners upload far less.** Controls went up to 60 times a second while the mouse moved (holding a key at 60
  FPS), and each change was sent three times over UDP: about 12 KB/s per joiner in the logs, more than the host sent.
  Now a key or button change goes at once, the mouse and heading at most 30 times a second, and a change is repeated
  once.
- **Steady controls: less delay, no catch-up bursts.** The delay follows the 90th percentile of recent lateness (at
  most 80 ms) instead of the worst arrival (up to 120 ms, as the logs showed), and changes gently, so a calmer link
  no longer makes a burst of controls skipped.
- **Back in your ship after a refresh.** After a resync (often after packet loss) the joiner sat back down only if the
  host's answer came after the ship was rebuilt; otherwise they were left in the stand-in, or stuck in the host's
  flagship, with no controls. Now it keeps trying for 15 s, from either.
- **One slow link no longer slows everyone.** When a joiner's link backs up, that joiner alone gets a bandwidth limit
  (their updates trimmed to what matters most to them) and everyone keeps 30 updates a second; before, the rate
  dropped for all.
- **Updates packed once for many.** Joiners who hold the same updates get the same bytes, packed once (the host's
  main cost per joiner). In tests: 38% fewer packs with 5 joiners, 1% more bytes.
- **Joiners' manual fire works again.** The fire key gives the game's "use selected group" command, not "fire", so
  the joiner's game never saw it as fire once it read the command queue: the host's copy of the ship held fire.

- **Trusted refits** (host): in the friend refit panel (Ctrl+Shift+R), T trusts that player's refits: from then on they
  are done as they arrive, without asking. Every check still runs; a refit that fails one waits or is refused as
  usual. For that connection only: a reconnect asks again.
- **Arma Armatura and other hull-size changes:** a mech that takes off or lands changes its hull size mid-battle; joiners
  now get the same size (0.98.5a's setHullSize), so both copies count ships and fighters alike and stop resyncing.
- **Diable Avionics' Virtuous:** taking a selector hull mod off in a friend's refit steps the ship to the next hull
  config, weapon kit or head, as Diable's own hull mod does.
- **Officers for friends** (host): Ctrl+Shift+L on the campaign map links one of your officers to a connected
  friend. That officer then captains whichever ship the friend picks (the ship's old officer swaps to the one left),
  and when it can level up the friend picks its skill in the lobby (numbers 1-8, from the game's own choices; the
  host's game levels it up). A linked officer may level as far as a player character (host setting, on). Save-safe:
  the link is a plain tag and the cap is the game's own per-officer setting, so saves load fine without Wingman.
- These four after scardwell15's pull request #1.

## 0.9.3-beta

Protocol 61. For Starsector 0.98.5a-RC5 only: the game internals Wingman uses were renamed in the update, so it
no longer runs on 0.98a. The host and every joiner update the game and Wingman together.

- **Starsector 0.98.5a.** Every game internal Wingman looks up was mapped again; `test/InternalsCheck` checks
  them all against a game install, so the next update is quicker to follow.
- **Wish lists.** Joiners wish for weapons and wings; the host sees every list (WISH LISTS on the campaign map),
  buys what a market has with one click, and what is bought is set aside in cargo for that player.
- **Saved refits:** up to 3 per ship, loaded again at this or another market. The refit screen's sections fold.
- **Refit screen:** weapon groups can be changed; a Stats section shows flux, shields, weapon flux and damage,
  and what the plan changes; weapon cards show damage, flux, range and type; long market and item names fit.
- **Shields forgive the delay** (host setting): a shot a joiner's shield stopped on their screen is given back
  if it hit their hull on the host first. Point defense on joiners' screens aims at the host's targets. Short
  fire taps are no longer lost; a ship system use the host never acted on is undone.
- **Fixed:** a joiner freezing on joining with Second in Command and a large fleet; venting a freshly rebuilt
  ship stopping co-op (and the crash after it); the agent's hooks left in after a battle that ended badly.
- **Launcher:** `Start Starsector with Wingman agent.bat` now sits in the zip beside the Wingman folder and goes
  in the Starsector folder. Launch settings left from before a game update (Mikohime with Fast Rendering's
  `fr.jar` gone) are noticed, and the normal game is started instead.
- **Joiners' controls from the game's own commands.** A joiner's ship now reports what the game told it to do
  (`ShipAPI.getCommands2`, new in 0.98.5a, read by an advance listener before the ship carries the commands out):
  fire on any key it is bound to, and every press, however short. Without it (paused, or an older game) the
  controls are worked out from the mouse and the ship's state, as before.
- **Mods' battle scripts guarded after a crash.** While a joiner's battle runs, the mods' battle scripts in it are
  known (`CombatEngineAPI.getPlugins`, new in 0.98.5a) and Wingman listens to the game's log. When the game logs the
  error it closes on and one of those scripts is in it, the script is noted; from the next battle on that PC it runs
  wrapped, so an error switches it off for the battle with a "Co-op:" message instead of closing the game. Only
  scripts that crashed are wrapped (a wrapped script can't be found by its class, which some mods rely on). The
  CO-OP panel lists them; F5 puts them back to normal, with the ship systems.
- Clearing a ship's own order uses the game's `cancelDirectOrdersForMember2` (PvP captains' orders on the host,
  and joiners' copies of the host's orders, which before only undid a retreat).
- **Launcher and Fast Rendering 1.0:** the .bat finds FR 1.0's `fr.bat` (in starsector-core, settings in
  `fr.vmparams`) and adds the agent under FR's (and FR Resource Cache's) own agent lines. A Mikohime setup made
  for FR's old class loader, which FR 1.0 no longer has, is noticed and skipped instead of failing to start.
  Checked: every game class loads with FR 1.0, FR Resource Cache 0.6.1 and Wingman's agent together, and the
  agent changes the same 460 classes as without them.
- **The launcher always starts the game:** with no Mikohime, Fast Rendering or other launcher it uses the normal
  game's settings (vmparams, or starsector-core's starsector.bat); if the agent can't be added (no Java, no agent
  jar, settings it can't read) it starts starsector.exe without it, and says why.
- **No 16-player cap any more:** a game can have up to 255 players (as many as its messages can number), set in
  the settings (2 to 64) or in wingman_settings.json. Player colours come round again after P16, and
  **Ctrl+Shift+K** removes whoever flies the targeted ship, for players past the number keys' P9. Untested beyond a
  handful of players: more players means more bandwidth and more work for the host's PC.
- Accepting a friend's refit no longer opens the refit screen on that ship afterwards (host setting "Reopen the
  refit screen after a friend's refit", now off by default).
- **For add-ons:** `wingman.api.Pvp` and more of `WingmanAPI`, used by Wingman PvP.

## 0.9.2-beta

Protocol 60, as before. The host and every joiner should update together (the agent changed too).

- **The CO-OP panel is a screen of its own.** On a small screen the game's title menu sat over Wingman's windows. Now
  CO-OP opens a full screen (the game's menu moved aside, as for the lobby) with the connection window in the
  middle; how-to, mods and settings open there too. A header across the top has **MAIN MENU** (back to the game's
  title screen; the connection stays) and, while connected or connecting, a red **DISCONNECT**. The lobby has the
  same two buttons. If the game's menu can't be moved aside, the windows hang from the corner as before.
- **Fixed: the game's missions list came up over the lobby** after a joiner left a battle themselves (Esc, End
  Battle): back on the title screen, the list the join mission was started from is closed, as its own Back does.
- **Host tethering** (host setting, off by default): if a mod's script (or a joiner's ship's
  script run on the host) moves the host's controls into an enemy ship, the host is put straight back in their own
  ship, and the log says what did it (with the agent, the script's own lines). Dev mode, where the game's own
  Transfer Command can take enemy ships, is left alone.
- **Fixed: the market dialog could be left empty after the host accepted a friend's refit** with the refit
  screen open. Wingman closes that screen to do the refit and opens it again; when the dialog came back without
  its options (nothing to click, as if it had vanished), they are now put back.

## 0.9.1-beta

Protocol 60, as 0.9-beta. The host and every joiner should still update together: the refit and battle fixes
are on both sides.

**Friend refit: plans that last**

- The refit screen no longer closes when the host leaves a market. Away from a market the joiner plans: the ship,
  the host's cargo, credits and story points are shown, and sending waits until the host docks. Only a battle (or
  picking another ship) closes it.
- Each ship's plan is kept until the host accepts it, through undocking, battles, other picks and restarts (a
  small file in the game's common folder, at most 24 ships), and comes back when the refit screen opens on that
  ship again.
- At another market, what the plan wants that isn't there (an item no longer in stock, a market that isn't here,
  a place to sell that won't take it) is listed under "Not available at ...", and marked on its slot: click it
  and pick something else. Away from a market the same list reads "Waits for a market".
- The host's credits and story points are always shown at the top of the refit screen, with the plan's cost
  against them.
- A request the host hasn't decided on waits when the host undocks instead of being withdrawn; it is checked again
  at the next market, and can be accepted only while docked. The host's FRIEND REFITS button sits under the
  player list when both show.
- **Search** over the refit screen's cards: a box above them (click it, or Ctrl+F) narrows weapons, fighter wings
  or hull mods to those with every typed word in their name. Esc clears it.
- D-mods are shown in red: on the lobby's ship card and in the refit screen.
- **Available** tick beside the search (on by default): the cards show only what the host can get. Untick it to see
  every weapon that fits the slot, every fighter wing or every hull mod in the game, the rest greyed as
  "Unavailable" after what the host can get. They can be searched but not fitted.
- **Everything outside battles works with the mouse:**
  - Every key hint at the bottom of a window is a button that does what the key does, and each window has a close
    box (X) at the top right.
  - The CO-OP panel has buttons (CONNECT / JOIN THE BATTLE, LOBBY, HOW TO, MODS, SETTINGS); the boxes take the typing
    when clicked.
  - Settings: click a tab for its page, a row to mark it, the arrows (or the marked row again) to change it; DONE and
    BACK TO DEFAULT buttons. On the campaign map too, and a SETTINGS button in the host's player list opens it.
  - How-to and mod report: NEXT / BACK / DONE (and FORGET WHAT WAS SEEN) buttons.
  - Lobby: click a ship in the list to look at it, twice to pick it; PICK THIS SHIP, UNPICK and REFIT on its card;
    JOIN THE BATTLE at the top while the host is in one; click the page tabs or the last battle box; the mouse wheel
    goes through the fleet.
  - The host's friend refit panel: its key hints click too.
- **Hull mods offered** are now those the host's own refit screen offers: the ones the host has learned and the ones
  every player starts with (before, the ones known from the start were missing). Hidden ones and D-mods are left
  out.
- After a refit is accepted, the joiner's refit screen asks the host for the ship afresh before anything new can be
  planned (before, a new plan could start from the old loadout). Refused refits are written to the host's log with
  the reason, and the host's answers to the joiner's.
- **A refit accepted in the host's refit screen is done at once:** that screen closes for a moment (its copy of the
  ship would undo the change), the refit is done, and it opens again on the ship. Before, it waited until the
  screen was closed, which could mean until leaving the market.
- **Pop-ups** over weapons, fighter wings and hull mods in the refit screen (cards and tiles) and on the lobby's ship
  card, as in the game's refit screen: what it is, ordnance points on this ship, range, damage and type, damage
  and flux per second, EMP, ammo (fighters: craft, count, refit time), and the game's own description; hull mods
  with their description for this hull size.
- **Fix: a crash in battle with MagicLib subsystems** on some setups (Fast Rendering's loader): Wingman's own hook
  named a class the game refuses to scripts ("File access and reflection are not allowed"). It no longer does, and
  the build now checks that hook too.
- **Allied ships** (another fleet fighting on the player's side) are marked as allies on joiners' screens too, as on
  the host; before, they looked like the player's own.
- **Joiners can't take allied ships, stations or automated ships** (in a battle, and automated ones can't be
  picked in the lobby either).
- A joiner who ends the battle from the game's own menu goes straight back to the lobby, without the results
  screen.
- A **LOBBY** button beside CO-OP on the title screen while connected.
- Hidden hull mods aren't listed in the refit screen; S-mods are shown in green (lobby card and refit screen).
- The cards come 20 at a time: "Show 20 more", or the mouse wheel at the end of the list, brings the next 20. New
  pictures load a few a frame, so long lists don't stall the screen.
- **Fixed: a joiner flying Isenoria's Rafflesya was thrown back into the carrier once its drones launched.** The
  drift check counted the drones as fighters on the host and as ships on the joiner, saw a mismatch, and refreshed
  the whole battle, which ended the hand-over. The joiner now counts by hull size, as the host does.

## 0.9-beta
Protocol 60: host and joiners need this build.

**A new look**

- Every Wingman window is built the same way: a title bar, the content in named sections, notices marked down
  their left edge, and the keys that work as key chips along the bottom. The CO-OP panel (join, the host,
  hosting from this PC, notices), the settings (a tab per page, a row per setting with who it matters to and an
  "edited" mark, then what the picked one does), the how-to, the mod report, the ready check and alerts.
- The lobby: the fleet list with size and pick tags, the ship card in sections (officer, weapons and wings, hull
  mods, then the ship's state and the Refit button), and the right column split into your ship, the players (in
  their colours, with their picks) and the last battle. The battle report is a table.
- Friend refit: the refit screen in three columns (ship, loadout, the marked slot), with the limits as bars that
  turn red past the most allowed, the cost, and what to fix before sending; the host's panel in sections
  (request, changes, cost, checks).
- The player list on the campaign map matches.

**Friend refit** (design: `docs/FRIEND_REFIT_DESIGN.md`)

- While the host is docked at a market, joiners are told so in the lobby, and the card of the ship they picked
  has a **REFIT (R)** button. Nothing opens by itself.
- The refit screen shows that ship as it stands on the host, with ordnance points, vents, capacitors and S-mods
  against the host's limits (worked out with the host's skills), and what the host has: credits, story points,
  cargo, storage, and each submarket with its tariff and stock, or why it is shut to the host.
- Joiners choose a new loadout there: weapons (only what fits each slot) and fighter LPCs from the host's cargo,
  storage or a market (bought at its price), where each removed item goes (cargo, storage, or sold), vents and
  capacitors, hull mods the host knows on and off, S-mods built in for a story point each. The totals and
  anything that stops it show as they choose. A short note can go with it.
- The host gets a message and a FRIEND REFITS button (Ctrl+Shift+R): each request with every change, its cost
  and the checks, all made again on the host's campaign. Click YES or NO (or press A / D). Accepted refits are done as the
  game's own refit and market screens would: items move, markets are paid and hear of the trade, story points
  are spent with the usual bonus experience, and the ship gets a refit variant of its own.
- The host can accept in the refit screen: the refit is done the moment that screen closes (it keeps its own
  copy of the ship, which would undo a change made under it), and the refit screen then opens again on that ship.
  Host setting `friendRefitReopen` (on by default).
- The refit screen is made for the mouse, with pictures: click a ring on the ship or a tile, then a card (weapon
  or fighter picture, OP, where it comes from, price) to fit it; buttons for where the old one goes, hull mods,
  vents and capacitors, undo; SEND TO HOST at the bottom; the mouse wheel scrolls. The host's panel shows each
  change as pictures too, and has YES / NO buttons.
- Requests are refused with the reason if a check fails, and withdrawn when the host undocks, the pick changes or
  a battle starts. Whether a hull mod suits the ship isn't checked (the game can only tell in the refit screen):
  the host decides.
- Host setting `friendRefit` (on by default): "Friends can refit their ships".

**Changes**

- The host's **player list** moved from battles to the **campaign map**, and is smaller: a compact box at the top
  right while friends are connected, one line per player in their colour with the ship they picked ("no pick"
  otherwise) and "(link quiet)" when their connection goes silent. Hidden while a dialog or a fleet, cargo or
  refit screen is open. Battles no longer show it (the player tags still say who flies what). Setting `playerList`.

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
