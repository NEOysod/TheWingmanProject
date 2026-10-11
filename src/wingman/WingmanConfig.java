package wingman;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;

/**
 * Settings: read from wingman_settings.json in the mod folder, then whatever was changed in the in-game settings
 * page (see Settings) is laid over that. Re-read at the start of every battle.
 */
public final class WingmanConfig {
    public static final String MOD_ID = "wingman_project";

    public String playerName = "Player";
    public boolean hostEnabled = true;
    public String hostAddress = "127.0.0.1";
    public int port = 24816;
    public int maxPlayers = 4;
    public int snapshotRate = 30;
    /** Host: send every ship, fighter and missile in every update, whether or not it has done anything new. */
    public boolean sendEverything = true;
    /** Host: wrecks stay whole (no break-ups), so every screen shows the same wrecks. */
    public boolean wholeWrecks = true;
    /** Joiner: other ships are shown as the host's (as best known) plus a fading offset, instead of corrected beyond a tolerance (see Smoother). */
    public boolean smoothCorrections = true;
    /** Joiner: other ships are shown a little in the past, between updates that have arrived (see Smoother). For uneven connections. */
    public boolean jitterBuffer = false;
    /** Host: joiners' controls are sent with copies and played back at an even pace (see InputBuffer). For lossy or uneven connections. */
    public boolean steadyControls = false;
    /** Host: most a joiner's position updates may take, KB/s; 0 = no limit (see Budget). */
    public int bandwidthLimit = 0;
    /** Joiner: the flux bar and ammo of the ship in your hands answer at once, kept in step with the host (needs predictOwnShip). */
    public boolean hideLatency = true;
    /** Host: send each position update as what changed since the one before (about a third of the size). */
    public boolean compactUpdates = true;
    /** Compress the whole connection. Each side compresses what it sends if the other can read it. */
    public boolean compressLink = true;
    /** Send position updates and controls by UDP where it gets through (TCP otherwise, and for everything else). */
    public boolean useUdp = true;
    /** Joiner: the ship you fly moves at once under your own controls, and is kept in step with the host's. */
    public boolean predictOwnShip = true;
    /** Host: open the co-op session on the campaign map, so friends can connect and pick ships before a battle. */
    public boolean campaignLobby = true;
    /** Joiner: while connected on the title screen, go into the host's battles as they begin, without being asked. */
    public boolean autoJoin = true;
    /** Joiner: at the start of a battle, sit down in the ship flown last time if it is there and free. */
    public boolean rememberShip = true;
    /** P1, P2... over the ship each player flies, in their colour. */
    public boolean playerTags = true;
    /** A thin diamond in the player's colour around each player's ship (with the tags). */
    public boolean playerOutlines = true;
    /** Host: also show this PC's addresses in the battle's message feed (they always go to the log). */
    public boolean announceAddresses = false;
    /** Joiner: how much difference from the host's positions is left alone. 1 = normal, 0 = none. */
    public float syncTolerance = 1f;
    public boolean readyCheck = true;
    /** "content": refuse joiners whose non-utility mods differ; "all": every mod must match; "off": no check. */
    public String modCheck = "content";
    /** Write the detailed diagnostic lines to starsector.log (for bug reports). */
    public boolean debugLog = false; // Release builds: off. (Test builds turn it on here and in wingman_settings.json.)
    /** Joiner: shots from guns with an on-hit script only hit when and where the host says. */
    public boolean hostDecidesScriptedShots = true;
    /**
     * Joiner: how hits by shots with an on-hit script are settled. "match": this PC's own shots hit by
     * themselves, and a hit the host reports is only played out here if this PC did not make the same hit at
     * about the same moment. "host": only the host's hits count. "local": each PC judges for itself.
     */
    public String scriptedHits = "match";
    /** Joiner: how far apart in time (milliseconds) this PC's hit and the host's may be and still be the same hit. */
    public int matchWindowMs = 100;
    /** Host: enemies nobody on the players' side can see are only reported now and then. */
    public boolean trimUnseen = true;
    /** Keep each joiner copy's ship stats (speed, turn rate, ranges, rate of fire...) equal to the real ship's. */
    public boolean syncStats = true;
    /** Copy the simple values scripts keep on ships (flags, counters, short text) from the host's ships to the joiner's. */
    public boolean syncScriptMemory = true;
    /** Joiner: give mods' battle scripts a stand-in campaign and stand-in fleets copied from the host's (see CampaignStandIn). */
    public boolean campaignStandIn = true;
    /** Scripts' lines in the combat message feed come from the host's game (see MessageTap). */
    public boolean hostMessages = true;
    /** Joiner: the host's fighters go in the hangars of the copy of their carrier (see BayLink). */
    public boolean fighterHangars = true;
    /** MagicLib subsystems on joiners' ships follow the joiner's keys (see Subsystems). */
    public boolean joinerSubsystems = true;
    /** Report damage as it happens, and play out on a joiner any that its own game did not produce. */
    public boolean damageReports = true;
    /** Joiner: fire each weapon as many times as the host's fired, not for as long as it "is firing". */
    public boolean shotCounts = true;
    /** Host: joiners must type this to get in. Empty = anyone who can reach the port may join. */
    public String password = "";
    /** Host: the largest ship a joiner may fly: FRIGATE, DESTROYER, CRUISER or CAPITAL_SHIP. */
    public String maxShipSize = "CAPITAL_SHIP";
    /** Joiner: ship systems (by id) that are never switched on from the host's state on this PC. */
    public java.util.List<String> neverMirrorSystems = new java.util.ArrayList<String>();
    /** Joiner: the password typed into the join form. */
    public String joinPassword = "";

    public static WingmanConfig load() {
        WingmanConfig c = fromFile();
        // What was changed in the in-game settings page wins over the file.
        try {
            for (java.util.Map.Entry<String, String> e : Settings.loadOverrides().entrySet()) c.set(e.getKey(), e.getValue());
        } catch (Throwable t) { /* the file's values stand */ }
        // What was typed into the join screen wins over both.
        try {
            SavedJoin saved = SavedJoin.load();
            if (!saved.address.isEmpty()) {
                c.port = SavedJoin.portOf(saved.address, c.port);
                c.hostAddress = SavedJoin.hostOf(saved.address);
            }
            if (!saved.name.isEmpty()) c.playerName = saved.name;
            c.joinPassword = saved.password;
        } catch (Throwable t) { /* the file's values stand */ }
        c.tidy(true);
        // The connection is made from more than one place (title screen, join mission): it reads these.
        wingman.net.Conn.compression = c.compressLink;
        wingman.net.Conn.udp = c.useUdp;
        return c;
    }

    /** The settings as wingman_settings.json alone gives them. */
    static WingmanConfig fromFile() {
        WingmanConfig c = new WingmanConfig();
        try {
            JSONObject j = Global.getSettings().loadJSON("wingman_settings.json", MOD_ID);
            c.playerName = j.optString("playerName", c.playerName);
            c.hostEnabled = j.optBoolean("hostEnabled", c.hostEnabled);
            c.hostAddress = j.optString("hostAddress", c.hostAddress);
            c.port = j.optInt("port", c.port);
            c.maxPlayers = j.optInt("maxPlayers", c.maxPlayers);
            c.snapshotRate = j.optInt("snapshotRate", c.snapshotRate);
            c.readyCheck = j.optBoolean("readyCheck", c.readyCheck);
            c.modCheck = j.optString("modCheck", c.modCheck).trim().toLowerCase();
            c.debugLog = j.optBoolean("debugLog", c.debugLog);
            c.hostDecidesScriptedShots = j.optBoolean("hostDecidesScriptedShots", c.hostDecidesScriptedShots);
            // (the older yes/no setting still means what it did: false = each PC judges for itself)
            c.scriptedHits = j.optString("scriptedHits", c.hostDecidesScriptedShots ? c.scriptedHits : "local").trim().toLowerCase();
            c.matchWindowMs = j.optInt("matchWindowMs", c.matchWindowMs);
            c.predictOwnShip = j.optBoolean("predictOwnShip", c.predictOwnShip);
            c.autoJoin = j.optBoolean("autoJoin", c.autoJoin);
            c.campaignLobby = j.optBoolean("campaignLobby", c.campaignLobby);
            c.rememberShip = j.optBoolean("rememberShip", c.rememberShip);
            c.playerTags = j.optBoolean("playerTags", c.playerTags);
            c.playerOutlines = j.optBoolean("playerOutlines", c.playerOutlines);
            c.announceAddresses = j.optBoolean("announceAddresses", c.announceAddresses);
            c.syncTolerance = (float) j.optDouble("syncTolerance", c.syncTolerance);
            c.sendEverything = j.optBoolean("sendEverything", c.sendEverything);
            c.wholeWrecks = j.optBoolean("wholeWrecks", c.wholeWrecks);
            c.smoothCorrections = j.optBoolean("smoothCorrections", c.smoothCorrections);
            c.jitterBuffer = j.optBoolean("jitterBuffer", c.jitterBuffer);
            c.steadyControls = j.optBoolean("steadyControls", c.steadyControls);
            c.bandwidthLimit = j.optInt("bandwidthLimit", c.bandwidthLimit);
            c.hideLatency = j.optBoolean("hideLatency", c.hideLatency);
            c.compactUpdates = j.optBoolean("compactUpdates", c.compactUpdates);
            c.compressLink = j.optBoolean("compressLink", c.compressLink);
            c.useUdp = j.optBoolean("useUdp", c.useUdp);
            c.syncStats = j.optBoolean("syncStats", c.syncStats);
            c.syncScriptMemory = j.optBoolean("syncScriptMemory", c.syncScriptMemory);
            c.campaignStandIn = j.optBoolean("campaignStandIn", c.campaignStandIn);
            c.hostMessages = j.optBoolean("hostMessages", c.hostMessages);
            c.fighterHangars = j.optBoolean("fighterHangars", c.fighterHangars);
            c.joinerSubsystems = j.optBoolean("joinerSubsystems", c.joinerSubsystems);
            c.damageReports = j.optBoolean("damageReports", c.damageReports);
            c.shotCounts = j.optBoolean("shotCounts", c.shotCounts);
            c.trimUnseen = j.optBoolean("trimUnseen", c.trimUnseen);
            c.password = j.optString("password", c.password).trim();
            c.maxShipSize = j.optString("maxShipSize", c.maxShipSize).trim().toUpperCase();
            org.json.JSONArray never = j.optJSONArray("neverMirrorSystems");
            for (int i = 0; never != null && i < never.length(); i++) c.neverMirrorSystems.add(never.optString(i, "").trim());
        } catch (Exception e) {
            Global.getLogger(WingmanConfig.class).warn("Wingman: could not read wingman_settings.json, using defaults", e);
        }
        c.tidy(false);
        return c;
    }

    /** One setting as text, by its name in wingman_settings.json. Null for a name this does not know. */
    String get(String key) {
        if ("hostEnabled".equals(key)) return String.valueOf(hostEnabled);
        if ("port".equals(key)) return String.valueOf(port);
        if ("maxPlayers".equals(key)) return String.valueOf(maxPlayers);
        if ("snapshotRate".equals(key)) return String.valueOf(snapshotRate);
        if ("sendEverything".equals(key)) return String.valueOf(sendEverything);
        if ("wholeWrecks".equals(key)) return String.valueOf(wholeWrecks);
        if ("smoothCorrections".equals(key)) return String.valueOf(smoothCorrections);
        if ("jitterBuffer".equals(key)) return String.valueOf(jitterBuffer);
        if ("steadyControls".equals(key)) return String.valueOf(steadyControls);
        if ("hideLatency".equals(key)) return String.valueOf(hideLatency);
        if ("bandwidthLimit".equals(key)) return bandwidthLimit <= 0 ? "off" : String.valueOf(bandwidthLimit);
        if ("compactUpdates".equals(key)) return String.valueOf(compactUpdates);
        if ("compressLink".equals(key)) return String.valueOf(compressLink);
        if ("useUdp".equals(key)) return String.valueOf(useUdp);
        if ("predictOwnShip".equals(key)) return String.valueOf(predictOwnShip);
        if ("autoJoin".equals(key)) return String.valueOf(autoJoin);
        if ("campaignLobby".equals(key)) return String.valueOf(campaignLobby);
        if ("rememberShip".equals(key)) return String.valueOf(rememberShip);
        if ("playerTags".equals(key)) return String.valueOf(playerTags);
        if ("playerOutlines".equals(key)) return String.valueOf(playerOutlines);
        if ("announceAddresses".equals(key)) return String.valueOf(announceAddresses);
        if ("syncTolerance".equals(key)) return trim(syncTolerance);
        if ("readyCheck".equals(key)) return String.valueOf(readyCheck);
        if ("modCheck".equals(key)) return modCheck;
        if ("debugLog".equals(key)) return String.valueOf(debugLog);
        if ("scriptedHits".equals(key)) return scriptedHits;
        if ("matchWindowMs".equals(key)) return String.valueOf(matchWindowMs);
        if ("trimUnseen".equals(key)) return String.valueOf(trimUnseen);
        if ("syncStats".equals(key)) return String.valueOf(syncStats);
        if ("syncScriptMemory".equals(key)) return String.valueOf(syncScriptMemory);
        if ("campaignStandIn".equals(key)) return String.valueOf(campaignStandIn);
        if ("hostMessages".equals(key)) return String.valueOf(hostMessages);
        if ("fighterHangars".equals(key)) return String.valueOf(fighterHangars);
        if ("joinerSubsystems".equals(key)) return String.valueOf(joinerSubsystems);
        if ("damageReports".equals(key)) return String.valueOf(damageReports);
        if ("shotCounts".equals(key)) return String.valueOf(shotCounts);
        if ("password".equals(key)) return password;
        if ("maxShipSize".equals(key)) return maxShipSize;
        return null;
    }

    private static String trim(float f) {
        return f == Math.round(f) ? String.valueOf(Math.round(f)) : String.valueOf(f);
    }

    /** Set one setting from text. A name this does not know, or a value that makes no sense, changes nothing. */
    void set(String key, String value) {
        if (key == null || value == null) return;
        value = value.trim();
        try {
            if ("hostEnabled".equals(key)) hostEnabled = yes(value);
            else if ("port".equals(key)) port = Integer.parseInt(value);
            else if ("maxPlayers".equals(key)) maxPlayers = Integer.parseInt(value);
            else if ("snapshotRate".equals(key)) snapshotRate = Integer.parseInt(value);
            else if ("sendEverything".equals(key)) sendEverything = yes(value);
            else if ("wholeWrecks".equals(key)) wholeWrecks = yes(value);
            else if ("smoothCorrections".equals(key)) smoothCorrections = yes(value);
            else if ("jitterBuffer".equals(key)) jitterBuffer = yes(value);
            else if ("steadyControls".equals(key)) steadyControls = yes(value);
            else if ("hideLatency".equals(key)) hideLatency = yes(value);
            else if ("bandwidthLimit".equals(key)) bandwidthLimit = "off".equals(value) ? 0 : Integer.parseInt(value);
            else if ("compactUpdates".equals(key)) compactUpdates = yes(value);
            else if ("compressLink".equals(key)) compressLink = yes(value);
            else if ("useUdp".equals(key)) useUdp = yes(value);
            else if ("predictOwnShip".equals(key)) predictOwnShip = yes(value);
            else if ("autoJoin".equals(key)) autoJoin = yes(value);
            else if ("campaignLobby".equals(key)) campaignLobby = yes(value);
            else if ("rememberShip".equals(key)) rememberShip = yes(value);
            else if ("playerTags".equals(key)) playerTags = yes(value);
            else if ("playerOutlines".equals(key)) playerOutlines = yes(value);
            else if ("announceAddresses".equals(key)) announceAddresses = yes(value);
            else if ("syncTolerance".equals(key)) syncTolerance = Float.parseFloat(value);
            else if ("readyCheck".equals(key)) readyCheck = yes(value);
            else if ("modCheck".equals(key)) modCheck = value.toLowerCase();
            else if ("debugLog".equals(key)) debugLog = yes(value);
            else if ("scriptedHits".equals(key)) scriptedHits = value.toLowerCase();
            else if ("matchWindowMs".equals(key)) matchWindowMs = Integer.parseInt(value);
            else if ("trimUnseen".equals(key)) trimUnseen = yes(value);
            else if ("syncStats".equals(key)) syncStats = yes(value);
            else if ("syncScriptMemory".equals(key)) syncScriptMemory = yes(value);
            else if ("campaignStandIn".equals(key)) campaignStandIn = yes(value);
            else if ("hostMessages".equals(key)) hostMessages = yes(value);
            else if ("fighterHangars".equals(key)) fighterHangars = yes(value);
            else if ("joinerSubsystems".equals(key)) joinerSubsystems = yes(value);
            else if ("damageReports".equals(key)) damageReports = yes(value);
            else if ("shotCounts".equals(key)) shotCounts = yes(value);
            else if ("password".equals(key)) password = value;
            else if ("maxShipSize".equals(key)) maxShipSize = value.toUpperCase();
        } catch (NumberFormatException e) { /* not a number: the old value stands */ }
    }

    private static boolean yes(String v) { return "true".equalsIgnoreCase(v); }

    /** True if this PC's own shots with an on-hit script are left to hit by themselves and then compared with the host's. */
    boolean matchHits() { return "match".equals(scriptedHits); }

    /** Put every value inside its limits. Called after loading and after every change made in the settings page. */
    void tidy(boolean warn) {
        WingmanConfig c = this;
        if (!"match".equals(c.scriptedHits) && !"host".equals(c.scriptedHits) && !"local".equals(c.scriptedHits)) c.scriptedHits = "match";
        c.hostDecidesScriptedShots = !"local".equals(c.scriptedHits);
        c.matchWindowMs = Math.max(0, Math.min(500, c.matchWindowMs));
        if (!"content".equals(c.modCheck) && !"all".equals(c.modCheck) && !"off".equals(c.modCheck)) c.modCheck = "content";
        Dbg.on = c.debugLog;
        if (!java.util.Arrays.asList("FRIGATE", "DESTROYER", "CRUISER", "CAPITAL_SHIP").contains(c.maxShipSize)) {
            if (warn) Global.getLogger(WingmanConfig.class).warn("Wingman: \"maxShipSize\" in wingman_settings.json is '" + c.maxShipSize
                    + "', which is not FRIGATE, DESTROYER, CRUISER or CAPITAL_SHIP; no limit is applied");
            c.maxShipSize = "CAPITAL_SHIP";
        }
        if (c.password.length() > 32) c.password = c.password.substring(0, 32); // the join form takes 32
        if (!(c.syncTolerance >= 0f)) c.syncTolerance = 1f;
        c.syncTolerance = Math.min(4f, c.syncTolerance);
        c.maxPlayers = Math.max(2, Math.min(16, c.maxPlayers));
        c.snapshotRate = Math.max(5, Math.min(60, c.snapshotRate));
        if (c.port < 1024 || c.port > 65535) c.port = 24816;
    }
}
