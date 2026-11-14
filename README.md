# Wingman PvP

Player-versus-player battles for [The Wingman Project](https://github.com/NEOysod/thewingmanproject), a co-op
multiplayer mod for Starsector 0.98a.

- Needs The Wingman Project with PvP support, the same version on every PC, and the same mod list everywhere
  (Wingman checks it).
- Pick **Multiplayer PvP Battles** at any bar, then **Host a match** or **Join a match** (Wingman's address and
  password, as on its join screen).
- Teams of 1 to 4. Each team has a captain who commands its fleet; everyone flies a ship of it.
- Fleets come from each player's own save. Nothing in anyone's save changes because of a PvP battle.
- Only captains' fleets are used: each captain brings ships from their own fleet (within the team's points
  limit), and every teammate picks the ship they fly from them.
- Joiners' games save and go to the main menu when the battle starts, and join it from there.

How it works and the rules: `docs/DESIGN.md`.

## Keys in the battle

| Key | Who | What |
| --- | --- | --- |
| Space | captains | pause (uses a charge) or resume your own pause; after a disconnect, "go on without them" |
| Enter | a player who reconnected | "I'm back and ready" |
| Enter | host, before the start | start the battle (Wingman's ready check) |

## Building

`./build.sh /path/to/starsector-core /path/to/Wingman.jar` rebuilds `jars/WingmanPvP.jar`. The tests:
`java -cp Wingman.jar:build/classes:build/test wingmanpvp.LobbyTest` after compiling `test/`.
