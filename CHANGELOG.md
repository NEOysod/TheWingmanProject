# Changelog

## 0.1.1-beta (public beta test)

- For Starsector 0.98.5a and Wingman 0.9.3-beta.
- **Second in Command:** each team fights with its captain's executive officers. Before, the host's team got the
  host's (even with another captain) and the other team could get random NPC ones. The host's own are put back
  after the battle. Without that mod nothing changes.
- **Only captains' fleets are used.** A captain ticks the ships the team brings from their own fleet (the points
  limit is now per team); every teammate picks the ship they fly from those, one player a ship. Changing captain
  or team, or the captain dropping a ship, asks whoever flew it to pick again. Only captains send a fleet at start.
- **Joiners save before going to the main menu** for the battle (the game's Save and exit; without saving only if
  the game can't be saved then).
- Lobby messages version 3 (everyone needs this version).

## 0.1.0-alpha (test)

First version, for testing.

- **Multiplayer PvP Battles** at every bar: host a match, or join one with Wingman's address and password.
- **Lobby:** two teams of up to 4, a captain each (the host can hand it over, move players, remove them), the
  ships each player brings (ticks) and the one they fly, a points limit per player (default: the host's fleet
  points), an "agreed rules" line, chat, ready, start.
- **Fleets** go to the host as plain data (hulls, loadouts, S-mods, D-mods, CR and hull, officers and skills) and
  are built there as copies. Nobody's save changes; the host's fleet and cargo are put back exactly.
- **Joiners** go to the main menu without saving once every fleet is in, and Wingman takes them into the battle.
- **Battle rules:** captains pause with Space (1 charge per minute of battle, up to 3, at most 60 s each, 3-2-1
  countdown); time flow held at 100%; no retreat; a dropped player pauses the match until they are back and ready
  or every captain goes on; a dropped captain's side is commanded by the next player meanwhile.
- **Results** for everyone at the end; the host can start a rematch with the same fleets.
- Needs The Wingman Project with PvP support (this test's build).
