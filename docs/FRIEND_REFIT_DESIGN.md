# Friend refit: design

A joiner refits the ship they picked from the host's fleet, in a refit screen of Wingman's own in the lobby. The
host's game checks the request and asks the host, in the host's own refit screen, to accept or deny it. Only an
accepted request changes anything in the host's campaign.

**Status:** all five stages are built; `docs/TECHNICAL.md` 6.8 says how. Where the build differs from this design:

- The joiner's screen is a list of rows (slots, bays, vents, capacitors, hull mods) with the choices for the marked
  row beside it, keyboard and mouse; the ship picture shows the new loadout but its slots aren't clickable.
- The host's FRIEND REFITS button is drawn at the top right over whatever is showing (the dialog, or a core screen),
  and opens Wingman's own panel. Accepted with the refit screen open, a refit is **done the moment that screen
  closes** (its own copy of the ship would undo it otherwise), and the screen then opens again on that ship from the
  market's dialog (setting `friendRefitReopen`).
- Ctrl+Shift+R opens the panel (instead of a list on the settings page). Deny gives no typed reason.
- Hull mods' `isApplicableToShip` isn't checked at all (it needs a ship that exists only in combat or the refit
  screen); the panel tells the host to judge it.
- A hull mod that adds fighter bays gives its bays only once accepted; modules' loadouts aren't refitted.
- Prices: base value × (1 + tariff) to buy and × (1 − tariff) to sell, rounded. To check against the game's own
  market screen in play.

---

## 1. When a friend can refit

- **The host is docked at a market**, inside its interaction dialog (the same moment the game lets the host open
  the market and refit tabs): a real market with submarkets, where docking wasn't refused (trade mode not `NONE`). The host's game detects this and tells
  joiners that refitting is available. Nothing opens on its own: joiners get a **Refit** button (see 3) and
  open the refit screen only if they want to. When the host undocks, the button goes away; a refit screen a
  joiner has open closes with a short notice ("The host left the market"), and a pending request is
  withdrawn.
- **The friend has picked the ship** in the lobby (only their own pick can be refitted), it isn't the host's
  flagship, and it isn't mothballed.
- **Host setting `friendRefit`**: off / on (default on). With it off, the refit button never shows for joiners.
- Between battles only. Nothing changes while a battle runs.

## 2. What the joiner's game is told (host -> joiner)

All new, all built only from the host's own campaign, through the game's API and a few listeners of Wingman's
own (no dependency on other mods). Sent when a joiner opens the refit screen and again whenever something in it changes (a
purchase, the cargo, the credits); about 1 to 20 KB.

| Data | From |
|---|---|
| **Market stock**: each submarket the host can use (open market, military, black market; not storage), with its name, tariff, and its weapons and fighter LPCs (id, count, price per unit with tariff). Submarkets the host can't use (`isEnabled` false, for example military without the commission or standing) are listed greyed out with the reason. | `market.getSubmarketsCopy()`, `SubmarketPlugin.getTariff()`, `isEnabled(...)`, `getCargo()`, the item specs' base values |
| **Storage** at that market (free transfer), if the host has it. | same |
| **Host's cargo**: weapons and fighter LPCs with counts. | `playerFleet.getCargo()` |
| **Credits** and **story points**. | `getCargo().getCredits()`, `getPlayerStats().getStoryPoints()` |
| **Hull mods the host knows**. | `getCharacterData().getHullMods()` |
| **For the picked ship**: its full variant (slots, weapons, wings, hull mods, S-mods, built-ins, vents, caps), its **ordnance points with the host's skills**, its **vent and capacitor maxima with the host's skills**, and its **maximum S-mods** (all worked out on the host, per ship, so mods that change any of these are already counted). | `hullSpec.getOrdnancePoints(stats)`, `variant.computeOPCost(stats)`, `SkillsChangeRemoveVentsCapsEffect.getMaxVents/getMaxCaps(size, stats)`, `Misc.getMaxPermanentMods(member, stats)` |
| **Legality**: items a submarket won't sell or buy (`isIllegalOnSubmarket`), shown as such. | submarket plugin |

## 3. The joiner's refit screen

Opened with a **Refit** button on the lobby's ship card (also the R key), which shows only while refitting is
available and the joiner has picked that ship; otherwise it isn't drawn at all. A small "Refit available at
<market>" line in the lobby's header points to it. It imitates the game's
refit screen with what is needed and nothing more:

- **The ship picture with its slots** (Wingman already draws weapons in their slots from the hull files). Each
  slot is clickable; the selected slot shows its size, type and mount.
- **A weapon list for the selected slot**: only what fits it (size, type), from the host's cargo, storage and
  each usable market, with the source, count and price. Taking one from cargo or storage is free; from a market
  it costs its price. Removing a weapon sends it to the host's cargo, or sells it to the market (option per
  removed item; the market's buy price, with tariff).
- **Fighter bays**: the same, with fighter LPCs.
- **Hull mods**: the ones the host knows, those that fit the hull (see 5), with their OP cost for this hull
  size. S-mods can be added: "build in" a hull mod for **1 story point each**, up to the ship's maximum
  S-mods; hull mods tagged `no_build_in` can't be. Existing S-mods and built-ins are fixed.
- **Vents and capacitors**: +/- up to the host's maxima.
- **Running totals**: OP used / OP available, credits to spend and to receive, story points to spend,
  S-mods used / allowed. Anything over a limit is shown in red and blocks sending.
- **Send to host** (with a short note box), **Reset**, **Close**. One request per player at a time; sending a new
  one replaces the old.

The screen is Wingman's own drawing, like the lobby, keyboard and mouse. It never touches the game's refit code.

## 4. The request (joiner -> host)

The ship's member id, and the **target loadout**: weapons by slot, wings by bay, hull mods to add and remove,
S-mods to build in, vents, capacitors; for each new item where it comes from (cargo, storage, a submarket) and,
for each removed item, where it goes (cargo or sold to which submarket). Plus the totals the joiner saw (cost,
story points), so the host can see if anything changed meanwhile.

## 5. Checks on the host (all again, on the real campaign)

When the request arrives, and again at the moment the host accepts:

1. Refitting is still available (docked at the same market), the friend still has that ship picked, the ship is
   still in the fleet and not the flagship.
2. Every slot exists and the weapon fits it (size, type, mount), read from the hull spec as the game does.
3. Every item is still where the request says, in the needed number (cargo, storage, the submarket's cargo),
   and still legal there.
4. The new loadout's OP cost with the host's skills (`variant.computeOPCost(stats)`) is within the ship's OP.
5. Vents and capacitors within the maxima.
6. Hull mods: known by the host; not hidden; for each one added, its own `isApplicableToShip` on a test copy of
   the ship where it can be made without a battle (see "Risks"); `no_build_in` respected for S-mods; S-mods
   within `Misc.getMaxPermanentMods(member, stats)`.
7. Credits and story points enough for it all.

A request that fails any check is refused with the reason, and the joiner's screen shows it.

## 6. The host's side

- A short notice on the campaign map: "Kyrizion sent a refit for ISS Child of Venus." The host decides when to
  look at it.
- **In the host's own refit screen**, a **"Friend refits (1)"** button, drawn by Wingman over the screen (top
  right of it) while that screen is open. It opens a small panel listing the pending requests: who, which ship,
  the changes (weapons in and out, hull mods, S-mods, vents/caps), the cost in credits and story points, and the
  checks' result. **Accept** or **Deny** for each.
- **Accept** applies it exactly as the game's refit screen would: changes the member's variant (weapons, wings,
  hull mods, S-mods with `addPermaMod(id, true)`, vents, caps), moves items between the ship and cargo/storage,
  buys from and sells to submarkets (credits moved, the submarket's cargo changed, and the transaction reported
  to the submarket so prices and reputation react as for any purchase), spends story points (with the usual
  bonus XP for building in, `Misc.getBuildInBonusXP`), then refreshes the refit screen so it shows the new
  loadout (the screen is reopened on the same ship if it was showing it). The joiner is told it was accepted
  and the lobby shows the new loadout.
- **Deny** tells the joiner, optionally with a one-line reason.
- Requests also stay listed on the campaign map's settings page (Ctrl+Shift+O) in case the host would rather not
  open the refit screen.

## 7. Messages and versions

New messages (protocol bump): availability travels in the lobby's ROSTER (the market's name, "" when refitting
isn't possible) rather than in a message of its own; REFIT_ASK (joiner -> host: the member whose refit screen the
joiner opened, "" when closed; built), REFIT_DATA (the data in 2, sent only to a joiner with the refit screen
open, on asking and on each change; built),
REFIT_REQUEST (joiner -> host), REFIT_RESULT (host -> joiner: accepted / denied / refused, with reason). Each
decoded with the usual size limits and checked by the fuzz test.

## 8. Risks and open points

- **Drawing a button into the host's refit screen.** The game has no API for adding to that screen. Wingman would
  draw its own button over it and read the mouse itself, as LunaLib does on the title screen. This has to be
  tried first: if the game doesn't let a mod draw over its core screens, the fallback is the notice plus the
  list on the campaign map's settings page (Ctrl+Shift+O), with a key to open it.
- **The refit screen keeps its own copy of the ship.** Applying a change while it shows that ship could be
  undone when it closes. Hence: apply, then reopen the screen on that ship. To be checked in game.
- **Hull mods' own rules.** Most hull mods decide if they fit by looking at a ship, which normally exists only in
  the refit screen. Vanilla and most mods' rules can be checked on a test copy; a few mods' may give wrong
  answers. Those would show as "can't check: the host decides" rather than block.
- **Mods' own refit features** (armaa's weapon swaps, Special Hullmod Upgrades' item installers, Ship Mastery's
  refit options) aren't part of this; the host does those.
- **Market rules beyond prices.** Selling illegal goods on an open market, commission-only weapons, and black
  market "sold to" reputation effects follow the submarket plugin's own rules where they are exposed; anything the
  API doesn't expose is simply not offered.
- **Cost of building it**: the largest single feature since the lobby. Suggested order: (1) availability, the Refit button and the data,
  (2) joiner screen with weapons/wings/vents/caps from cargo only, (3) host button, checks and apply,
  (4) market buy/sell, (5) hull mods and S-mods.
