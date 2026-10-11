# Game code audit: where a joiner's ship is not the "player ship"

Starsector 0.98a-RC8. Wingman runs a joiner's ship on the host as an ordinary AI ship, flown by `RemotePilotAI`.
The game treats the ship the local player flies (`CombatEngine.getPlayerShip()`, usually with no AI) differently
in a number of places. Each of those is a place where a joiner's ship can behave differently from the same ship
in single player, or from the joiner's own predicted copy (which *is* the player ship on their machine). That is
the source of both gameplay differences and prediction errors (rubber-banding).

Method: every class in `starfarer_obf.jar` that calls `getPlayerShip`/`isPlayerShip` (190 classes, 60 combat
methods), read in bytecode; every vanilla script in `starfarer.api` (`api/impl`) that checks the player ship;
the `Ship` command loop, `WeaponGroup`, `CombatEngine.advanceInner`; then compared with what Wingman relays and
syncs (`InputState`, `Snapshot`, `ShipStats`, `DamageState`).

Status: **fixed** (in this build), **handled** (already covered), **open** (not fixed; reason given).

## Engine and ship

| # | What the game does differently for the player's ship | Effect on a joiner | Status |
|---|---|---|---|
| 1 | `CombatEngine.advanceInner`: a ship whose time multiplier is above 1 (phase cloak, temporal shell, mod time systems) is moved in several steps a frame, and its AI is asked to fly in every step (the player's ship gets its commands copied into each step). | `RemotePilotAI` answered only the first step of each frame (it skipped calls at the same battle time), so a phased or time-boosted joiner ship coasted through the other steps: turned, accelerated and fired a fraction as well as on the joiner's screen. Prediction error grew with the multiplier. | **fixed**: every step is flown; only the game's call right after the host flew a fighter itself is skipped (`flyFromHost`). |
| 2 | `WeaponGroup.advanceAlternating`: while fire is held, the active weapon of an alternating group moves on every cycle/n seconds only for the player's ship with no AI. | Only the first weapon of the group fired while held. | **fixed** (`GroupFire.takeTurns`, previous build). |
| 3 | `WeaponGroup.advance`: the selected group of the player's ship is under the fire button even with autofire on (its weapon AI forced off). | The group in hand would be AI-fired. | **handled**: autofire switched off for the group in hand. |
| 4 | `Ship` command `PULL_BACK_FIGHTERS` (Z): toggles the order, sets the wings' waypoint to the cursor when engaging, cancels the fighters' current maneuvers; for the player's ship also sets `forceCarrierTarget` (the R target, 20 s; 30 s if friendly) or `forceCarrierPullBackTime` (20 s). | The host set the switch directly: no waypoint, no maneuver reset, and the wings did not go for the joiner's target. | **fixed**: the order is given as the game's command, and the player-only part is done by the pilot. |
| 5 | Hold Fire (the game's toggle, `HOLD_FIRE` command, flag `holdFire`). | Not relayed: the joiner's autofire groups kept firing on the host. | **fixed**: new input bit `HOLD_FIRE` (protocol 47). |
| 6 | `CombatEngine` time: vanilla phase cloak / temporal shell / triad shield scripts slow the whole battle (`engine.getTimeMult`) when the *player's* ship uses them ("bullet time"). | On the joiner's machine their own phase would slow their copy of the battle: **handled** (`followHostTime` enforces the host's speed). But: a joiner never gets bullet time, and the host's own phase slows the battle for everyone (the host's speed is sent). | **open: design choice.** Options: keep as is; or neutralise the host's own bullet time too, so all players are equal (the host loses it). Needs a decision. |
| 7 | `Ship.advance` input buffer: a command the player's ship can't carry out yet (shield while overloaded) is kept for `INPUT_BUFFER_DELAY` and done when it can. | None in practice: the pilot repeats the wanted state every frame (shield toggle at most every 0.3 s). | handled |
| 8 | `Ship.advance`: `timeDeployedUnderPlayerControl` counts only the player's ship. Used by `FleetEncounterContext.tallyOfficerTime`. | A joiner's time counts as the ship's officer's time (officer XP), as if the AI flew it. | open (harmless; arguably right). |
| 9 | `BasicShipAI.avoidShipCollisions`: AI frigates don't bother avoiding a lighter frigate, except the player's ship. | Friendly AI frigates may bump a joiner's frigate more than the host's. | open (inside the AI; minor). |
| 10 | Shield raise after overload (`elapsedSinceRaiseShieldsWhileOverloaded`) buffered only for the player. | Same as 7. | handled |
| 11 | `ShipSystem` failure sounds/messages, drone-mode sounds, status icons (`maintainStatusForPlayerShip`), floaties, camera follow. | UI only; the joiner's own copy shows its own. | no action |

## Ship systems (vanilla scripts)

| # | Script | Player's ship | Other ship (a joiner's on the host) | Status |
|---|---|---|---|---|
| 12 | Drone Strike, Entropy Amplifier, Interdictor Array, Acausal Disruptor, Energy Lash | R target, else the ship nearest the cursor (fighters included) within range | Entropy Amplifier, Acausal Disruptor: AI flag `TARGET_FOR_SHIP_SYSTEM`; Drone Strike, Interdictor, Energy Lash: `MANEUVER_TARGET` (range-checked), else the ship nearest the *ship* | **fixed**: `MANEUVER_TARGET` was never set, so a joiner's Interdictor or Energy Lash with no R target hit whatever was nearest their ship, not their cursor. The pilot now sets it to the R target, else the enemy nearest the cursor (any size). Left over: if that one is out of range the game falls back to the nearest to the ship, where the player's version picks the nearest to the cursor in range. |
| 13 | Phase displacer / teleporters (`systems.int`) | destination from the cursor | `targetLoc`, else the mouse target | handled (the cursor is the ship's mouse target on the host). |
| 14 | Neural Link / Neural Interface | transfer to the linked ship | n/a | open: joiners can't use neural link (feature). |
| 15 | Operations Center hullmod | bonus applies only to the player's ship | none | open: a joiner flying an Ops Center ship gives no bonus (fleet-level; host's flagship still does). |
| 16 | Escort Package, Missile Autoloader, Energy Weapon Mastery, B-Deck, Swarm/Fragment weapons | status display; Escort Package updates every frame instead of on an interval | | minor / no action |

## Sync coverage (what could drift)

Synced today: position, velocity, facing, spin, acceleration (derived), hull, CR, flux/hard flux, shield facing,
defense/overload/vent/system/phase flags and levels, firing bits and shot counts, weapon angles, 43 ship stats
(speed, turn, flux, rate of fire, ranges, time multiplier, recoil...), armor cells, disabled weapons and engines,
flame-out, ammo, missiles (position, velocity, flight time, fizzling), damage events, time multiplier, pause.

Not synced / worth watching:
- **Hold-fire and fighter orders**: now relayed (4, 5).
- **Mod scripts that branch on `getPlayerShip()`** run differently on host and joiner by design (the joiner's
  ship is the player ship only on the joiner's machine). Each mod needs a look when it misbehaves; UAF (Isenoria
  Manual Override) and Armaa are handled case by case.
- **Randomness** (`Math.random` in systems such as displacer random range, weapon spread): only the host's
  result counts; joiners see the host's projectiles and positions, so no drift beyond one snapshot.

## What changed in this build

- 1: `RemotePilotAI.advance` flies every time-step; `flyFromHost` replaces the host's direct call.
- 4: `RemotePilotAI.followFighterOrder` gives `PULL_BACK_FIGHTERS` and sets the carrier target/pull-back time.
- 5: `InputState.HOLD_FIRE` (bit 32768); client sends `isHoldFire()`; pilot and `keepPilots` apply it.
- 12: `answerForSystem` also sets `MANEUVER_TARGET`.
- Protocol 46 -> 47.
