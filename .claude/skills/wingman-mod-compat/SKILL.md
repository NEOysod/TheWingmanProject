---
name: wingman-mod-compat
description: Make another Starsector mod's ships, hull mods, weapons, systems or scripts work correctly under Wingman multiplayer (friend refit, lobby, combat replication), through the wingman.compat package. Use whenever a modded feature misbehaves for joining players or on the host because it relies on refit-screen side effects, hidden hull mods, a live ShipAPI, player-only checks, or local-only state — e.g. Diable Avionics' Virtuous loadout switching — even if the user only names the other mod.
---

# Wingman mod compatibility

Compatibility code adapts Wingman to another mod's behaviour; it never changes the other mod. Load `wingman-multiplayer` (which loads `starsector-knowledge`) and follow its authority, protocol and verification rules — this skill only adds the compat-specific workflow.

## 1. Read the other mod's mechanism, not its description

1. Find the mod: `D:\Starsector\mods\<name>\mod_info.json` (its `id`), then the classes behind the feature. Hull mod scripts are in `data/hullmods/hull_mods.csv` (`script` column); systems in `ship_systems.csv`; hulls/skins in `data/hulls`.
2. Read the source if shipped (`src/`), else stop and say the jar would need decompiling — ask before doing it.
3. Write down exactly what it does and **when**: which callback (`applyEffectsBeforeShipCreation`, `applyEffectsAfterShipCreation`, `advanceInCampaign`, a campaign script, a listener), which ids/slots/tags, and every precondition. Watch especially for:
   - `stats.getEntity()` / `ship` being a live `ShipAPI` (absent for a fleet member at Wingman's host refit).
   - Hidden hull mods used as UI controls (`hidden`/`hiddenEverywhere` in the CSV) — Wingman's joiner refit hides them.
   - Mutating `stats.getVariant()` / `ship.getVariant()` (shared instance; Wingman works on clones).
   - Player-only checks (`originalOwner`, `Global.getSector().getPlayerFleet()`, player cargo) that mean "the host" under Wingman, not the joiner.
   - Random choices, real-time timers or local memory that would differ between host and client.
4. Record cycle orders, id lists and mappings verbatim from the source, with the file they came from.

## 2. Trace where Wingman breaks it

Use the `wingman-multiplayer` path template: originating UI → message → host validation → mutation → replication → presentation. Name the exact stage that diverges (e.g. "joiner UI never offers it", "host accept never triggers it", "client combat recomputes it differently"). Typical seams:

| Symptom | Seam |
|---|---|
| Joiner can't see/choose a modded refit control | `RefitScreen` row building / side panel |
| Host approval misses a side effect the vanilla refit screen would trigger | `FriendRefit.applyTo` (runs on the check copy **and** the accepted variant) |
| Validation rejects or miscounts it | `FriendRefit.check`, `RefitPlan.work` |
| Ship differs in combat between peers | spawn/snapshot paths; prefer making the campaign variant final on the host before battle |
| Mod needs to talk between peers | `WingmanAPI.listen/send` on a namespaced channel — not a new raw `Msg` |

## 3. Implement in `wingman.compat`

- `src/wingman/compat/ModCompat.java` is the only entry point Wingman core calls. Add a static method only for a seam a mod actually needs, and delegate to the per-mod class.
- One package-private class per mod (`DiableAvionics.java` is the model): constants copied from the mod with a comment naming the source class, plus static functions on API types.
- Gate on the mod's own ids (hull/base hull id, hull mod id prefix) so the code is inert when the mod isn't loaded and needs no `ModManager` lookup or hard dependency. Never `import` the other mod's classes; Wingman must load without it. Use `Global.getSettings().getModManager().isModEnabled(id)` only when no id gate exists.
- Reproduce the mod's effect **deterministically on the host**, on the variant, in the same order the mod uses, and make it idempotent: once the result is in place the mod's own code must see nothing left to do (e.g. the next selector hull mod is present), so it can't apply twice.
- Keep host authority: the joiner UI may reveal or describe an option (`ModCompat.switchNote`), but the host decides and mutates. Unknown ids, missing specs and odd states must leave the ship unchanged, not throw — any uncaught exception is fatal in Starsector.
- No public API, config switch or interface for a single mod. No PatchLib unless there's genuinely no API seam; it is a hard dependency — ask first.
- Comments: say what the other mod does and why Wingman must mirror it, in the surrounding code's style.

## 4. Test and report

- Add `test/wingman/compat/<Mod>Test.java`: an executable main class (the repo's convention) using `java.lang.reflect.Proxy` stand-ins for `ShipVariantAPI`, `ShipHullSpecAPI`, `SettingsAPI` (`Global.setSettings`). Cover each step of every cycle, wrap-around, the "already settled → untouched" case, other ships untouched, and unknown ids.
- Build: `gradle jar testClasses` from the repo root. Run tests with the JDK that compiled them (`java` on PATH if class version errors appear) and the classpath `build/classes/java/test;build/classes/java/main;../../starsector-core/*.jar;jars/Wingman.jar`; add `-Djava.library.path=../../starsector-core/native/windows` for tests touching LWJGL. Re-run the existing refit tests that cover any seam you touched.
- Report: the other mod's version and the mechanism you mirrored (source path), changed Wingman sides, whether the protocol changed (it normally shouldn't), tests run, rules of the other mod you did *not* enforce (e.g. a `req_spaceport` tag), and the in-game two-PC checks still needed.

## Existing adapters

| Mod (id, version read) | Feature | Seams |
|---|---|---|
| Diable Avionics (`diableavionics`, 2.9.5.3) | Virtuous loadout selectors: hull config/system, left/right kit, head (`DiableAvionicsVirtuous_system`) | `ModCompat.switchNote` → `RefitScreen` and `FriendRefit.loadoutSwitch` (selector-only refits sent at once, no host approval); `ModCompat.afterHullmods` → `FriendRefit.applyTo` |
| Exotica Technologies (`exoticatechnologies`, 1.8.7) | EXOTICA button in the joiner refit: Exotica's own ship-mod menu on a mirror of the host ship/hold/market; recorded purchases replayed on the host through Exotica's `UpgradeMethod`/`ExoticMethod` | `ModCompat.exoticaEnabled/Open/Frame/Input/Render/Close/Ship/Replay` → `ExoticaRefit` → `RefitScreen`, `WingmanTitlePlugin`, `CampaignLobby.advance` |
| Secrets of the Frontier (`secretsofthefrontier`, 0.15.1) | Fel's invasion (`SotfFelInvasionPlugin`): a joiner's battle is a mission, where the plugin does nothing, so the host names the infested ship (net id, member id, guilt, prior invasion count, Fel with her skills) on channel `secretsofthefrontier:fel`; the joiner runs SotF's own `createFelInfestationPlugin` on its copy, keeps it a `PuppetAI` puppet, and drops it if SotF's code throws | `ModCompat.felNews/felState` → `WingmanHostPlugin.step` (broadcast; per-player sync); `ModCompat.felListen/felWanted/felShow` → `WingmanClientPlugin.prepare/step`; Fel and the invasion count via `CampaignStandIn.importantPerson/sectorValue` |

Add a row whenever you add an adapter.
