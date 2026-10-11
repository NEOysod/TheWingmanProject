# The Wingman Project: API for other mods

`wingman.api.WingmanAPI` (in `jars/Wingman.jar`) is what other mods may use of Wingman. It is small on purpose:
who is flying what, a way for a mod to talk to its own copies on the other PCs, and running the host's session
outside a battle. Everything is static, safe to call when nobody is connected, and meant for the game thread.
`API_VERSION` is 1.

## Working with or without Wingman

List Wingman as an optional dependency (or not at all), and only touch `WingmanAPI` behind a check:

```java
if (Global.getSettings().getModManager().isModEnabled("wingman_project")) {
    MyWingmanBits.setUp(); // the only class of yours that mentions WingmanAPI
}
```

Java loads a class when it is first used, so as long as the class that mentions `WingmanAPI` is only touched
when Wingman is enabled, your mod works without it.

## Who flies what

In a co-op battle, a ship a joiner flies is an ordinary AI ship on the host and on the other joiners' PCs, and is
the player's ship only on that joiner's own PC. Scripts that do something special for `engine.getPlayerShip()`
(a status display, a hotkey, a sound) may want to treat those ships as player ships too, or leave them alone.

Wingman writes it on the ships themselves, so you don't even need to call the API:

| Ship custom data | Value | Where |
|---|---|---|
| `wingman_pilot` | the person's name (String) | on every PC, on every ship a person flies, the host's own included |
| `wingman_remote` | `Boolean.TRUE` | present when that person is on another PC |

Or through the API:

```java
WingmanAPI.pilotOf(ship)          // the name, or null if the AI flies it
WingmanAPI.isFlownByPlayer(ship)  // a person flies it, here or elsewhere
WingmanAPI.isFlownRemotely(ship)  // a person on another PC flies it
```

The marks are updated every frame while players are connected, and taken off a ship nobody flies any more.

If the host runs Wingman's optional agent (see `docs/TECHNICAL.md` section 8.9), you may not need any of this:
while a joiner's ship's frame runs on the host (its hullmods, system, weapon scripts, MagicLib subsystems),
`engine.getPlayerShip()` in your code answers that ship, `setPlayerShipExternal` moves that joiner, and the mouse is
theirs. Status lines and time-multiplier changes made there are dropped (the joiner's own game shows them). A
plugin you add from that frame runs for the joiner too if it keeps the ship in a field. Screen-wide plugins that
look at "the player's ship" on their own still see the host's.

## Messages between PCs

A mod can talk to its own copies on the other PCs: the host to every joiner or to one, a joiner to the host.

```java
WingmanAPI.listen("mymod:loot", new WingmanAPI.Listener() {
    public void onMessage(String from, int playerId, byte[] data) { ... }
});
WingmanAPI.send("mymod:loot", bytes);              // host: to every joiner; joiner: to the host
WingmanAPI.sendTo(playerId, "mymod:loot", bytes);  // host: to one joiner
```

- A channel is up to 64 characters; start it with your mod's id. A message is up to 60 KB.
- Messages arrive in order and are not lost while the connection holds. They go on the same connection as
  everything else, so keep them small and few: a few a second is fine, every frame is not.
- Listeners are called on the game thread by `WingmanAPI.deliver()`. Wingman calls it every frame in battles and
  on the title screen. A campaign script calls `WingmanAPI.pump()` (which delivers too).
- A message on a channel nobody listens to is dropped.
- Joiners can only reach the host; the host can pass things on. A joiner may send at most 100 messages a second
  (more are dropped on the host and counted). A listener that throws is logged once and skipped; it doesn't stop
  the others or co-op.
- On the host, Wingman posts on `wingman:joined` and `wingman:left` (empty messages) when a joiner comes or goes,
  with their name and id.

Use `wingman.net.Msg.W` / `Msg.reader(byte[])` to pack and read your bytes if you like (they are what Wingman
itself uses: no `java.io` streams needed, which the game's sandbox refuses).

Who is there:

```java
WingmanAPI.isHosting()      // this PC has a session open
WingmanAPI.isJoined()       // this PC is connected to a host
WingmanAPI.localName()      // this player's name
WingmanAPI.hostName()       // the host's name (on the host: our own)
WingmanAPI.players()        // host: ids of the joiners connected now
WingmanAPI.playerName(id)   // host: a joiner's name (stays the same when they reconnect; the id doesn't)
```

## Hosting outside a battle

Normally the host's session opens when the host's first battle starts. A campaign mod can open it earlier so
friends can connect while the host is on the campaign map:

```java
WingmanAPI.openHost();            // with Wingman's settings: port, password, mod check
List<String> lines = WingmanAPI.pump(); // every frame on the campaign map: lets players come and go,
                                        // and delivers messages. Lines like "Ann joined (2/4)." to show.
```

Battles then use the same session, as usual.

## Ships kept for players

```java
WingmanAPI.reserveShip("Ann", fleetMember.getId()); // null clears it
```

On the host: when Ann is in a battle with nothing to fly, and that fleet member is deployed, Wingman claims it for
her as if she had asked for it, and moves her into it. Once per battle, so she can change ship as usual. The usual
rules apply (not the host's ship, not one someone else flies, the host's size limit). Names are matched ignoring
case. Reservations last until the game is closed: set them again when a save is loaded.

## What is not in the API

Wingman's battle sync itself (ships, positions, damage) is not something other mods can change through this API.
If your mod's battle scripts misbehave in co-op, see `docs/TECHNICAL.md` section 8 for how mod content is handled,
and report it.
