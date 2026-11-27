package wingman;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.SkillData;

/**
 * Officers linked to players (host side): the host links one of their officers to a friend, and that officer then
 * captains whichever ship the friend picks. After an idea in scardwell15's pull request #1, made save-safe:
 * - the link is a plain tag on the officer ("wingman_player:" and the player's name);
 * - the higher caps are the game's own per-officer settings ($officerMaxLevel and $officerMaxEliteSkills in the
 *   officer's memory, as the game's special sleeper-pod officers have), with what they were kept beside them to put
 *   back on unlinking. The cap is every combat skill the game's officer level-up can teach (14 in vanilla, mods'
 *   own officer skills counted), each of them allowed elite.
 * Both are plain values: the save loads without Wingman, the officer just keeps the tag (and the cap).
 */
final class OfficerLinks {
    private OfficerLinks() {}

    static final String TAG = "wingman_player:";
    /** The game's per-officer level cap (MemFlags.OFFICER_MAX_LEVEL). */
    static final String MAX_LEVEL = "$officerMaxLevel";
    /** What the officer's cap was before linking (-1: not set), to put back on unlinking. */
    static final String MAX_LEVEL_WAS = "$wingman_officerMaxLevelWas";
    /** The game's per-officer elite skill cap (MemFlags.OFFICER_MAX_ELITE_SKILLS). */
    static final String MAX_ELITE = "$officerMaxEliteSkills";
    /** What the officer's elite cap was before linking (-1: not set). */
    static final String MAX_ELITE_WAS = "$wingman_officerMaxEliteWas";

    static String key(String player) { return player == null ? "" : player.trim().toLowerCase(Locale.ROOT); }

    /** The player an officer is linked to (as tagged, lower case), or null. */
    static String linkedTo(PersonAPI p) {
        if (p == null) return null;
        for (String t : p.getTags()) if (t.startsWith(TAG) && t.length() > TAG.length()) return t.substring(TAG.length());
        return null;
    }

    /** The host's officers that can be linked: real officers in the player fleet (not AI cores, not unremovable). */
    static List<PersonAPI> officers() {
        List<PersonAPI> out = new ArrayList<PersonAPI>();
        try {
            CampaignFleetAPI f = Global.getSector().getPlayerFleet();
            for (OfficerDataAPI d : f.getFleetData().getOfficersCopy()) {
                PersonAPI p = d.getPerson();
                if (p != null && !p.isPlayer() && !p.isDefault() && !p.isAICore() && !Misc.isUnremovable(p)) out.add(p);
            }
        } catch (Throwable t) { /* no fleet */ }
        return out;
    }

    /** The officer linked to this player, if still in the fleet. */
    static PersonAPI officerOf(String player) {
        String k = key(player);
        for (PersonAPI p : officers()) if (k.equals(linkedTo(p))) return p;
        return null;
    }

    /** Links an officer to a player (taking any earlier link of either off first). */
    static void link(String player, PersonAPI officer) {
        String k = key(player);
        if (k.isEmpty() || officer == null) return;
        PersonAPI had = officerOf(player);
        if (had != null && had != officer) unlink(had);
        if (linkedTo(officer) != null) unlink(officer);
        officer.addTag(TAG + k);
        if (WingmanConfig.load().linkedOfficerLevelCap) raiseCap(officer);
    }

    /** Takes the link off, and puts the officer's caps back as they were. */
    static void unlink(PersonAPI officer) {
        if (officer == null) return;
        for (String t : new ArrayList<String>(officer.getTags())) if (t.startsWith(TAG)) officer.removeTag(t);
        restoreCap(officer);
    }

    /** The officer's caps as they were before Wingman raised them (if it did). */
    private static void restoreCap(PersonAPI officer) {
        MemoryAPI m = officer.getMemoryWithoutUpdate();
        putBack(m, MAX_LEVEL, MAX_LEVEL_WAS);
        putBack(m, MAX_ELITE, MAX_ELITE_WAS);
    }

    private static void putBack(MemoryAPI m, String key, String wasKey) {
        if (!m.contains(wasKey)) return;
        int was = m.getInt(wasKey);
        if (was < 0) m.unset(key);
        else m.set(key, was);
        m.unset(wasKey);
    }

    /** Skills the game's officer level-up can teach, counted once (skills don't change while the game runs). */
    private static int combatSkills = -1;

    /**
     * How many skills the game's officer level-up can offer: combat officer skills in an aptitude, not deprecated,
     * NPC-only or player-only (as OfficerLevelupPluginImpl picks them). 14 in vanilla; mods' own officer skills count.
     */
    static int combatSkills() {
        if (combatSkills >= 0) return combatSkills;
        int n = 0;
        try {
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (String ap : SkillData.getAptitudes().keySet()) {
                SkillData.SkillsForAptitude sk = SkillData.getSkills(ap);
                if (sk == null || sk.tiers == null) continue;
                for (List<SkillSpecAPI> tier : sk.tiers) for (SkillSpecAPI s : tier) if (teachable(s) && seen.add(s.getId())) n++;
            }
        } catch (Throwable t) {
            n = 0;
        }
        if (n <= 0) {
            try {
                for (String id : Global.getSettings().getSkillIds()) {
                    SkillSpecAPI s = Global.getSettings().getSkillSpec(id);
                    if (teachable(s) && !s.hasTag("npc_only") && s.getGoverningAptitudeId() != null && !s.getGoverningAptitudeId().isEmpty()) n++;
                }
            } catch (Throwable t) { /* none counted */ }
        }
        combatSkills = n;
        Global.getLogger(OfficerLinks.class).info("Wingman: a friend's officer can learn " + n + " combat skills");
        return n;
    }

    static boolean teachable(SkillSpecAPI s) {
        return s != null && !s.isAptitudeEffect() && s.isCombatOfficerSkill() && !s.hasTag("deprecated") && !s.hasTag("player_only");
    }

    /** The level a linked officer may reach: one skill per level, so every combat skill there is. */
    static int levelCap() {
        int n = combatSkills();
        int normal = 5;
        try { normal = (int) Global.getSettings().getFloat("officerMaxLevel"); } catch (Throwable t) { /* 5 */ }
        if (n <= 0) {
            try { return Global.getSettings().getInt("playerMaxLevel"); } catch (Throwable t) { return 15; }
        }
        return Math.max(n, normal);
    }

    /**
     * A linked officer can learn every combat skill, and have every one elite (made elite in the host's officer
     * screen, a story point each, as the game does it). The game's own per-officer caps; what they were is kept to put
     * back. Never below what the officer had, or below what it already is.
     */
    private static void raiseCap(PersonAPI officer) {
        try {
            MemoryAPI m = officer.getMemoryWithoutUpdate();
            int level = officer.getStats() == null ? 0 : officer.getStats().getLevel();
            int elite = 0;
            try { elite = Misc.getNumEliteSkills(officer); } catch (Throwable t) { /* 0 */ }
            int cap = levelCap();
            hold(m, MAX_LEVEL, MAX_LEVEL_WAS, Math.max(cap, level));
            if (combatSkills() > 0) hold(m, MAX_ELITE, MAX_ELITE_WAS, Math.max(cap, elite));
        } catch (Throwable t) {
            Global.getLogger(OfficerLinks.class).warn("Wingman: a linked officer's level cap couldn't be raised", t);
        }
    }

    /** Sets the cap to the higher of `want` and what it was before Wingman (kept beside it the first time). */
    private static void hold(MemoryAPI m, String key, String wasKey, int want) {
        if (!m.contains(wasKey)) m.set(wasKey, m.contains(key) ? m.getInt(key) : -1);
        int v = Math.max(want, m.getInt(wasKey));
        if (!m.contains(key) || m.getInt(key) != v) m.set(key, v);
    }

    /**
     * Each second on the host: linked officers' caps as the setting says (also brings officers raised by an older
     * Wingman, to the player's max level, to the new cap, and puts them back if the setting was turned off).
     */
    static void keepCaps(boolean on) {
        for (PersonAPI o : officers()) {
            if (linkedTo(o) == null) continue;
            try {
                if (on) raiseCap(o);
                else restoreCap(o);
            } catch (Throwable t) { /* next one */ }
        }
    }

    /** The officer's entry in the player fleet (what levels it up), or null. */
    static com.fs.starfarer.api.characters.OfficerDataAPI dataOf(PersonAPI officer) {
        try {
            for (OfficerDataAPI d : Global.getSector().getPlayerFleet().getFleetData().getOfficersCopy()) if (d.getPerson() == officer) return d;
        } catch (Throwable t) { /* no fleet */ }
        return null;
    }

    /**
     * Each second on the host: each friend's linked officer, if it can level up, offered to that friend with the skills
     * the game picks for it (as the host's own officer screen would show); a friend's pick, if it is one of those,
     * levels it up through the game's own level-up. Returns a line for the host, or null.
     */
    static String levelUps(wingman.net.HostSession s, boolean capRaised) {
        String said = null;
        keepCaps(capRaised);

        for (wingman.net.HostSession.Player p : s.getPlayers()) {
            try {
                PersonAPI o = officerOf(p.name);
                OfficerDataAPI d = o == null ? null : dataOf(o);
                String pick = p.officerPick;
                p.officerPick = null;
                if (d != null && pick != null && d.canLevelUp() && d.getSkillPicks() != null && d.getSkillPicks().contains(pick)) {
                    d.levelUp(pick);
                    p.officerOffered = ""; // the next level's choices, if any, go out below
                    said = p.name + " picked " + OfficerChoice.name(pick) + " for " + o.getNameString() + " (now level " + o.getStats().getLevel() + ").";
                }
                wingman.net.Msg.W w = new wingman.net.Msg.W();
                String key = "";
                List<String> picks = null;
                if (d != null && d.canLevelUp()) {
                    if (!d.isMadePicks()) d.makeSkillPicks();
                    picks = d.getSkillPicks();
                }
                if (picks != null && !picks.isEmpty()) { // nothing left to learn: no level-up to offer
                    int n = Math.min(8, picks.size());
                    w.out.writeUTF(o.getNameString());
                    w.out.writeInt(o.getStats().getLevel() + 1);
                    w.out.writeByte(n);
                    for (int i = 0; i < n; i++) w.out.writeUTF(picks.get(i));
                    key = o.getId() + ":" + o.getStats().getLevel() + ":" + picks;
                } else {
                    w.out.writeUTF("");
                    w.out.writeInt(0);
                    w.out.writeByte(0);
                }
                if (!key.equals(p.officerOffered)) {
                    p.officerOffered = key;
                    p.conn.send(wingman.net.Msg.OFFICER_LEVEL, w.done());
                }
            } catch (Throwable t) {
                Global.getLogger(OfficerLinks.class).warn("Wingman: " + p.name + "'s officer level-up failed", t);
            }
        }
        return said;
    }

    /**
     * The player picked this ship: their linked officer becomes its captain. Whoever captained it takes the ship the
     * officer leaves, so nobody is left out. Not onto the host's own ship, an automated one, or one whose captain
     * can't be moved. Returns a line for the host, or null if nothing moved.
     */
    static String follow(String player, FleetMemberAPI ship) {
        return move(player, ship);
    }

    /** A move made while a screen was open, whose fleet view is refreshed once it closes. */
    private static boolean syncWaiting;

    /** A screen of the game's own (fleet, refit, character, cargo...) is open over the map. */
    static boolean screenUp() {
        try {
            com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
            return ui != null && ui.getCurrentCoreTab() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Each frame: once the screen is closed, the fleet view a move made under it is refreshed. (Refreshing it with
     * the screen open rebuilds the officer rows under the mouse, and the pop-up of the one hovered stays stuck.)
     */
    static void afterScreen() {
        if (!syncWaiting || screenUp()) return;
        syncWaiting = false;
        try { Global.getSector().getPlayerFleet().getFleetData().setSyncNeeded(); } catch (Throwable t) { /* no fleet */ }
    }

    private static String move(String player, FleetMemberAPI ship) {
        try {
            PersonAPI officer = officerOf(player);
            if (officer == null || ship == null) return null;
            PersonAPI current = ship.getCaptain();
            if (current == officer) return null;
            if (current != null && !current.isDefault() && (current.isPlayer() || current.isAICore() || Misc.isUnremovable(current))) return null;
            if (Misc.isAutomated(ship)) return null;
            CampaignFleetAPI f = Global.getSector().getPlayerFleet();
            FleetMemberAPI left = null;
            for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) if (m.getCaptain() == officer) left = m;
            ship.setCaptain(officer);
            if (left != null) left.setCaptain(current != null && !current.isDefault() ? current : null);
            if (screenUp()) syncWaiting = true; // the captains move now; the open screen's rows are left alone
            else f.getFleetData().setSyncNeeded();
            return officer.getNameString() + " (" + player + "'s officer) takes command of " + CampaignLobby.label(ship)
                    + (left != null && current != null && !current.isDefault() ? "; " + current.getNameString() + " moves to " + CampaignLobby.label(left) : "") + ".";
        } catch (Throwable t) {
            Global.getLogger(OfficerLinks.class).warn("Wingman: a linked officer couldn't move to " + player + "'s ship", t);
            return null;
        }
    }
}
