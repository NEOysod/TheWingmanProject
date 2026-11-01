package wingman.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CoreUIAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.ui.HintPanelAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.SkillsChangeRemoveVentsCapsEffect;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

import wingman.compat.ModCompat;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.RefitData;
import wingman.net.RefitNames;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;
import wingman.net.RefitResult;
import wingman.ExoticaRefit;
import wingman.WingmanConfig;

/**
 * Friend refit, host side (see docs/FRIEND_REFIT_DESIGN.md). While the host is docked at a market it can trade
 * with, joiners are told so in the roster (Roster.refitMarket) and get a Refit button for the ship they mark.
 * Any of the host's ships can be refitted but the host's own: a pick moves the joiner's officer, a refit does not.
 * When one opens their refit screen (Msg.REFIT_ASK), the host checks they may, then sends what the screen shows
 * (Msg.REFIT_DATA), built only from the host's campaign; again whenever it changes, until they close it.
 *
 * A request (Msg.REFIT_REQUEST) is checked against the host's campaign and waits for host approval unless that
 * player has permission to refit without confirmation. No host screen is opened or closed. Runs on the game
 * thread, from CampaignLobby.
 */
public final class FriendRefit {
    private FriendRefit() {}

    /** Players with the refit screen open and the member each is refitting. */
    private static final Map<HostSession.Player, String> open = new HashMap<HostSession.Player, String>();
    /** What each of them was last sent, to send again only on a change. */
    private static final Map<HostSession.Player, byte[]> lastSent = new HashMap<HostSession.Player, byte[]>();
    /** Waiting requests by connection id, oldest first. */
    static final LinkedHashMap<Integer, Pending> pending = new LinkedHashMap<Integer, Pending>();
    private static long lastChecked;
    private static boolean broken;

    /** The game's submarkets ask "may the player trade here?" of the core screen; this answers as the dialog's trade mode. */
    private static final class TradeUi implements CoreUIAPI {
        private final CoreUITradeMode mode;
        TradeUi(CoreUITradeMode mode) { this.mode = mode; }
        public CoreUITradeMode getTradeMode() { return mode; }
        public HintPanelAPI getHintPanel() { return null; }
    }

    /** The market the host is docked at, if refitting is possible there now; else null. */
    public static MarketAPI dockedMarket() {
        try {
            InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            if (dialog == null) return null;
            SectorEntityToken target = dialog.getInteractionTarget();
            MarketAPI market = target == null ? null : target.getMarket();
            if (market == null || market.isPlanetConditionMarketOnly()) return null;
            if (tradeMode(target) == CoreUITradeMode.NONE) return null; // not allowed to dock (hostile, or the market is shut)
            if (market.getSubmarketsCopy().isEmpty()) return null;
            return market;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean docked() { return dockedMarket() != null; }

    static boolean bypass(HostSession.Player p) {
        return live(HostSession.active(), p) && p.refitWithoutConfirmation;
    }

    static void setBypass(HostSession.Player p, boolean enabled) {
        if (!live(HostSession.active(), p)) return;
        p.refitWithoutConfirmation = enabled;
        lastChecked = 0;
    }

    private static CoreUITradeMode tradeMode(SectorEntityToken target) {
        try { return Misc.getTradeMode(target.getMemoryWithoutUpdate()); } catch (Throwable t) { return CoreUITradeMode.OPEN; }
    }

    /** What joiners are told: the market's name while they may refit there, else "". */
    static String availableAt(WingmanConfig cfg) {
        if (broken || !cfg.friendRefit) return "";
        MarketAPI m = dockedMarket();
        if (m == null) return "";
        String name = m.getName();
        return name == null || name.isEmpty() ? "this market" : name;
    }

    /** Why this player can't refit that member now, or null. */
    private static String refusal(String memberId, WingmanConfig cfg) {
        if (!cfg.friendRefit) return "the host has switched friend refits off";
        if (dockedMarket() == null) return "the host isn't docked at a market";
        return shipRefusal(memberId);
    }

    /**
     * Why this player can't have the refit screen open on that member, or null. Docking isn't needed: away from a
     * market the joiner plans (the ship, the host's cargo, credits and story points are shown), and sends once the
     * host docks.
     */
    private static String refusalToPlan(String memberId, WingmanConfig cfg) {
        if (!cfg.friendRefit) return "the host has switched friend refits off";
        return shipRefusal(memberId);
    }

    /** Any ship in the host's fleet can be refitted except theirs (and ships that can't be). */
    private static String shipRefusal(String memberId) {
        FleetMemberAPI m = member(memberId);
        if (m == null) return "that ship is no longer in the fleet";
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        if (pf != null && m == pf.getFlagship()) return "that is the host's flagship";
        if (m.isMothballed()) return "that ship is mothballed";
        if (m.isStation()) return "a station can't be refitted";
        return null;
    }

    private static FleetMemberAPI member(String id) {
        for (FleetMemberAPI f : CampaignLobby.fleet()) if (id.equals(f.getId())) return f;
        return null;
    }

    /** Each frame on the campaign map: answer asks, keep open screens up to date, close the ones that can't stay. */
    static void advance(HostSession s, WingmanConfig cfg) {
        if (broken) return;
        try {
            for (HostSession.Player p : s.getPlayers()) {
                if (!live(s, p)) continue;
                byte[] rq = p.refitRequest;
                if (rq != null) { p.refitRequest = null; receive(p, rq, cfg); }
                String ask = p.refitAsk;
                if (ask == null) continue;
                p.refitAsk = null;
                if (ask.isEmpty()) { open.remove(p); lastSent.remove(p); continue; }
                String why = refusalToPlan(ask, cfg);
                if (why != null) {
                    p.conn.send(Msg.NOTICE, Msg.utf("Can't refit: " + why + "."));
                    open.remove(p);
                    lastSent.remove(p);
                    continue;
                }
                open.put(p, ask);
                lastSent.remove(p);
                lastChecked = 0; // send it now
            }
            if (open.isEmpty() && pending.isEmpty()) return;
            long now = System.currentTimeMillis();
            boolean refresh = now - lastChecked >= 1000;
            if (refresh) lastChecked = now;
            if (refresh || !cfg.friendRefit) keepPending(s, cfg, refresh);
            if (open.isEmpty() || !refresh) return;
            for (Iterator<Map.Entry<HostSession.Player, String>> it = open.entrySet().iterator(); it.hasNext();) {
                Map.Entry<HostSession.Player, String> e = it.next();
                HostSession.Player p = e.getKey();
                if (!live(s, p)) { it.remove(); lastSent.remove(p); continue; }
                String why = refusalToPlan(e.getValue(), cfg);
                if (why != null) { // the pick changed, or the ship can't be refitted: the joiner's screen closes on the roster
                    it.remove();
                    lastSent.remove(p);
                    continue;
                }
                byte[] data = build(member(e.getValue()), dockedMarket()).encode(); // away from a market: no market, no storage
                if (Arrays.equals(data, lastSent.get(p))) continue;
                lastSent.put(p, data);
                p.conn.send(Msg.REFIT_DATA, data);
            }
        } catch (Throwable t) {
            broken = true;
            open.clear();
            lastSent.clear();
            for (Pending pd : pending.values()) send(pd.player, new RefitResult(RefitResult.REFUSED, "The host couldn't check your refit."));
            pending.clear();
            Global.getLogger(FriendRefit.class).error("Wingman: friend refits stopped working and are switched off until the game is restarted", t);
        }
    }

    /** The host went into a battle: no refit screen or request stays open. */
    static void closeAll() {
        ExoticaRefit.clear();
        wingman.BionicsRefit.clear();
        clearRequests("The host went into a battle.");
    }

    /** A loaded campaign starts with no requests or connection permissions from the previous campaign. */
    public static void resetForCampaignLoad() {
        HostSession s = HostSession.active();
        if (s != null) for (HostSession.Player p : s.getPlayers()) p.refitWithoutConfirmation = false;
        clearRequests("The host loaded a campaign.");
        broken = false;
    }

    private static void clearRequests(String reason) {
        HostSession s = HostSession.active();
        if (s != null) for (HostSession.Player p : s.getPlayers()) {
            Pending pd = pending.get(p.id);
            if (p.refitRequest != null && (pd == null || pd.player != p)) {
                send(p, new RefitResult(RefitResult.WITHDRAWN, reason));
            }
            p.refitRequest = null;
            p.refitAsk = null;
        }
        for (Pending pd : pending.values()) send(pd.player, new RefitResult(RefitResult.WITHDRAWN, reason));
        pending.clear();
        open.clear();
        lastSent.clear();
        lastChecked = 0;
    }

    /** For ExoticaRefit: why this player can't plan a refit of that member now (docking is checked by the caller), or null. */
    public static String refusalFor(String memberId, WingmanConfig cfg) { return refusalToPlan(memberId, cfg); }

    /** For ExoticaRefit: a ship of the host's fleet by its id, or null. */
    public static FleetMemberAPI memberFor(String id) { return member(id); }

    /** For ExoticaRefit: a connected player by id, or null. */
    public static HostSession.Player playerFor(HostSession s, int id) { return playerById(s, id); }

    private static HostSession.Player playerById(HostSession s, int id) {
        for (HostSession.Player p : s.getPlayers()) if (p.id == id) return p;
        return null;
    }

    private static boolean live(HostSession s, HostSession.Player p) {
        return s != null && p != null && !p.replaced && p.conn != null && !p.conn.isClosed()
                && playerById(s, p.id) == p;
    }

    // ---- what the joiner's refit screen shows ----

    private static void captureSlots(RefitData d, ShipVariantAPI variant, String prefix) {
        ShipHullSpecAPI hull = variant.getHullSpec();
        for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
            if (s.isDecorative() || s.isSystemSlot() || s.isHidden() || s.isStationModule()) continue;
            boolean editable = s.isWeaponSlot() && !s.isBuiltIn();
            if (!editable && !s.isBuiltIn()) continue;
            RefitData.Slot info = new RefitData.Slot();
            info.place = prefix + s.getId();
            info.size = s.getSlotSize().name();
            info.type = s.getWeaponType().name();
            info.editable = editable;
            info.builtIn = s.isBuiltIn();
            info.hardpoint = s.isHardpoint();
            info.x = s.getLocation().x; info.y = s.getLocation().y;
            info.angle = s.getAngle(); info.arc = s.getArc();
            String weapon = hull.getBuiltInWeapons().get(s.getId());
            if (weapon == null && info.builtIn) weapon = variant.getWeaponId(s.getId());
            info.builtInWeapon = weapon == null ? "" : weapon;
            d.slots.add(info);
        }
    }

    static RefitData build(FleetMemberAPI m, MarketAPI market) {
        RefitData d = new RefitData();
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        ShipVariantAPI v = m.getVariant();
        d.market = market == null ? "" : market.getName() == null || market.getName().isEmpty() ? "this market" : market.getName();
        String systemId = v.getHullSpec().getShipSystemId();
        d.systemId = systemId == null ? "" : systemId;
        d.memberId = m.getId();
        d.hullId = m.getHullId() == null ? "" : m.getHullId();
        d.hullSize = String.valueOf(m.getHullSpec().getHullSize());
        try { d.opMax = m.getHullSpec().getOrdnancePoints(stats); } catch (Throwable t) { d.opMax = m.getHullSpec().getOrdnancePoints(null); }
        try { d.opUsed = v.computeOPCost(stats); } catch (Throwable t) { /* 0 */ }
        d.vents = v.getNumFluxVents();
        d.caps = v.getNumFluxCapacitors();
        try {
            d.maxVents = SkillsChangeRemoveVentsCapsEffect.getMaxVents(m.getHullSpec().getHullSize(), stats);
            d.maxCaps = SkillsChangeRemoveVentsCapsEffect.getMaxCaps(m.getHullSpec().getHullSize(), stats);
        } catch (Throwable t) { /* 0: unknown */ }
        try { d.maxSMods = Misc.getMaxPermanentMods(m, stats); } catch (Throwable t) { /* 0 */ }

        captureSlots(d, v, "");
        Set<String> keys = new LinkedHashSet<String>(); // every item named below, for its price
        for (String slot : v.getFittedWeaponSlots()) {
            String id = v.getWeaponId(slot);
            if (id == null) continue;
            try {
                WeaponSlotAPI ws = v.getSlot(slot);
                if (ws != null && (ws.isDecorative() || ws.isSystemSlot() || ws.isHidden() || ws.isStationModule())) continue; // built-ins stay: a hull mod may have put them there
            } catch (Throwable t) { /* listed */ }
            d.fitSlots.add(slot);
            d.fitWeapons.add(id);
            keys.add(RefitData.key(RefitData.WEAPON, id));
        }
        try {
            for (String slot : v.getModuleSlots()) {
                ShipVariantAPI mv = v.getModuleVariant(slot);
                if (mv == null) continue;
                d.moduleSlots.add(slot);
                d.moduleHulls.add(mv.getHullSpec().getHullId());
                WeaponSlotAPI moduleSlot = v.getSlot(slot);
                String sprite = mv.getHullSpec().getSpriteName();
                if (moduleSlot == null || sprite == null) d.modulePictures.add("");
                else d.modulePictures.add(mv.getHullSpec().getHullId() + "|" + sprite + "|" + moduleSlot.getLocation().x + "|"
                        + moduleSlot.getLocation().y + "|" + moduleSlot.getAngle());
                captureSlots(d, mv, slot + "/");
                for (String ms : mv.getFittedWeaponSlots()) { // what a module holds can be changed too, as "module/slot"
                    String mw = mv.getWeaponId(ms);
                    WeaponSlotAPI mws = mv.getSlot(ms);
                    if (mw == null || mws == null || mws.isDecorative() || mws.isSystemSlot() || mws.isHidden() || mws.isStationModule()) continue;
                    d.fitSlots.add(slot + "/" + ms);
                    d.fitWeapons.add(mw);
                    keys.add(RefitData.key(RefitData.WEAPON, mw));
                }
            }
        } catch (Throwable t) { /* none shown */ }
        for (WeaponGroupSpec g : v.getWeaponGroups()) {
            RefitData.Group rg = new RefitData.Group(g.getType() == WeaponGroupType.ALTERNATING, g.isAutofireOnByDefault());
            rg.slots.addAll(g.getSlots());
            d.groups.add(rg);
        }
        d.hasGroups = true;
        int bays = 0;
        try { bays = m.getNumFlightDecks(); } catch (Throwable t) { /* none */ }
        for (int i = 0; i < bays && i < RefitData.MAX_BAYS; i++) {
            String w = null;
            try { w = v.getWingId(i); } catch (Throwable t) { /* empty */ }
            d.wings.add(w == null ? "" : w);
            if (w != null) keys.add(RefitData.key(RefitData.WING, w));
        }
        Set<String> sMods = new LinkedHashSet<String>();
        try { sMods.addAll(v.getSMods()); } catch (Throwable t) { /* none */ }
        try { sMods.addAll(v.getSModdedBuiltIns()); } catch (Throwable t) { /* none */ }
        Set<String> perma = new LinkedHashSet<String>(v.getPermaMods());
        perma.removeAll(sMods);
        Set<String> fitted = new LinkedHashSet<String>(v.getNonBuiltInHullmods());
        fitted.removeAll(perma);
        fitted.removeAll(sMods);
        d.hullmods.addAll(fitted);
        d.sMods.addAll(sMods);
        d.permaMods.addAll(perma);

        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        d.credits = pf.getCargo().getCredits().get();
        d.storyPoints = stats.getStoryPoints();
        items(pf.getCargo(), d.cargo, keys);
        if (market != null) try {
            if (Misc.playerHasStorageAccess(market)) {
                d.hasStorage = true;
                items(Misc.getStorageCargo(market), d.storage, keys);
            }
        } catch (Throwable t) { d.hasStorage = false; d.storage.clear(); }

        SubmarketPlugin.TransferAction buy = SubmarketPlugin.TransferAction.PLAYER_BUY, sell = SubmarketPlugin.TransferAction.PLAYER_SELL;
        CoreUIAPI ui = market == null ? null : new TradeUi(tradeMode(market.getPrimaryEntity()));
        List<SubmarketPlugin> plugins = new ArrayList<SubmarketPlugin>();
        for (SubmarketAPI sub : market == null ? new ArrayList<SubmarketAPI>() : market.getSubmarketsCopy()) {
            if (d.markets.size() >= RefitData.MAX_MARKETS) break;
            try {
                String id = sub.getSpecId();
                if (Submarkets.SUBMARKET_STORAGE.equals(id) || Submarkets.LOCAL_RESOURCES.equals(id)) continue;
                SubmarketPlugin pl = sub.getPlugin();
                if (pl == null || pl.isHidden() || !pl.showInCargoScreen() || pl.isFreeTransfer()) continue;
                RefitData.Market rm = new RefitData.Market();
                rm.id = id;
                rm.name = sub.getNameOneLine() == null ? id : sub.getNameOneLine();
                rm.tariff = sub.getTariff();
                try { rm.usable = pl.isEnabled(ui); } catch (Throwable t) { rm.usable = false; }
                if (!rm.usable) {
                    try { rm.reason = pl.getTooltipAppendix(ui); } catch (Throwable t) { /* none given */ }
                    if (rm.reason == null || rm.reason.isEmpty()) rm.reason = "Not open to the host";
                }
                CargoAPI stock = sub.getCargo();
                if (stock != null) {
                    items(stock, rm.stock, keys);
                    for (CargoStackAPI st : stock.getStacksCopy()) {
                        String k = keyOf(st);
                        if (k != null && pl.isIllegalOnSubmarket(st, buy) && !rm.cantBuy.contains(k)) rm.cantBuy.add(k);
                    }
                }
                d.markets.add(rm);
                plugins.add(pl);
            } catch (Throwable t) { /* that submarket isn't offered */ }
        }
        // What each submarket won't take: checked for everything the host could sell (cargo, storage, the ship's own).
        for (int i = 0; i < d.markets.size(); i++) {
            SubmarketPlugin pl = plugins.get(i);
            for (String k : keys) {
                if (d.markets.get(i).cantSell.size() >= RefitData.MAX_ITEMS) break;
                try {
                    CargoStackAPI st = stackOf(k);
                    if (st != null && pl.isIllegalOnSubmarket(st, sell)) d.markets.get(i).cantSell.add(k);
                } catch (Throwable t) { /* allowed */ }
            }
        }
        for (String id : knownHullmods()) {
            if (d.knownHullmods.size() >= RefitData.MAX_MODS) break;
            d.knownHullmods.add(id);
        }
        for (String k : keys) { // what each item costs in ordnance points on this ship
            if (d.opCosts.size() >= RefitData.MAX_VALUES) break;
            try {
                String id = k.substring(2);
                float op = k.startsWith("f:") ? Global.getSettings().getFighterWingSpec(id).getOpCost(m.getStats())
                        : Global.getSettings().getWeaponSpec(id).getOrdnancePointCost(stats, m.getStats());
                d.opCosts.put(k, Math.round(op));
            } catch (Throwable t) { /* 0 */ }
        }
        Set<String> mods = new LinkedHashSet<String>(d.knownHullmods);
        mods.addAll(d.hullmods);
        mods.addAll(d.sMods);
        for (String id : mods) {
            if (d.modCosts.size() >= RefitData.MAX_MODS) break;
            try {
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                d.modCosts.put(id, spec.getCostFor(m.getHullSpec().getHullSize()));
                if (spec.hasTag(Tags.HULLMOD_NO_BUILD_IN) && d.noBuildIn.size() < RefitData.MAX_MODS) d.noBuildIn.add(id);
            } catch (Throwable t) { /* unknown here */ }
        }
        for (String k : keys) {
            if (d.values.size() >= RefitData.MAX_VALUES) break;
            try {
                String id = k.substring(2);
                float base = k.startsWith("f:") ? Global.getSettings().getFighterWingSpec(id).getBaseValue() : Global.getSettings().getWeaponSpec(id).getBaseValue();
                d.values.put(k, base);
            } catch (Throwable t) { /* no price known: shown as 0 */ }
        }
        return d;
    }

    /**
     * The hull mods the host's refit screen offers: those the player has learned and those every player has from
     * the start (always unlocked), less the hidden ones and D-mods. (The player faction's list is left out: mods
     * fill it with hull mods the player can't fit.)
     */
    static List<String> knownHullmods() {
        Set<String> ids = new LinkedHashSet<String>();
        try { ids.addAll(Global.getSector().getCharacterData().getHullMods()); } catch (Throwable t) { /* none from there */ }
        try {
            for (HullModSpecAPI spec : Global.getSettings().getAllHullModSpecs()) if (spec.isAlwaysUnlocked()) ids.add(spec.getId());
        } catch (Throwable t) { /* none from there */ }
        List<String> out = new ArrayList<String>();
        for (String id : ids) {
            try {
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || spec.hasTag(Tags.HULLMOD_DMOD)) continue;
                out.add(id);
            } catch (Throwable t) { /* not a hull mod this game has */ }
        }
        return out;
    }

    /** Weapons and fighter LPCs in a cargo, into the list; their keys into keys. */
    private static void items(CargoAPI cargo, List<RefitData.Item> to, Set<String> keys) {
        if (cargo == null) return;
        for (CargoAPI.CargoItemQuantity<String> q : cargo.getWeapons()) {
            if (to.size() >= RefitData.MAX_ITEMS) return;
            if (q.getItem() == null || q.getCount() <= 0) continue;
            to.add(new RefitData.Item(RefitData.WEAPON, q.getItem(), q.getCount()));
            keys.add(RefitData.key(RefitData.WEAPON, q.getItem()));
        }
        for (CargoAPI.CargoItemQuantity<String> q : cargo.getFighters()) {
            if (to.size() >= RefitData.MAX_ITEMS) return;
            if (q.getItem() == null || q.getCount() <= 0) continue;
            to.add(new RefitData.Item(RefitData.WING, q.getItem(), q.getCount()));
            keys.add(RefitData.key(RefitData.WING, q.getItem()));
        }
    }

    private static String keyOf(CargoStackAPI st) {
        try {
            if (st.isWeaponStack()) return RefitData.key(RefitData.WEAPON, st.getWeaponSpecIfWeapon().getWeaponId());
            if (st.isFighterWingStack()) return RefitData.key(RefitData.WING, st.getFighterWingSpecIfWing().getId());
        } catch (Throwable t) { /* not one of ours */ }
        return null;
    }

    /** A stack of one, to ask a submarket about. */
    private static CargoStackAPI stackOf(String key) {
        String id = key.substring(2);
        CargoAPI.CargoItemType type = key.startsWith("f:") ? CargoAPI.CargoItemType.FIGHTER_CHIP : CargoAPI.CargoItemType.WEAPONS;
        CargoStackAPI st = Global.getFactory().createCargoStack(type, id, null);
        st.setSize(1f);
        return st;
    }

    // ---- refit requests: received, checked, accepted or denied ----

    /** A request waiting for the host, with its most recently checked plan. */
    static final class Pending {
        HostSession.Player player;
        RefitRequest request;
        String ship = "";
        long at;
        RefitPlan plan;
        List<String> problems = new ArrayList<String>();
        /** The ordnance points of the new loadout as the game counts them on a copy of the ship, and the most it has. */
        int op, opMax;
    }

    private static void send(HostSession.Player p, RefitResult r) {
        try { p.conn.send(Msg.REFIT_RESULT, r.encode()); } catch (Throwable t) { /* gone */ }
    }

    private static void receive(HostSession.Player p, byte[] data, WingmanConfig cfg) {
        RefitRequest r;
        try { r = RefitRequest.decode(data); }
        catch (java.io.IOException e) { return; } // garbled: nothing to answer
        catch (Throwable t) {
            Global.getLogger(FriendRefit.class).warn("Wingman: a refit from " + p.name + " could not be read", t);
            send(p, new RefitResult(RefitResult.REFUSED, "The host couldn't read that refit."));
            return;
        }
        boolean replaced = pending.remove(p.id) != null;
        Pending pd = new Pending();
        pd.player = p;
        pd.request = r;
        pd.at = System.currentTimeMillis();
        try {
            String why = refusal(r.memberId, cfg);
            if (why != null) {
                Global.getLogger(FriendRefit.class).info("Wingman: refit from " + p.name + " refused: " + why);
                send(p, new RefitResult(RefitResult.REFUSED, "Can't refit: " + why + "."));
                return;
            }
            FleetMemberAPI m = member(r.memberId);
            pd.ship = CampaignLobby.label(m);
            check(pd, m);
            if (!live(HostSession.active(), p) || p.refitRequest != null || pending.containsKey(p.id)) return;
            if (pd.plan.isEmpty()) {
                send(p, new RefitResult(RefitResult.REFUSED, "Nothing changes in that refit."));
                return;
            }
            if (!pd.problems.isEmpty()) {
                send(p, new RefitResult(RefitResult.REFUSED, join(pd.problems)));
                return;
            }
            pending.put(p.id, pd);
            if (bypass(p)) {
                String waiting = accept(pd);
                if (waiting != null && pending.get(p.id) == pd) {
                    send(p, new RefitResult(RefitResult.RECEIVED, "Your refit is waiting. " + waiting));
                }
                return;
            }
            send(p, new RefitResult(RefitResult.RECEIVED, "The host has your refit"
                    + (replaced ? " (it replaces the one before)" : "") + " and will accept or deny it."));
            CampaignLobby.say("Co-op: " + p.name + " sent a refit for " + pd.ship + ". Ctrl+Shift+R to look at it.");
            lastChecked = 0;
        } catch (Throwable t) {
            failed(pd, t);
        }
    }

    private static void keepPending(HostSession s, WingmanConfig cfg, boolean refresh) {
        for (Pending pd : new ArrayList<Pending>(pending.values())) {
            if (pending.get(pd.player.id) != pd) continue;
            if (!live(s, pd.player)) { pending.remove(pd.player.id, pd); continue; }
            if (pd.player.refitRequest != null) continue;
            try {
                if (!cfg.friendRefit || refresh || pd.player.refitWithoutConfirmation) {
                    String why = refusalToPlan(pd.request.memberId, cfg);
                    if (why != null) {
                        if (pending.remove(pd.player.id, pd)) {
                            send(pd.player, new RefitResult(RefitResult.WITHDRAWN, "Your refit was withdrawn: " + why + "."));
                        }
                        continue;
                    }
                    if (!docked()) continue;
                    if (pd.player.refitWithoutConfirmation) accept(pd);
                    else check(pd, member(pd.request.memberId));
                }
            } catch (Throwable t) {
                failed(pd, t);
            }
        }
    }

    private static void failed(Pending pd, Throwable t) {
        Pending current = pending.get(pd.player.id);
        if (current != null && current != pd) return;
        pending.remove(pd.player.id, pd);
        Global.getLogger(FriendRefit.class).warn("Wingman: a refit from " + pd.player.name + " failed", t);
        send(pd.player, new RefitResult(RefitResult.REFUSED, "The host couldn't do that refit."));
    }

    static void deny(Pending pd) {
        if (pd == null || pd.player == null || pending.get(pd.player.id) != pd) return;
        if (!live(HostSession.active(), pd.player)) { pending.remove(pd.player.id, pd); return; }
        if (pd.player.refitRequest != null) return;
        if (pending.remove(pd.player.id, pd)) {
            send(pd.player, new RefitResult(RefitResult.DENIED, "The host denied your refit of " + pd.ship + "."));
        }
    }

    static String accept(Pending pd) {
        if (pd == null || pd.player == null || pending.get(pd.player.id) != pd) return "That request is no longer waiting.";
        if (!live(HostSession.active(), pd.player)) {
            pending.remove(pd.player.id, pd);
            return "That player is no longer connected.";
        }
        if (pd.player.refitRequest != null) return "A newer refit request is waiting to be checked.";
        try {
            String why = refusalToPlan(pd.request.memberId, WingmanConfig.load());
            if (why != null) {
                if (pending.remove(pd.player.id, pd)) {
                    send(pd.player, new RefitResult(RefitResult.WITHDRAWN, "Your refit was withdrawn: " + why + "."));
                }
                return "It can't be done any more: " + why + ".";
            }
            MarketAPI market = dockedMarket();
            if (market == null) return "Dock at a market to accept it: the refit waits until then.";
            FleetMemberAPI m = member(pd.request.memberId);
            check(pd, m);
            if (pending.get(pd.player.id) != pd || !live(HostSession.active(), pd.player) || pd.player.refitRequest != null) {
                return "That request or connection is no longer current.";
            }
            if (pd.plan.isEmpty()) {
                if (pending.remove(pd.player.id, pd)) {
                    send(pd.player, new RefitResult(RefitResult.REFUSED, "Nothing changes in that refit."));
                }
                return "Nothing changes in that refit.";
            }
            if (!pd.problems.isEmpty()) {
                if (pending.remove(pd.player.id, pd)) send(pd.player, new RefitResult(RefitResult.REFUSED, join(pd.problems)));
                return "It no longer passes the checks: " + pd.problems.get(0);
            }
            why = refusalToPlan(pd.request.memberId, WingmanConfig.load());
            if (why != null) {
                if (pending.remove(pd.player.id, pd)) {
                    send(pd.player, new RefitResult(RefitResult.WITHDRAWN, "Your refit was withdrawn: " + why + "."));
                }
                return "It can't be done any more: " + why + ".";
            }
            if (pending.get(pd.player.id) != pd || !live(HostSession.active(), pd.player) || pd.player.refitRequest != null) {
                return "That request or connection is no longer current.";
            }
            if (market != dockedMarket() || member(pd.request.memberId) != m) {
                return "The market or ship changed: review the refit again.";
            }
            if (!pending.remove(pd.player.id, pd)) return "That request is no longer waiting.";
            apply(pd);
            return null;
        } catch (Throwable t) {
            failed(pd, t);
            return "The host couldn't do that refit.";
        }
    }

    /** Every check, on the real campaign: the plan's (items, money, story points, limits) and the game's own. */
    private static void check(Pending pd, FleetMemberAPI m) {
        MarketAPI market = dockedMarket();
        RefitData d = build(m, market);
        pd.plan = new RefitPlan(d, pd.request, new RefitNames(d));
        List<String> problems = new ArrayList<String>(pd.plan.problems); // ordnance points: the game's count below
        RefitRequest r = pd.request;
        ShipVariantAPI v = m.getVariant();
        ShipHullSpecAPI hull = m.getHullSpec();
        for (RefitRequest.Change c : r.changes) {
            if (pd.plan.sameAsBefore(c)) continue;
            if (c.wing) {
                int bay;
                try { bay = Integer.parseInt(c.place); } catch (NumberFormatException e) { problems.add("No such fighter bay."); continue; }
                int builtIn = 0;
                try { builtIn = hull.getBuiltInWings().size(); } catch (Throwable t) { /* none */ }
                if (bay < builtIn) problems.add("Fighter bay " + (bay + 1) + " holds a built-in wing.");
                if (!c.item.isEmpty()) {
                    try { Global.getSettings().getFighterWingSpec(c.item).getId(); } catch (Throwable t) { problems.add("Unknown fighter wing " + c.item + "."); }
                }
                continue;
            }
            WeaponSlotAPI slot = null;
            try {
                int bar = c.place.indexOf('/');
                ShipVariantAPI sv = bar >= 0 ? v.getModuleVariant(c.place.substring(0, bar)) : v;
                slot = sv.getSlot(c.place.substring(bar + 1));
            } catch (Throwable t) { /* none */ }
            if (slot == null || !slot.isWeaponSlot() || slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot() || slot.isHidden() || slot.isStationModule()) {
                problems.add("Slot " + c.place + " can't take a weapon.");
                continue;
            }
            if (c.item.isEmpty()) continue;
            WeaponSpecAPI spec;
            try { spec = Global.getSettings().getWeaponSpec(c.item); } catch (Throwable t) { problems.add("Unknown weapon " + c.item + "."); continue; }
            if (!slot.weaponFits(spec)) problems.add(spec.getWeaponName() + " doesn't fit slot " + c.place + " (" + slot.getSlotSize().name().toLowerCase() + " "
                    + slot.getWeaponType().name().toLowerCase() + ").");
        }
        if (r.groups != null) for (RefitData.Group g : r.groups) for (String id : g.slots) {
            if (!groupable(v, id)) { problems.add("Slot " + id + " can't be in a weapon group."); break; }
        }
        for (String id : r.addMods) {
            try {
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                if (spec.isHidden() || spec.isHiddenEverywhere()) problems.add(spec.getDisplayName() + " can't be fitted.");
                if (hull.getBuiltInMods().contains(id)) problems.add(spec.getDisplayName() + " is built into the hull already.");
            } catch (Throwable t) { problems.add("Unknown hull mod " + id + "."); }
        }
        // The real ordnance cost: the new loadout on a copy of the ship, counted by the game with the host's skills.
        try {
            ShipVariantAPI test = v.clone();
            applyTo(test, pd);
            MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
            pd.op = test.computeOPCost(stats);
            pd.opMax = test.getHullSpec().getOrdnancePoints(stats); // its hull: a loadout switch may change it
            if (pd.op > pd.opMax) problems.add("Ordnance points: " + pd.op + " used, " + pd.opMax + " available.");
        } catch (Throwable t) {
            pd.op = pd.plan.opUsed;
            pd.opMax = d.opMax;
            problems.addAll(pd.plan.opProblems);
        }
        pd.problems = problems;
    }

    /** The request's loadout put on a variant (a copy for the checks, the new one when it is done). */
    private static void applyTo(ShipVariantAPI v, Pending pd) {
        RefitRequest r = pd.request;
        for (RefitRequest.Change c : r.changes) {
            if (pd.plan.sameAsBefore(c)) continue;
            if (c.wing) v.setWingId(Integer.parseInt(c.place), c.item.isEmpty() ? null : c.item);
            else {
                int bar = c.place.indexOf('/');
                ShipVariantAPI target = v;
                if (bar >= 0) { // a module: a copy of its own, so the ship's stays as it is until the new variant is set
                    target = v.getModuleVariant(c.place.substring(0, bar)).clone();
                    v.setModuleVariant(c.place.substring(0, bar), target);
                }
                String slot = c.place.substring(bar + 1);
                target.clearSlot(slot);
                if (!c.item.isEmpty()) target.addWeapon(slot, c.item);
            }
        }
        for (String id : r.removeMods) v.removeMod(id);
        for (String id : r.addMods) v.addMod(id);
        ModCompat.afterHullmods(v); // other mods' loadout switches (a Diable Virtuous selector taken off, say)
        for (String id : r.buildIn) v.addPermaMod(id, true);
        v.setNumFluxVents(r.vents);
        v.setNumFluxCapacitors(r.caps);
        if (r.groups != null) {
            // Only slots that hold a weapon now; the game puts any weapon left out into a group of its own.
            Set<String> armed = new LinkedHashSet<String>();
            for (RefitData.Group g : r.groups) for (String id : g.slots) if (groupable(v, id) && v.getWeaponId(id) != null) armed.add(id);
            List<RefitData.Group> groups = RefitPlan.normalize(r.groups, armed);
            v.getWeaponGroups().clear();
            for (int i = 0; i < groups.size() && i < RefitPlan.MAX_GROUPS; i++) {
                RefitData.Group g = groups.get(i);
                WeaponGroupSpec spec = new WeaponGroupSpec(g.alternating ? WeaponGroupType.ALTERNATING : WeaponGroupType.LINKED);
                spec.setAutofireOnByDefault(g.autofire);
                for (String id : g.slots) spec.addSlot(id);
                v.addWeaponGroup(spec);
            }
        }
    }

    /** A slot a weapon group may hold: any with a real weapon, built-ins too, as the game's own refit screen allows. */
    private static boolean groupable(ShipVariantAPI v, String id) {
        WeaponSlotAPI slot = null;
        try { slot = v.getSlot(id); } catch (Throwable t) { /* none */ }
        return slot != null && !slot.isDecorative() && !slot.isSystemSlot() && !slot.isStationModule();
    }

    /** A request that passed every check, done as the game's refit and market screens would do it. */
    private static void apply(Pending pd) {
        MarketAPI market = dockedMarket();
        FleetMemberAPI m = member(pd.request.memberId);
        RefitPlan plan = pd.plan;
        RefitData d = plan.d;
        RefitRequest r = pd.request;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        CoreUITradeMode mode = tradeMode(market.getPrimaryEntity());
        Map<String, PlayerMarketTransaction> deals = new java.util.LinkedHashMap<String, PlayerMarketTransaction>();

        // Out first (into cargo or storage, or sold), then in (from cargo, storage, or bought).
        for (RefitRequest.Change c : r.changes) {
            if (plan.sameAsBefore(c)) continue;
            String old = plan.before(c);
            if (old == null) continue;
            String to = c.to.isEmpty() ? RefitRequest.CARGO : c.to;
            move(cargoOf(to, pf, market), old, 1);
            RefitData.Market rm = d.market(to);
            if (rm != null) {
                float price = d.sellPrice(rm, old);
                pf.getCargo().getCredits().add(price);
                PlayerMarketTransaction t = deal(deals, market, to, mode);
                move(t.getSold(), old, 1);
                t.setCreditValue(t.getCreditValue() + price);
            }
        }
        for (RefitRequest.Change c : r.changes) {
            if (plan.sameAsBefore(c)) continue;
            String now = plan.after(c);
            if (now == null) continue;
            move(cargoOf(c.from, pf, market), now, -1);
            RefitData.Market rm = d.market(c.from);
            if (rm != null) {
                float price = d.buyPrice(rm, now);
                pf.getCargo().getCredits().subtract(price);
                PlayerMarketTransaction t = deal(deals, market, c.from, mode);
                move(t.getBought(), now, 1);
                t.setCreditValue(t.getCreditValue() - price);
            }
        }
        // The ship: a refit variant of its own (never the shared stock one), with the new loadout.
        ShipVariantAPI nv = m.getVariant().clone();
        nv.setSource(VariantSource.REFIT);
        applyTo(nv, pd);
        if (nv.hasUnassignedWeapons()) nv.assignUnassignedWeapons();
        m.setVariant(nv, false, true);
        m.updateStats();
        // Story points for S-mods, with the bonus experience the game gives for building in.
        for (String id : r.buildIn) {
            try {
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                Global.getSector().getPlayerStats().spendStoryPoints(1, true, null, true, Misc.getBuildInBonusXP(spec, m.getHullSpec().getHullSize()),
                        "Built " + spec.getDisplayName() + " into " + pd.ship + " (refit for " + pd.player.name + ")");
            } catch (Throwable t) {
                Global.getLogger(FriendRefit.class).warn("Wingman: story point for building in " + id + " not spent", t);
            }
        }
        // The markets hear of it as of any trade: prices, stock and standing react as usual.
        for (Map.Entry<String, PlayerMarketTransaction> e : deals.entrySet()) {
            PlayerMarketTransaction t = e.getValue();
            try { market.getSubmarket(e.getKey()).getPlugin().reportPlayerMarketTransaction(t); } catch (Throwable x) { /* that report is missed */ }
            try { Global.getSector().reportPlayerMarketTransaction(t); } catch (Throwable x) { /* that report is missed */ }
            try { ListenerUtil.reportPlayerMarketTransaction(t); } catch (Throwable x) { /* that report is missed */ }
        }
        CampaignLobby.forgetLoadout(m.getId());
        lastSent.remove(pd.player); // the joiner's refit screen gets the new loadout at once
        lastChecked = 0;
        int net = plan.net();
        send(pd.player, new RefitResult(RefitResult.ACCEPTED, "Your refit of " + pd.ship + " is done."));
        CampaignLobby.say("Co-op: refit of " + pd.ship + " for " + pd.player.name + " done"
                + (net > 0 ? ", " + net + " credits paid" : net < 0 ? ", " + (-net) + " credits received" : "")
                + (plan.storyPoints > 0 ? ", " + plan.storyPoints + " story point" + (plan.storyPoints > 1 ? "s" : "") : "") + ".");
        Global.getLogger(FriendRefit.class).info("Wingman: friend refit applied for " + pd.player.name + " on " + pd.ship + " (credits " + (-net) + ", story points " + plan.storyPoints + ")");
    }

    private static PlayerMarketTransaction deal(Map<String, PlayerMarketTransaction> deals, MarketAPI market, String sub, CoreUITradeMode mode) {
        PlayerMarketTransaction t = deals.get(sub);
        if (t == null) { t = new PlayerMarketTransaction(market, market.getSubmarket(sub), mode); deals.put(sub, t); }
        return t;
    }

    private static CargoAPI cargoOf(String id, CampaignFleetAPI pf, MarketAPI market) {
        if (RefitRequest.CARGO.equals(id)) return pf.getCargo();
        if (RefitRequest.STORAGE.equals(id)) return Misc.getStorageCargo(market);
        return market.getSubmarket(id).getCargo();
    }

    /** Add (n > 0) or take (n < 0) items of a key to or from a cargo. */
    private static void move(CargoAPI cargo, String key, int n) {
        String id = key.substring(2);
        boolean wing = key.startsWith("f:");
        if (n > 0) { if (wing) cargo.addFighters(id, n); else cargo.addWeapons(id, n); }
        else { if (wing) cargo.removeFighters(id, -n); else cargo.removeWeapons(id, -n); }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : "\n").append(s);
        return sb.toString();
    }
    /** The host's view of the changes, with the game's item and hull mod names. */
    static List<String> describe(Pending pd) {
        List<String> out = new ArrayList<String>();
        RefitPlan plan = pd.plan;
        RefitData d = plan.d;
        RefitNames names = new RefitNames(d);
        for (RefitRequest.Change c : pd.request.changes) {
            if (plan.sameAsBefore(c)) continue;
            String old = plan.before(c), now = plan.after(c);
            String place;
            if (c.wing) {
                int bay;
                try { bay = Integer.parseInt(c.place) + 1; } catch (NumberFormatException e) { bay = 0; }
                place = "Bay " + (bay > 0 ? String.valueOf(bay) : c.place);
            } else {
                int slash = c.place.indexOf('/');
                place = slash < 0 ? "Slot " + c.place : "Module " + c.place.substring(0, slash) + ", slot " + c.place.substring(slash + 1);
                RefitData.Slot slot = d.slot(c.place);
                if (slot != null && !slot.size.isEmpty() && !slot.type.isEmpty()) {
                    place += " (" + slot.size.toLowerCase() + " " + slot.type.toLowerCase().replace('_', ' ') + ")";
                }
            }
            RefitData.Market from = d.market(c.from);
            String in = now == null ? "empty" : names.item(now) + " (" + (from != null
                    ? "bought at " + names.source(c.from) + ", " + Math.round(d.buyPrice(from, now)) + " cr"
                    : "from " + names.source(c.from)) + ")";
            String to = c.to.isEmpty() ? RefitRequest.CARGO : c.to;
            RefitData.Market destination = d.market(to);
            String outTo = old == null ? "" : "; " + names.item(old) + (destination != null
                    ? " sold at " + names.source(to) + ", " + Math.round(d.sellPrice(destination, old)) + " cr"
                    : " to " + names.source(to));
            out.add(place + ": " + in + outTo);
        }
        for (String id : pd.request.removeMods) out.add("Hull mod off: " + names.mod(id));
        for (String id : pd.request.addMods) out.add("Hull mod on: " + names.mod(id));
        for (String id : pd.request.buildIn) out.add("Built in (S-mod, 1 story point): " + names.mod(id));
        if (pd.request.vents != d.vents) out.add("Flux vents: " + d.vents + " -> " + pd.request.vents);
        if (pd.request.caps != d.caps) out.add("Flux capacitors: " + d.caps + " -> " + pd.request.caps);
        if (plan.groupsChanged()) {
            List<RefitData.Group> groups = plan.groupsAfter();
            Set<String> ungrouped = new LinkedHashSet<String>(plan.armedSlots());
            if (groups.isEmpty()) out.add("Weapon groups: none selected.");
            for (int i = 0; i < groups.size(); i++) {
                RefitData.Group group = groups.get(i);
                out.add("Weapon group " + (i + 1) + ": " + (group.alternating ? "alternating" : "linked")
                        + ", autofire " + (group.autofire ? "on" : "off") + ".");
                for (String slot : group.slots) {
                    ungrouped.remove(slot);
                    out.add("  " + groupSlot(pd, names, slot));
                }
            }
            if (!ungrouped.isEmpty()) {
                out.add("Ungrouped weapons get groups assigned by the game:");
                for (String slot : ungrouped) out.add("  " + groupSlot(pd, names, slot));
            }
        }
        return out;
    }

    private static String groupSlot(Pending pd, RefitNames names, String slot) {
        RefitRequest.Change change = pd.request.change(slot, false);
        String weapon = change == null ? pd.plan.d.weaponIn(slot) : change.item;
        return "Slot " + slot + (weapon == null || weapon.isEmpty() ? "" : ": " + names.item(RefitData.key(RefitData.WEAPON, weapon)));
    }

}
