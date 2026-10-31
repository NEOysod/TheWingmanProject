package wingman.compat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.PuppetAI;
import wingman.api.WingmanAPI;
import wingman.client.CampaignStandIn;
import wingman.host.Identity;
import wingman.net.In;
import wingman.net.ModChannel;
import wingman.net.Out;
import wingman.net.Person;
import wingman.net.Snapshot;
import wingman.util.ReflectionUtils;

/**
 * Secrets of the Frontier's Felcesis. In a campaign battle SotF's SotfFelInvasionPlugin may pick one of the enemy's
 * ships at random and infest it: four seconds of jitter, smoke, sounds and warnings, then Fel is its captain, with
 * skills picked for this battle. A joiner's battle is a mission, where that plugin does nothing, so only the host
 * picks. The host names the ship to its joiners, with what SotF's show of it depends on (Fel, her skills, the
 * player's guilt, how many invasions came before); a joiner then runs SotF's own infestation plugin on its copy.
 */
final class SotfFel {
    private SotfFel() {}

    // From Secrets of the Frontier 0.15.1 (data.scripts.combat.special.SotfFelInvasionPlugin, campaign.ids.SotfIDs,
    // campaign.ids.SotfPeople, utils.SotfMisc).
    static final String PLUGIN = "data.scripts.combat.special.SotfFelInvasionPlugin";
    static final String MISC = "data.scripts.utils.SotfMisc";
    /** SotfIDs.INVASION_INVADED_KEY: in the engine's custom data once the invasion has happened this battle. */
    static final String INVADED_KEY = "$sotf_invasion_invaded";
    /** SotfFelInvasionPlugin.INFEST_IMMUNITY_KEY: on the infested ship's damage taken while it is being infested. */
    static final String IMMUNITY_KEY = "sotf_felInfestImmunity";
    /** SotfPeople.FEL: her id among the sector's important people; she is the ship's captain once infested. */
    static final String FEL = "sotf_fel";
    /** SotfIDs.MEM_NUM_FEL_INVASIONS, on the sector's memory. */
    static final String NUM_INVASIONS = "$sotf_numFelInvasions";
    /** SotfFelInvasionPlugin.infestShip: createFelInfestationPlugin(ship, 4f). */
    static final float INFEST_TIME = 4f;

    static final String CHANNEL = "secretsofthefrontier:fel";
    static final int VERSION = 1;
    /** Engine custom data on the host (what was found this battle) and ship custom data on a joiner (shown here). */
    static final String STATE = "wingman_sotfFel";
    /** Battle seconds after SotF marks the invasion within which its ship shows (its first unpaused frame). */
    static final float LOOK_FOR = 10f;
    /** How long a joiner keeps the host's word while its copy of the ship isn't here (being sent, or never). */
    static final long WAIT_MS = 10000L;

    private static boolean guiltWarned, encodeWarned, readWarned, showWarned, standInWarned;

    /** What the host tells its joiners. */
    static final class Infest {
        int netId;
        String memberId = "";
        float guilt;
        int invasions;
        Person fel;
        long at;
    }

    static byte[] encode(Infest m) throws IOException {
        Out o = new Out();
        o.writeByte(VERSION);
        o.writeInt(m.netId);
        o.writeUTF(m.memberId);
        o.writeFloat(m.guilt);
        o.writeInt(m.invasions);
        m.fel.write(o);
        return o.toByteArray();
    }

    static Infest decode(byte[] data) throws IOException {
        In in = new In(data);
        int version = in.readByte();
        if (version != VERSION) throw new IOException("unknown version " + version);
        Infest m = new Infest();
        m.netId = in.readInt();
        m.memberId = in.readUTF();
        m.guilt = in.readFloat();
        m.invasions = in.readInt();
        m.fel = Person.read(in);
        if (m.netId <= 0) throw new IOException("bad ship id");
        if (Snapshot.bad(m.guilt)) throw new IOException("bad guilt");
        if (m.invasions < 0 || m.invasions > 1000000) throw new IOException("bad invasion count");
        if (!FEL.equals(m.fel.id)) throw new IOException("not Fel");
        return m;
    }

    // ---- host ----

    /** The host's battle so far (in the engine's custom data, so a new battle starts afresh). */
    static final class Found {
        float since;
        ShipAPI ship;
        byte[] msg;
        boolean failed;
    }

    /**
     * SotF marks the invasion in the engine's custom data, then hands the ship to its infestation plugin, which
     * makes it immune for the first four seconds and makes Fel its captain after. Either one names the ship.
     */
    static byte[] news(CombatEngineAPI engine, Map<ShipAPI, Integer> ids) {
        Map<String, Object> data = engine.getCustomData();
        if (!data.containsKey(INVADED_KEY)) return null;
        Object o = data.get(STATE);
        Found f;
        if (o instanceof Found) {
            f = (Found) o;
        } else {
            f = new Found();
            f.since = engine.getTotalElapsedTime(false);
            data.put(STATE, f);
        }
        if (f.msg != null || f.failed || engine.getTotalElapsedTime(false) - f.since > LOOK_FOR) return null;
        for (ShipAPI s : engine.getShips()) {
            if (s.isFighter() || !infested(s)) continue;
            Integer id = ids.get(s);
            if (id == null) return null; // not counted yet: next frame
            try {
                Infest m = new Infest();
                m.netId = id;
                m.memberId = s.getFleetMember() == null || s.getFleetMember().getId() == null ? "" : s.getFleetMember().getId();
                m.guilt = guilt();
                SectorAPI sector = Global.getSector();
                // SotF counted this invasion on the plugin's first frame, before the ship turned immune.
                m.invasions = sector == null ? 0 : Math.max(0, sector.getMemoryWithoutUpdate().getInt(NUM_INVASIONS) - 1);
                PersonAPI fel = isFel(s.getCaptain()) ? s.getCaptain() : sector == null ? null : sector.getImportantPeople().getPerson(FEL);
                if (fel == null) return null;
                m.fel = Identity.person(fel, true);
                f.ship = s;
                f.msg = ModChannel.encode(CHANNEL, encode(m));
                Global.getLogger(SotfFel.class).info("Wingman: Secrets of the Frontier's Fel infested ship " + id
                        + " (" + s.getHullSpec().getHullId() + "); joiners told");
                return f.msg;
            } catch (Throwable t) {
                f.failed = true;
                if (!encodeWarned) {
                    encodeWarned = true;
                    Global.getLogger(SotfFel.class).warn("Wingman: could not tell joiners which ship Fel infested", t);
                }
                return null;
            }
        }
        return null;
    }

    /** The message again, for a joiner just sent the whole battle, while Fel's ship still fights. */
    static byte[] state(CombatEngineAPI engine) {
        Object o = engine.getCustomData().get(STATE);
        if (!(o instanceof Found)) return null;
        Found f = (Found) o;
        if (f.msg == null || !engine.isEntityInPlay(f.ship) || !f.ship.isAlive() || f.ship.isRetreating()) return null;
        return f.msg;
    }

    static boolean infested(ShipAPI s) {
        if (s.getMutableStats().getHullDamageTakenMult().getMultStatMod(IMMUNITY_KEY) != null) return true;
        return isFel(s.getCaptain());
    }

    private static boolean isFel(PersonAPI p) { return p != null && FEL.equals(p.getId()); }

    /** SotfMisc.getPlayerGuilt(): the player's memory plus a bonus from SotF's (and LunaLib's) settings. */
    private static float guilt() {
        try {
            Class<?> misc = Class.forName(MISC, true, Global.getSettings().getScriptClassLoader());
            float g = ((Number) ReflectionUtils.invokeStatic(0, "getPlayerGuilt", misc)).floatValue();
            if (!Snapshot.bad(g)) return g;
        } catch (Throwable t) {
            if (!guiltWarned) {
                guiltWarned = true;
                Global.getLogger(SotfFel.class).warn("Wingman: could not read the guilt Secrets of the Frontier keeps; joiners see Fel's calmer warnings", t);
            }
        }
        return 0f;
    }

    // ---- joiner ----

    private static boolean listening;
    /** The host's word, until the copy of its ship is here. Game thread only. */
    static Infest wanted;

    static void listen() {
        if (listening) return;
        listening = true;
        WingmanAPI.listen(CHANNEL, new WingmanAPI.Listener() {
            public void onMessage(String from, int playerId, byte[] data) { heard(playerId, data); }
        });
    }

    static void heard(int playerId, byte[] data) {
        if (playerId != WingmanAPI.HOST) return; // only the host decides which ship it was
        try {
            Infest m = decode(data);
            m.at = System.currentTimeMillis();
            wanted = m;
        } catch (Throwable t) {
            if (!readWarned) {
                readWarned = true;
                Global.getLogger(SotfFel.class).warn("Wingman: the host's word on Fel's ship could not be read", t);
            }
        }
    }

    static int wantedId() {
        Infest w = wanted;
        return w == null ? -1 : w.netId;
    }

    static void show(CombatEngineAPI engine, ShipAPI ship) {
        Infest w = wanted;
        if (w == null) return;
        if (ship == null || !engine.isEntityInPlay(ship) || !sameMember(ship, w.memberId)) {
            if (System.currentTimeMillis() - w.at > WAIT_MS) wanted = null;
            return;
        }
        wanted = null;
        if (ship.getCustomData().containsKey(STATE)) return; // already shown on this copy
        ship.setCustomData(STATE, Boolean.TRUE);
        try {
            start(engine, ship, w);
        } catch (Throwable t) {
            if (!showWarned) {
                showWarned = true;
                Global.getLogger(SotfFel.class).warn("Wingman: Fel's infestation could not be shown here", t);
            }
        }
    }

    private static boolean sameMember(ShipAPI ship, String memberId) {
        if (memberId.isEmpty()) return true;
        return ship.getFleetMember() != null && memberId.equals(ship.getFleetMember().getId());
    }

    /**
     * SotF's own infestation plugin, made the way SotfFelInvasionPlugin.infestShip makes it, on the joiner's copy.
     * It reads Fel from the sector's important people (in init and when it hands her the ship), the invasion
     * count from the sector's memory, and the guilt from the invasion plugin that made it.
     */
    private static void start(CombatEngineAPI engine, ShipAPI ship, Infest w) throws Throwable {
        PersonAPI fel = CampaignStandIn.importantPerson(w.fel);
        if (fel == null) {
            if (!standInWarned) {
                standInWarned = true;
                Global.getLogger(SotfFel.class).info("Wingman: no stand-in campaign on this PC, so Fel's infestation is not shown here");
            }
            return;
        }
        CampaignStandIn.sectorValue(NUM_INVASIONS, w.invasions);
        Class<?> c = Class.forName(PLUGIN, true, Global.getSettings().getScriptClassLoader());
        EveryFrameCombatPlugin outer = (EveryFrameCombatPlugin) ReflectionUtils.instantiate(c);
        outer.init(engine);
        ReflectionUtils.set("guilt", outer, w.guilt);
        EveryFrameCombatPlugin inner = (EveryFrameCombatPlugin) ReflectionUtils.invoke("createFelInfestationPlugin", outer, ship, INFEST_TIME);
        engine.addPlugin(new Shown(inner, ship));
        Global.getLogger(SotfFel.class).info("Wingman: showing Fel's infestation of ship " + w.netId + " as the host has it");
    }

    /**
     * SotF's infestation plugin, kept to what a copy may do here. When it hands the ship to Fel it also gives it an
     * AI of its own, and a copy only mirrors the host: it gets its PuppetAI back in the same frame. It goes when SotF's
     * would (a second after the ship is lost or retreats; SotF's own removePlugin call is about itself, not this), when
     * the copy leaves the battle here (rebuilt, reset), or at once if SotF's code fails here.
     */
    static final class Shown extends BaseEveryFrameCombatPlugin {
        private final EveryFrameCombatPlugin inner;
        private final ShipAPI ship;
        private CombatEngineAPI engine;
        private boolean puppeted, ending, done;
        private float fade = 1f;

        Shown(EveryFrameCombatPlugin inner, ShipAPI ship) {
            this.inner = inner;
            this.ship = ship;
        }

        public void init(CombatEngineAPI engine) {
            this.engine = engine;
            try { inner.init(engine); } catch (Throwable t) { fail(t); }
        }

        public void advance(float amount, List<InputEventAPI> events) {
            if (done) { engine.removePlugin(this); return; }
            if (!engine.isEntityInPlay(ship)) { stop(); return; }
            try {
                inner.advance(amount, events);
            } catch (Throwable t) {
                fail(t);
                return;
            }
            if (!puppeted && isFel(ship.getCaptain())) {
                ship.setShipAI(new PuppetAI());
                puppeted = true;
            }
            // Mirrors SotF's: it starts to fade on an unpaused frame that finds the ship lost, then fades for a second.
            if (ending) {
                fade -= amount;
                if (fade < 0f) stop();
            } else if (!engine.isPaused() && (!ship.isAlive() || ship.isRetreating())) {
                ending = true;
            }
        }

        public void renderInWorldCoords(ViewportAPI viewport) {
            if (done) return;
            try { inner.renderInWorldCoords(viewport); } catch (Throwable t) { fail(t); }
        }

        public void renderInUICoords(ViewportAPI viewport) {
            if (done) return;
            try { inner.renderInUICoords(viewport); } catch (Throwable t) { fail(t); }
        }

        private void stop() {
            done = true;
            engine.removePlugin(this);
        }

        private void fail(Throwable t) {
            done = true;
            try { ship.setShipAI(new PuppetAI()); } catch (Throwable ignored) { /* the copy is gone */ }
            if (!showWarned) {
                showWarned = true;
                Global.getLogger(SotfFel.class).warn("Wingman: Secrets of the Frontier's infestation failed on this PC; it is no longer shown", t);
            }
        }
    }
}
