# Wingman PvP: design

A separate mod that needs The Wingman Project. It turns Wingman's co-op into player-versus-player battles: up
to 4 players a team, 1v1 being the main case. Fleets come from each player's own save; nothing in anyone's save
changes because of a PvP battle.

## The rules

| Topic | Rule |
| --- | --- |
| Teams | Two teams (A blue, B red), 1 to 4 players each. Players switch teams only in the lobby. |
| Captains | Chosen in the lobby, one per team. The captain commands the team's whole fleet: deploying ships and giving orders, as the player does in vanilla. Every player flies their own flagship. |
| Fleets | Only captains' fleets are used. A captain ticks the ships the team brings from their own fleet; each teammate picks the ship they fly from those (one player a ship). |
| Points limit | Per team (the captain's fleet), set by the host. Default: the host's fleet points (deployment points of the host's combat-ready fleet). A captain over the limit can't be ready until under it. |
| Officers, S-mods, house rules | Not policed by the mod. Players agree in the lobby chat; the host can write them on the "Agreed rules" line everyone sees. |
| Pausing | Only captains, with Space. Each side starts with 1 pause charge, regains one per 60 s of battle and holds at most 3. A pause lasts until the captain who paused presses Space again, at most 60 s, then a 3-2-1 countdown for everyone. |
| Time flow | Locked at 100%. The player-only slow-down from a flagship's time systems and any fast-forward are off. A ship's own time-flow system still speeds that ship up, as when the AI uses it. |
| Retreat | Off. No ship can leave the map; the battle ends when one team has no ships left on the field and none in reserve that can deploy. |
| Disconnects | The match pauses for everyone and says who dropped. It resumes when every side with a captain connected presses Space, or the dropped player reconnects and presses Enter in their ship; then a 3-2-1 countdown. A captain who drops is replaced by the next player on the team as acting captain, and gets command back on return. |
| After the battle | Everyone sees the results screen. The host's fleet is put back exactly as before; salvage, loot and recovery screens are skipped. Joiners' saves were never touched. |

## How a match goes

1. **Host:** loads a save, docks, opens the bar and picks **Multiplayer PvP Battles**, then **Host**. Wingman's
   session opens (Wingman's port, password and mod check). The lobby screen opens inside the bar dialog.
2. **Joiner:** loads their own save, docks, bar, **Multiplayer PvP Battles**, **Join** (Wingman's address and
   password). The lobby opens; they pick a team and the ships they bring.
3. **Lobby:** teams, captains, the fleet each player brings, points, settings, chat, ready.
4. **Start:** when everyone is ready and within the limit, the host presses Start. Each joiner's fleet is sent to
   the host (ships, condition, officers and skills). Joiners' games save and go to the main menu;
   Wingman's title screen joins the battle by itself. The host's game waits until every joiner is on the title
   screen, then starts the battle.
5. **Battle:** run by Wingman on the host as usual, with the PvP rules above. Each player sees their own team as
   friendly.
6. **End:** results screen on every PC. The host is back at the bar with the fleet restored. Joiners are on the
   title screen and load their save again (it is exactly as they left it).

Joiners go through the title screen because Wingman's joiner side runs its copy of the battle as a mission
started from the title screen; a campaign battle on the joiner's PC would need Wingman's joiner side rebuilt.

## Where the code lives

**This mod (`wingman_pvp`, package `wingmanpvp`):**

| Part | Classes |
| --- | --- |
| Start-up | `PvpModPlugin` (adds the bar event, the campaign script) |
| Bar | `PvpBarEventCreator`, `PvpBarEvent` (the bar option, host/join) |
| Fleet transfer | `FleetPack` (a player's fleet as bytes: hulls, loadouts, S-mods, condition, officers), `FleetBuild` (back into fleet members on the host) |
| Lobby | `LobbyState` (the whole lobby, host-authoritative, as bytes), `LobbyHost` (rules, applying players' requests), `Proto` (messages), `PvpSession` (this PC's part: host or joiner, the network), `LobbyPanel` (the screen), `PvpDialog` (the bar dialog: host, join, lobby, results) |
| Battle | `PvpBattle` (host: fleets for each team, starting the battle, restoring afterwards), `PvpRules` (pause charges, countdowns, disconnects), `PvpBattlePlugin` (every battle and the title screen: the rules on the host, keys and the pause banner on joiners, results on the title screen), `BattleProto` (battle messages and results) |
| Campaign | `PvpCampaignScript` (pumps Wingman's link, the joiner's exit to the main menu) |
| Network | Wingman's mod channels (`WingmanAPI.send` / `listen`), channel names `wingman_pvp:*` |

**Wingman itself** (small, general hooks in `wingman.api`, so PvP is not wired into Wingman's core):
- `WingmanAPI.join` / `leave`: connect to a host from the campaign map (not only the title screen).
- A PvP battle mode for Wingman's host and joiners: players on both sides; each joiner sees their own side as
  friendly; one captain per side whose orders and deployments are carried out on the host; pausing and time flow
  under the mod's rules.

## How the battle is set up (0.1)

- **Sides.** The host's team is side 0 (the player fleet); the other team is side 1, a fleet made for the battle.
  Every ship is a copy built from its captain's pack, the host's own included: the host's fleet and cargo are put
  aside for the battle and put back exactly afterwards (`PvpBattle.restore`).
- **Deployment.** Side 1's ships are all deployed by the game's admiral (`enemyDeployAll`), which is then told to
  give no orders: its captain does. Side 0 deploys through the host's deployment screen as in any battle (the host
  should deploy every teammate's flagship). Each joiner is seated in the ship they picked from their captain's fleet by Wingman
  (`WingmanAPI.reserveShip`) during Wingman's ready check; the host starts the battle there with Enter, as in co-op.
- **Orders.** Each joiner sees their own side's orders. A captain's changes in the command screen are sent to the
  host and carried out for that side (Wingman's `OrderRequest`).
- **Time and retreat.** Each frame on the host: the battle's time multiplier is reset to 1 (a ship's own time
  system still speeds that ship up); retreat orders become search and destroy; full retreat is off.
- **Results.** When the battle is decided, the host works out who lost what and sends it to everyone. Joiners
  are back on their title screen (Wingman takes them there); the results show at the top. A rematch with the same
  fleets takes them into the next battle by themselves; otherwise they load their save.

- **Second in Command.** That mod keeps a fleet's executive officers in its fleet's memory (`$sc_stored_data`) and
  applies their skills to each ship from it. The captain's pack carries their executive officers (slot, aptitude,
  active skills); `SicBridge` gives side 1's fleet its own copy of them (tagged `sc_do_not_generate_skills`, so no
  random NPC ones) and, when side 0's captain isn't the host, swaps the host's for the captain's until
  `PvpBattle.restore`. All by name, so the mod isn't needed.

Known gaps to test in game: the host's deployment screen (side 0) is the vanilla one; the joiner's exit to the
main menu uses the game's "save and exit" (or "exit without saving" when it can't save); Wingman's own lobby screen on the title
screen may cover the PvP results box.

## Messages (channel `wingman_pvp:lobby` and others)

All bytes packed with Wingman's `Msg.W` / `Msg.reader`; every message starts with a type byte and the protocol
version. The host owns the lobby: joiners send requests, the host checks them and sends the whole `LobbyState`
back to everyone (small: names, teams, ships as id + hull + points).

| Type | From | Holds |
| --- | --- | --- |
| `HELLO` | joiner | protocol version, the fleet summary (ships: id, hull, name, points, officer name) |
| `STATE` | host | the whole lobby: settings, players (team, captain, ready, ping, picks, points), chat tail |
| `TEAM` | joiner | team wanted |
| `PICKS` | joiner | ship ids brought, flagship |
| `READY` | joiner | ready or not |
| `CHAT` | joiner | a line of chat (host adds it to the log) |
| `FLEET` | joiner | the full fleet pack of the picked ships, sent at Start |
| `GO` | host | everyone to the title screen; the battle is starting |
| `YOU` | host | to one joiner once let in: their id in the lobby |
| `BYE` | host | removed from the lobby (kicked, full, or the host closed the match), with the reason |

Battle channel `wingman_pvp:battle` (`BattleProto`): `PAUSE`, `GO_ON`, `BACK`, `AT_TITLE` from joiners; `STATE`
(pause state), `LINE` (feed lines), `RESULT`, `OVER` from the host.
