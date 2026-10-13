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
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.SkillsChangeRemoveVentsCapsEffect;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.RefitData;

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
    private static String refusal(HostSession.Player p, String memberId, WingmanConfig cfg) {
        if (!cfg.friendRefit) return "the host has switched friend refits off";
        if (dockedMarket() == null) return "the host isn't docked at a market";
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
                String ask = p.refitAsk;
                if (ask == null) continue;
                p.refitAsk = null;
                if (ask.isEmpty()) { open.remove(p.id); lastSent.remove(p.id); continue; }
                String why = refusal(p, ask, cfg);
                if (why != null) {
                    p.conn.send(Msg.NOTICE, Msg.utf("Can't refit: " + why + "."));
                    open.remove(p.id);
                    continue;
                }
                open.put(p.id, ask);
                lastSent.remove(p.id);
                lastChecked = 0; // send it now
            }
            if (open.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - lastChecked < 1000) return; // changes (a purchase, the cargo, the credits) show within a second
            lastChecked = now;
            for (Iterator<Map.Entry<Integer, String>> it = open.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Integer, String> e = it.next();
                HostSession.Player p = playerById(s, e.getKey());
                if (p == null) { it.remove(); lastSent.remove(e.getKey()); continue; }
                String why = refusal(p, e.getValue(), cfg);
                if (why != null) { // the host left the market, or the pick changed: the joiner's screen closes on the roster
                    it.remove();
                    lastSent.remove(p.id);
                    continue;
                }
                byte[] data = build(member(e.getValue()), dockedMarket()).encode();
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

    /** The host went into a battle: no refit screen stays open. */
    static void closeAll() {
        open.clear();
        lastSent.clear();
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
        d.market = market.getName() == null ? "" : market.getName();
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
        try {
            if (Misc.playerHasStorageAccess(market)) {
                d.hasStorage = true;
                items(Misc.getStorageCargo(market), d.storage, keys);
            }
        } catch (Throwable t) { d.hasStorage = false; d.storage.clear(); }

        SubmarketPlugin.TransferAction buy = SubmarketPlugin.TransferAction.PLAYER_BUY, sell = SubmarketPlugin.TransferAction.PLAYER_SELL;
        CoreUIAPI ui = new TradeUi(tradeMode(market.getPrimaryEntity()));
        List<SubmarketPlugin> plugins = new ArrayList<SubmarketPlugin>();
        for (SubmarketAPI sub : market.getSubmarketsCopy()) {
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
}
