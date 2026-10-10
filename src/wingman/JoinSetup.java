package wingman;

import wingman.net.ClientLink;
import wingman.net.Conn;
import wingman.net.BattleInfo;
import wingman.net.Msg;

/**
 * Runs when the player clicks "Wingman: Join Game" in the mission list, before any battle exists. A map's size
 * and backdrop can only be chosen at that moment, so this connects to the host right away and asks what
 * battlefield it is on. If the host can't be reached or isn't in a battle, a large default map is used.
 */
public final class JoinSetup {
    private JoinSetup() {}

    public static final class Info {
        public boolean found;
        public float width = 40000f, height = 40000f;
        public String background = "";
        public boolean hyperspace;
        public boolean hasPlanet;
        public String planetType = "";
        public float planetRadius, planetYOffset, planetBgWidth = 2048f, planetBgHeight = 2048f;
        public boolean hasGlow;
        public java.awt.Color glow;
        /** The host's objectives (kind, x, y), to be made exactly; empty if not known. */
        public java.util.List<String> objectiveTypes = new java.util.ArrayList<String>();
        public java.util.List<Float> objectiveX = new java.util.ArrayList<Float>(), objectiveY = new java.util.ArrayList<Float>();
        /** One line for the mission briefing. */
        public String status = "";
    }

    /** The vanilla kind made where a mod's objective can't be (see SystemWatch). */
    public static final String STAND_IN_OBJECTIVE = "sensor_array";
    /** Host objective kinds this battle shows with a stand-in; the map check doesn't count them as missing. */
    public static final java.util.Set<String> standIns = new java.util.HashSet<String>();
    private static java.util.Map<String, String> effectClasses;

    /**
     * The objective kind to make here for one of the host's: its own, or a stand-in when its effect script
     * can't run in a joiner's battle. Called from the join mission while it is set up.
     */
    public static String kindToMake(String type) {
        try {
            if (effectClasses == null) {
                java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                org.json.JSONObject all = com.fs.starfarer.api.Global.getSettings().getMergedJSON("data/config/battle_objectives.json");
                java.util.Iterator<?> it = all.keys();
                while (it.hasNext()) {
                    String k = String.valueOf(it.next());
                    try {
                        org.json.JSONObject o = all.getJSONObject(k);
                        if (o != null) m.put(k, o.optString("effectClass", ""));
                    } catch (Throwable t) { /* not an objective entry */ }
                }
                effectClasses = m;
            }
            String effect = effectClasses.get(type);
            if (SystemWatch.get().allowObjective(type, effect, CampaignStandIn.isInstalled())) return type;
            standIns.add(type);
            com.fs.starfarer.api.Global.getLogger(JoinSetup.class).info("Wingman objectives: '" + type + "' (" + effect
                    + ") can't run in a joiner's battle; a " + STAND_IN_OBJECTIVE + " marks its place");
            return STAND_IN_OBJECTIVE;
        } catch (Throwable t) {
            com.fs.starfarer.api.Global.getLogger(JoinSetup.class).warn("Wingman: could not read the objective kinds; using '" + type + "' as is", t);
            return type;
        }
    }

    private static Info cached;
    private static long cachedAt;
    /** If the host refused us here, the reason; the battle plugin shows it. */
    public static volatile String rejectReason;

    /** Forget what was learned last time, so the next fetch asks the host again. */
    public static synchronized void forget() { cached = null; }

    /**
     * The host's battlefield as last heard of, on the title screen or in a battle. The host sends it to
     * everyone it has let in when its battle begins, so by the time the join mission is set up it is
     * usually already here, and the map can be made the right size without asking. (Asking takes a round
     * trip the host may be slow to make: a game window in the background can run very slowly.)
     */
    private static volatile BattleInfo known;

    /** Note the host's battlefield (called wherever a BATTLE_INFO is read). */
    public static void remember(BattleInfo b) { known = b; }

    /** The host's battle is over: its battlefield no longer applies. */
    public static void battleOver() { known = null; }

    public static synchronized Info fetch() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < 4000) return cached;
        Info info = new Info();
        try {
            BattleInfo b = known;
            if (b != null && ClientLink.get().getState() == ClientLink.State.CONNECTED) fill(info, b);
            else ask(info);
        } catch (Throwable t) {
            info.status = "Could not ask the host about its battlefield (" + t + "). Using a large default map.";
        }
        cached = info;
        cachedAt = System.currentTimeMillis();
        return info;
    }

    private static void ask(Info info) throws Exception {
        WingmanConfig cfg = WingmanConfig.load();
        ClientLink link = ClientLink.get();
        rejectReason = null;
        if (link.getState() == ClientLink.State.CONNECTED) {
            Conn c = link.getConn();
            while (c.poll() != null) { /* old messages */ }
            c.send(Msg.SYNC_REQ, Msg.EMPTY);
        } else {
            link.connect(cfg.hostAddress, cfg.port, cfg.playerName, Mods.enabled(), Mods.gameVersion(), cfg.joinPassword);
            long until = System.currentTimeMillis() + 2500;
            while (link.getState() == ClientLink.State.CONNECTING && System.currentTimeMillis() < until) Thread.sleep(20);
        }
        if (link.getState() != ClientLink.State.CONNECTED) {
            info.status = "Host not reachable at " + cfg.hostAddress + ":" + cfg.port + ". Using a large default map.";
            return;
        }
        Conn c = link.getConn();
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until && !c.isClosed()) {
            Conn.Packet p = c.poll();
            if (p == null) { Thread.sleep(15); continue; }
            if (p.type == Msg.REJECT) {
                rejectReason = Msg.readUtf(p.data);
                info.status = "The host refused the connection: " + rejectReason.replace('\n', ' ');
                return;
            }
            if (p.type != Msg.BATTLE_INFO) continue;
            BattleInfo b = BattleInfo.decode(p.data);
            known = b;
            fill(info, b);
            return;
        }
        info.status = "Connected, but the host is not in a battle yet. Click this mission again once it is, "
                + "or the map will be a large default one.";
    }

    /** The battlefield the join mission is being set up from (for the stand-in campaign). */
    private static volatile BattleInfo lastBattle;

    /**
     * Called by the join mission while it is set up, before any objective or ship exists: put the host's
     * campaign in place for mods' scripts to find (see CampaignStandIn).
     */
    public static void prepareCampaign() {
        try {
            BattleInfo b = lastBattle;
            if (b == null || !WingmanConfig.load().campaignStandIn) return;
            CampaignStandIn.install(b);
        } catch (Throwable t) {
            com.fs.starfarer.api.Global.getLogger(JoinSetup.class).warn("Wingman: could not set up the stand-in campaign", t);
        }
    }

    private static void fill(Info info, BattleInfo b) {
        lastBattle = b;
        {
            info.width = b.width;
            info.height = b.height;
            info.background = b.background;
            info.hyperspace = b.hyperspace;
            info.hasPlanet = b.hasPlanet;
            info.planetType = b.planetType;
            info.planetRadius = b.planetRadius;
            info.planetYOffset = b.planetYOffset;
            info.planetBgWidth = b.planetBgWidth;
            info.planetBgHeight = b.planetBgHeight;
            info.hasGlow = b.hasGlowColor;
            if (b.hasGlowColor) info.glow = new java.awt.Color(b.glowColor, true);
            info.objectiveTypes.addAll(b.objectiveTypes);
            info.objectiveX.addAll(b.objectiveX);
            info.objectiveY.addAll(b.objectiveY);
            info.found = true;
            info.status = "Host's battle found: map " + Math.round(info.width) + " x " + Math.round(info.height)
                    + (b.hasPlanet ? ", near a planet" : "") + (b.hyperspace ? ", in hyperspace" : "") + ".";
        }
    }
}
