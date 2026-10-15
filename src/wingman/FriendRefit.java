package wingman;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
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
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.RefitData;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;
import wingman.net.RefitResult;

/**
 * Friend refit, host side (see docs/FRIEND_REFIT_DESIGN.md). While the host is docked at a market it can trade
 * with, joiners are told so in the roster (Roster.refitMarket) and get a Refit button for the ship they picked.
 * When one opens their refit screen (Msg.REFIT_ASK), the host checks they may, then sends what the screen shows
 * (Msg.REFIT_DATA), built only from the host's campaign; again whenever it changes, until they close it.
 *
 * Nothing here changes the host's campaign: this only reads it. Runs on the game thread, from CampaignLobby.
 */
final class FriendRefit {
    private FriendRefit() {}

    /** Players with the refit screen open: player id -> member id. */
    private static final Map<Integer, String> open = new HashMap<Integer, String>();
    /** What each of them was last sent, to send again only on a change. */
    private static final Map<Integer, byte[]> lastSent = new HashMap<Integer, byte[]>();
    private static long lastChecked, lastDiag;
    private static boolean broken;

    /** The game's submarkets ask "may the player trade here?" of the core screen; this answers as the dialog's trade mode. */
    private static final class TradeUi implements CoreUIAPI {
        private final CoreUITradeMode mode;
        TradeUi(CoreUITradeMode mode) { this.mode = mode; }
        public CoreUITradeMode getTradeMode() { return mode; }
        public HintPanelAPI getHintPanel() { return null; }
    }

    /** The market the host is docked at, if refitting is possible there now; else null. */
    static MarketAPI dockedMarket() {
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
    private static String refusal(HostSession.Player p, String memberId, WingmanConfig cfg) { return refusal(p, memberId, cfg, null); }

    /**
     * Why this player can't have the refit screen open on that member, or null. Docking isn't needed: away from a
     * market the joiner plans (the ship, the host's cargo, credits and story points are shown), and sends once the
     * host docks.
     */
    private static String refusalToPlan(HostSession.Player p, String memberId, WingmanConfig cfg) {
        if (!cfg.friendRefit) return "the host has switched friend refits off";
        return shipRefusal(p, memberId);
    }

    /**
     * acceptedAt: for a refit the host accepted under a core screen, the market it was docked at then. It no longer
     * needs to be docked: the host said yes while it was, and that market is still at hand.
     */
    private static String refusal(HostSession.Player p, String memberId, WingmanConfig cfg, MarketAPI acceptedAt) {
        if (!cfg.friendRefit) return "the host has switched friend refits off";
        if (dockedMarket() == null && acceptedAt == null) return "the host isn't docked at a market";
        return shipRefusal(p, memberId);
    }

    private static String shipRefusal(HostSession.Player p, String memberId) {
        if (!memberId.equals(WingmanAPI.reservedShip(p.name))) return "you can only refit the ship you picked";
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
                byte[] rq = p.refitRequest;
                if (rq != null) { p.refitRequest = null; receive(p, rq, cfg); }
                String ask = p.refitAsk;
                if (ask == null) continue;
                p.refitAsk = null;
                if (ask.isEmpty()) { open.remove(p.id); lastSent.remove(p.id); continue; }
                String why = refusalToPlan(p, ask, cfg);
                if (why != null) {
                    p.conn.send(Msg.NOTICE, Msg.utf("Can't refit: " + why + "."));
                    open.remove(p.id);
                    continue;
                }
                open.put(p.id, ask);
                lastSent.remove(p.id);
                lastChecked = 0; // send it now
            }
            if (open.isEmpty() && pending.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - lastChecked < 1000) return; // changes (a purchase, the cargo, the credits) show within a second
            lastChecked = now;
            keepPending(s, cfg);
            for (Iterator<Map.Entry<Integer, String>> it = open.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Integer, String> e = it.next();
                HostSession.Player p = playerById(s, e.getKey());
                if (p == null) { it.remove(); lastSent.remove(e.getKey()); continue; }
                String why = refusalToPlan(p, e.getValue(), cfg);
                if (why != null) { // the pick changed, or the ship can't be refitted: the joiner's screen closes on the roster
                    it.remove();
                    lastSent.remove(p.id);
                    continue;
                }
                byte[] data = build(member(e.getValue()), dockedMarket()).encode(); // away from a market: no market, no storage
                if (Arrays.equals(data, lastSent.get(p.id))) continue;
                lastSent.put(p.id, data);
                p.conn.send(Msg.REFIT_DATA, data);
            }
        } catch (Throwable t) {
            broken = true;
            open.clear();
            lastSent.clear();
            Global.getLogger(FriendRefit.class).error("Wingman: friend refits stopped working and are switched off until the game is restarted", t);
        }
    }

    /** The host went into a battle: no refit screen stays open, and no request waits. */
    static void closeAll() {
        open.clear();
        lastSent.clear();
        for (Pending p : pending.values()) send(p.player, new RefitResult(RefitResult.WITHDRAWN, "The host went into a battle."));
        pending.clear();
    }

    private static HostSession.Player playerById(HostSession s, int id) {
        for (HostSession.Player p : s.getPlayers()) if (p.id == id) return p;
        return null;
    }

    // ---- what the joiner's refit screen shows ----

    static RefitData build(FleetMemberAPI m, MarketAPI market) {
        RefitData d = new RefitData();
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        ShipVariantAPI v = m.getVariant();
        d.market = market == null ? "" : market.getName() == null || market.getName().isEmpty() ? "this market" : market.getName();
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

        Set<String> keys = new LinkedHashSet<String>(); // every item named below, for its price
        for (String slot : v.getFittedWeaponSlots()) {
            String id = v.getWeaponId(slot);
            if (id == null) continue;
            try {
                WeaponSlotAPI ws = v.getSlot(slot);
                if (ws != null && (ws.isBuiltIn() || ws.isDecorative() || ws.isSystemSlot() || ws.isHidden() || ws.isStationModule())) continue;
            } catch (Throwable t) { /* listed */ }
            d.fitSlots.add(slot);
            d.fitWeapons.add(id);
            keys.add(RefitData.key(RefitData.WEAPON, id));
        }
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
        try {
            for (String id : Global.getSector().getCharacterData().getHullMods()) {
                if (d.knownHullmods.size() >= RefitData.MAX_MODS) break;
                d.knownHullmods.add(id);
            }
        } catch (Throwable t) { /* none listed */ }
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

    // ---- refit requests: received, checked, shown to the host, accepted or denied ----

    /** A request waiting for the host: whose, for which ship, and what the checks found (refreshed each second). */
    static final class Pending {
        HostSession.Player player;
        RefitRequest request;
        String ship = "";
        long at;
        RefitPlan plan;
        List<String> problems = new ArrayList<String>();
        /** The ordnance points of the new loadout as the game counts them on a copy of the ship, and the most it has. */
        int op, opMax;
        /** The host accepted it with a core screen open: it is done the moment that screen closes. */
        boolean acceptOnClose;
        /** Then: the market docked at, and the dialog that was up (its closing closes its core screen too). */
        MarketAPI acceptedAt;
        InteractionDialogAPI dialogAtAccept;
    }

    /** Where a request is done: the market docked at, else the one a refit accepted under a core screen was accepted at. */
    private static MarketAPI marketFor(Pending pd) {
        MarketAPI m = dockedMarket();
        return m != null ? m : pd != null && pd.acceptOnClose ? pd.acceptedAt : null;
    }

    private static MarketAPI acceptedAt(Pending pd) { return pd.acceptOnClose ? pd.acceptedAt : null; }

    /**
     * The host accepts with the fleet, cargo or refit screen open. That screen keeps its own copy of the ship, which
     * would undo a change made under it, so the refit is done as soon as it closes (applyWaiting).
     */
    static void acceptOnClose(Pending pd) {
        pd.acceptOnClose = true;
        pd.acceptedAt = dockedMarket();
        pd.dialogAtAccept = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        send(pd.player, new RefitResult(RefitResult.RECEIVED, "The host accepted your refit of " + pd.ship + ". It is done as soon as they close their refit screen."));
    }

    /**
     * Each frame: refits accepted under a core screen are done once it has closed. Then, if the host wishes, the
     * refit screen opens again on the (last) ship refitted, from the market's dialog as the game's own rules open it.
     */
    static void applyWaiting() {
        if (broken || pending.isEmpty()) return;
        try {
            com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null) return;
            InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
            long now = System.currentTimeMillis();
            if (Dbg.on && now - lastDiag > 1000) {
                for (Pending pd : pending.values()) {
                    if (!pd.acceptOnClose) continue;
                    lastDiag = now;
                    Dbg.info(FriendRefit.class, "Wingman: refit waiting on the screen closing: core tab " + ui.getCurrentCoreTab() + ", showing dialog "
                            + ui.isShowingDialog() + ", showing menu " + ui.isShowingMenu() + ", dialog " + (dialog == null ? "none" : dialog == pd.dialogAtAccept ? "same" : "another"));
                    break;
                }
            }
            FleetMemberAPI last = null;
            boolean reopenable = false;
            for (Pending pd : new ArrayList<Pending>(pending.values())) {
                if (!pd.acceptOnClose) continue;
                // The screen has closed: no core tab, or the dialog it was opened over is gone (taking its screen with it).
                boolean closed = ui.getCurrentCoreTab() == null || (pd.dialogAtAccept != null && dialog != pd.dialogAtAccept);
                if (!closed) continue;
                reopenable = dialog != null && dialog == pd.dialogAtAccept;
                String why = accept(pd);
                if (why != null) CampaignLobby.say("Co-op: the refit for " + pd.player.name + " couldn't be done: " + why);
                else last = member(pd.request.memberId);
            }
            if (last != null && reopenable && WingmanConfig.load().friendRefitReopen) reopen(last);
        } catch (Throwable t) {
            Global.getLogger(FriendRefit.class).warn("Wingman: a friend's refit accepted under the refit screen failed", t);
        }
    }

    /** The refit screen again, on that ship, opened by the market's dialog (as its OpenCoreTab rule does). */
    private static void reopen(FleetMemberAPI m) {
        final InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        if (dialog == null || !(dialog.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl)) return;
        final Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> memory =
                ((com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl) dialog.getPlugin()).getMemoryMap();
        dialog.getOptionPanel().clearOptions();
        dialog.getVisualPanel().showCore(com.fs.starfarer.api.campaign.CoreUITabId.REFIT, dialog.getInteractionTarget(), m,
                tradeMode(dialog.getInteractionTarget()), new com.fs.starfarer.api.campaign.CoreInteractionListener() {
                    public void coreUIDismissed() {
                        // as the game's own OpenCoreTab: the dialog's options come back
                        com.fs.starfarer.api.impl.campaign.rulecmd.FireAll.fire(null, dialog, memory, "PopulateOptions");
                    }
                });
    }

    /** True while the host is docked where a refit can be done. */
    static boolean docked() { return dockedMarket() != null; }

    /** Waiting requests by player id, oldest first. */
    static final Map<Integer, Pending> pending = new java.util.LinkedHashMap<Integer, Pending>();

    private static void send(HostSession.Player p, RefitResult r) {
        try { p.conn.send(Msg.REFIT_RESULT, r.encode()); } catch (Throwable t) { /* gone */ }
    }

    private static void receive(HostSession.Player p, byte[] data, WingmanConfig cfg) {
        RefitRequest r;
        try { r = RefitRequest.decode(data); } catch (java.io.IOException e) { return; } // garbled: nothing to answer
        String why = refusal(p, r.memberId, cfg);
        if (why != null) { send(p, new RefitResult(RefitResult.REFUSED, "Can't refit: " + why + ".")); return; }
        Pending pd = new Pending();
        pd.player = p;
        pd.request = r;
        pd.at = System.currentTimeMillis();
        FleetMemberAPI m = member(r.memberId);
        pd.ship = CampaignLobby.label(m);
        check(pd, m);
        if (pd.plan.isEmpty()) { send(p, new RefitResult(RefitResult.REFUSED, "Nothing changes in that refit.")); return; }
        if (!pd.problems.isEmpty()) { // the design: a request that fails a check is refused with the reason
            send(p, new RefitResult(RefitResult.REFUSED, join(pd.problems)));
            return;
        }
        boolean replaced = pending.remove(p.id) != null;
        pending.put(p.id, pd);
        send(p, new RefitResult(RefitResult.RECEIVED, "The host has your refit" + (replaced ? " (it replaces the one before)" : "") + " and will accept or deny it."));
        CampaignLobby.say("Co-op: " + p.name + " sent a refit for " + pd.ship + ". Ctrl+Shift+R to look at it.");
    }

    /** Each second: requests that can't be done any more are withdrawn; the others are checked again. */
    private static void keepPending(HostSession s, WingmanConfig cfg) {
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext();) {
            Pending pd = it.next();
            if (playerById(s, pd.player.id) != pd.player) { it.remove(); continue; }
            // Away from a market a request waits (it is checked again at the next one); it is withdrawn only when the
            // ship can't be refitted at all any more.
            String why = refusalToPlan(pd.player, pd.request.memberId, cfg);
            if (why != null) {
                it.remove();
                send(pd.player, new RefitResult(RefitResult.WITHDRAWN, "Your refit was withdrawn: " + why + "."));
                continue;
            }
            check(pd, member(pd.request.memberId));
        }
    }

    /** Every check, on the real campaign: the plan's (items, money, story points, limits) and the game's own. */
    static void check(Pending pd, FleetMemberAPI m) {
        MarketAPI market = marketFor(pd);
        RefitData d = build(m, market);
        pd.plan = new RefitPlan(d, pd.request, new Names(d));
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
            try { slot = v.getSlot(c.place); } catch (Throwable t) { /* none */ }
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
            pd.opMax = hull.getOrdnancePoints(stats);
            if (pd.op > pd.opMax) problems.add("Ordnance points: " + pd.op + " used, " + pd.opMax + " available.");
        } catch (Throwable t) {
            pd.op = pd.plan.opUsed;
            pd.opMax = d.opMax;
            problems.addAll(pd.plan.opProblems);
        }
        pd.problems = problems;
    }

    /** The request's loadout put on a variant (a copy for the checks, the new one on accepting). */
    private static void applyTo(ShipVariantAPI v, Pending pd) {
        RefitRequest r = pd.request;
        for (RefitRequest.Change c : r.changes) {
            if (pd.plan.sameAsBefore(c)) continue;
            if (c.wing) v.setWingId(Integer.parseInt(c.place), c.item.isEmpty() ? null : c.item);
            else {
                v.clearSlot(c.place);
                if (!c.item.isEmpty()) v.addWeapon(c.place, c.item);
            }
        }
        for (String id : r.removeMods) v.removeMod(id);
        for (String id : r.addMods) v.addMod(id);
        for (String id : r.buildIn) v.addPermaMod(id, true);
        v.setNumFluxVents(r.vents);
        v.setNumFluxCapacitors(r.caps);
    }

    /** The host denies a request. */
    static void deny(Pending pd) {
        pending.remove(pd.player.id);
        send(pd.player, new RefitResult(RefitResult.DENIED, "The host denied your refit of " + pd.ship + "."));
    }

    /**
     * The host accepts a request: checked once more, then done as the game's refit and market screens would do it.
     * Returns null when done, else what stopped it (the joiner is told too).
     */
    static String accept(Pending pd) {
        WingmanConfig cfg = WingmanConfig.load();
        if (dockedMarket() == null && acceptedAt(pd) == null) return "Dock at a market to accept it: the refit waits until then.";
        String why = refusal(pd.player, pd.request.memberId, cfg, acceptedAt(pd));
        if (why != null) {
            pending.remove(pd.player.id);
            send(pd.player, new RefitResult(RefitResult.WITHDRAWN, "Your refit was withdrawn: " + why + "."));
            return "It can't be done any more: " + why + ".";
        }
        FleetMemberAPI m = member(pd.request.memberId);
        check(pd, m);
        if (!pd.problems.isEmpty()) {
            pending.remove(pd.player.id);
            send(pd.player, new RefitResult(RefitResult.REFUSED, join(pd.problems)));
            return "It no longer passes the checks: " + pd.problems.get(0);
        }
        MarketAPI market = marketFor(pd);
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
        pending.remove(pd.player.id);
        CampaignLobby.forgetLoadout(m.getId());
        lastSent.remove(pd.player.id); // the joiner's refit screen gets the new loadout at once
        lastChecked = 0;
        int net = plan.net();
        send(pd.player, new RefitResult(RefitResult.ACCEPTED, "The host accepted your refit of " + pd.ship + "."));
        CampaignLobby.say("Co-op: refit of " + pd.ship + " for " + pd.player.name + " done"
                + (net > 0 ? ", " + net + " credits paid" : net < 0 ? ", " + (-net) + " credits received" : "")
                + (plan.storyPoints > 0 ? ", " + plan.storyPoints + " story point" + (plan.storyPoints > 1 ? "s" : "") : "") + ".");
        Global.getLogger(FriendRefit.class).info("Wingman: friend refit applied for " + pd.player.name + " on " + pd.ship + " (credits " + (-net) + ", story points " + plan.storyPoints + ")");
        return null;
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

    /** The host's view of a request, line by line, with names. */
    static List<String> describe(Pending pd) {
        List<String> out = new ArrayList<String>();
        RefitPlan plan = pd.plan;
        Names n = new Names(plan.d);
        ShipHullSpecAPI hull = null;
        try { hull = Global.getSettings().getHullSpec(plan.d.hullId); } catch (Throwable t) { /* plain ids */ }
        for (RefitRequest.Change c : pd.request.changes) {
            if (plan.sameAsBefore(c)) continue;
            String old = plan.before(c), now = plan.after(c);
            String place = c.wing ? "Bay " + (Integer.parseInt(c.place) + 1) : "Slot " + c.place + slotKind(hull, c.place);
            String in = now == null ? "empty" : n.item(now) + " (" + (plan.d.market(c.from) != null ? "bought at " + n.source(c.from) + ", "
                    + Math.round(plan.d.buyPrice(plan.d.market(c.from), now)) + " cr" : "from " + n.source(c.from)) + ")";
            String outTo = old == null ? "" : ";  " + n.item(old) + (plan.d.market(c.to) != null ? " sold at " + n.source(c.to) + ", "
                    + Math.round(plan.d.sellPrice(plan.d.market(c.to), old)) + " cr" : " to " + n.source(c.to.isEmpty() ? RefitRequest.CARGO : c.to));
            out.add(place + ":  " + in + outTo);
        }
        for (String id : pd.request.removeMods) out.add("Hull mod off:  " + n.mod(id));
        for (String id : pd.request.addMods) out.add("Hull mod on:  " + n.mod(id));
        for (String id : pd.request.buildIn) out.add("Built in (S-mod, 1 story point):  " + n.mod(id));
        if (pd.request.vents != plan.d.vents) out.add("Flux vents:  " + plan.d.vents + " -> " + pd.request.vents);
        if (pd.request.caps != plan.d.caps) out.add("Flux capacitors:  " + plan.d.caps + " -> " + pd.request.caps);
        return out;
    }

    private static String slotKind(ShipHullSpecAPI hull, String slotId) {
        try {
            WeaponSlotAPI s = hull.getWeaponSlotAPI(slotId);
            return " (" + s.getSlotSize().name().toLowerCase() + " " + s.getWeaponType().name().toLowerCase().replace('_', ' ') + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Names from this game's specs, and the submarkets' as the host's data has them. */
    static final class Names implements RefitPlan.Names {
        private final RefitData d;
        Names(RefitData d) { this.d = d; }
        public String item(String key) {
            String id = key.length() > 2 ? key.substring(2) : key;
            try {
                return key.startsWith("f:") ? Global.getSettings().getFighterWingSpec(id).getWingName() + " LPC" : Global.getSettings().getWeaponSpec(id).getWeaponName();
            } catch (Throwable t) {
                return id;
            }
        }
        public String mod(String id) {
            try { return Global.getSettings().getHullModSpec(id).getDisplayName(); } catch (Throwable t) { return id; }
        }
        public String source(String id) {
            if (RefitRequest.CARGO.equals(id)) return "cargo";
            if (RefitRequest.STORAGE.equals(id)) return "storage";
            RefitData.Market m = d == null ? null : d.market(id);
            if (m != null) return m.name;
            // a submarket that isn't here (a plan made at another market): its id, made readable ("open_market": "Open market")
            String s = id.replace('_', ' ').trim();
            return s.isEmpty() ? "a market" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
