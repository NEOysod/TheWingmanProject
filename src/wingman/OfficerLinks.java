package wingman;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Officers linked to players (host side): the host links one of their officers to a friend, and that officer then
 * captains whichever ship the friend picks. After an idea in scardwell15's pull request #1, made save-safe:
 * - the link is a plain tag on the officer ("wingman_player:" and the player's name);
 * - the higher level cap is the game's own per-officer setting ($officerMaxLevel in the officer's memory, as the
 *   game's special sleeper-pod officers have), with what it was kept beside it to put back on unlinking.
 * Both are plain values: the save loads without Wingman, the officer just keeps the tag (and the cap).
 */
final class OfficerLinks {
    private OfficerLinks() {}

    static final String TAG = "wingman_player:";
    /** The game's per-officer level cap (MemFlags.OFFICER_MAX_LEVEL). */
    static final String MAX_LEVEL = "$officerMaxLevel";
    /** What the officer's cap was before linking (-1: not set), to put back on unlinking. */
    static final String MAX_LEVEL_WAS = "$wingman_officerMaxLevelWas";

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

    /** Takes the link off, and puts the officer's level cap back as it was. */
    static void unlink(PersonAPI officer) {
        if (officer == null) return;
        for (String t : new ArrayList<String>(officer.getTags())) if (t.startsWith(TAG)) officer.removeTag(t);
        MemoryAPI m = officer.getMemoryWithoutUpdate();
        if (m.contains(MAX_LEVEL_WAS)) {
            int was = m.getInt(MAX_LEVEL_WAS);
            if (was < 0) m.unset(MAX_LEVEL);
            else m.set(MAX_LEVEL, was);
            m.unset(MAX_LEVEL_WAS);
        }
    }

    /** A linked officer can level as far as a player character can (the game's own per-officer cap). */
    private static void raiseCap(PersonAPI officer) {
        try {
            int cap = Global.getSettings().getInt("playerMaxLevel");
            MemoryAPI m = officer.getMemoryWithoutUpdate();
            if (!m.contains(MAX_LEVEL_WAS)) m.set(MAX_LEVEL_WAS, m.contains(MAX_LEVEL) ? m.getInt(MAX_LEVEL) : -1);
            if (!m.contains(MAX_LEVEL) || m.getInt(MAX_LEVEL) < cap) m.set(MAX_LEVEL, cap);
        } catch (Throwable t) {
            Global.getLogger(OfficerLinks.class).warn("Wingman: a linked officer's level cap couldn't be raised", t);
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
    static String levelUps(wingman.net.HostSession s) {
        String said = null;
        if (screenUp()) return null; // picks wait (and nothing new is offered) until the game's own screen is closed

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
                if (d != null && d.canLevelUp()) {
                    if (!d.isMadePicks()) d.makeSkillPicks();
                    List<String> picks = d.getSkillPicks() == null ? new ArrayList<String>() : d.getSkillPicks();
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
        if (ship == null) return null;
        if (screenUp()) { // not under an open fleet/refit/officer screen: its officer rows (and their hover pop-ups) would be left behind
            waiting.put(key(player), new String[] { player, ship.getId() });
            PersonAPI o = officerOf(player);
            return o == null || ship.getCaptain() == o ? null
                    : o.getNameString() + " (" + player + "'s officer) takes command of " + CampaignLobby.label(ship) + " once you close this screen.";
        }
        waiting.remove(key(player));
        return move(player, ship);
    }

    /** Officer moves held back while a screen was open: player key -> {player, ship id}, the newest pick each. */
    private static final java.util.Map<String, String[]> waiting = new java.util.LinkedHashMap<String, String[]>();

    /** A screen of the game's own (fleet, refit, character, cargo...) is open over the map. */
    static boolean screenUp() {
        try {
            com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
            return ui != null && ui.getCurrentCoreTab() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The player dropped their pick: a held-back move for it is dropped too. */
    static void forgetWaiting(String player) { waiting.remove(key(player)); }

    /** Once the screen is closed: the officer moves held back, done. Returns lines for the host. */
    static List<String> applyWaiting() {
        List<String> said = new ArrayList<String>();
        if (waiting.isEmpty() || screenUp()) return said;
        List<String[]> todo = new ArrayList<String[]>(waiting.values());
        waiting.clear();
        CampaignFleetAPI f = Global.getSector().getPlayerFleet();
        if (f == null) return said;
        for (String[] w : todo) {
            for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) {
                if (!m.getId().equals(w[1])) continue;
                String line = move(w[0], m);
                if (line != null) said.add(line);
            }
        }
        return said;
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
            f.getFleetData().setSyncNeeded();
            return officer.getNameString() + " (" + player + "'s officer) takes command of " + CampaignLobby.label(ship)
                    + (left != null && current != null && !current.isDefault() ? "; " + current.getNameString() + " moves to " + CampaignLobby.label(left) : "") + ".";
        } catch (Throwable t) {
            Global.getLogger(OfficerLinks.class).warn("Wingman: a linked officer couldn't move to " + player + "'s ship", t);
            return null;
        }
    }
}
