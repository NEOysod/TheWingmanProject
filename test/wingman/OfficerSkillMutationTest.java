package wingman;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.net.HostCombatFixture;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.OfficerSkillRequest;
import wingman.net.Roster;

/** Executes the real host request handler and vanilla-levelUp seam with strict API doubles. */
public final class OfficerSkillMutationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try (World w = new World()) {
            OfficerSkillRequest request = w.request();
            w.submit(request);
            check(w.levelUps == 1 && w.level == 3 && w.syncs == 1, "valid request invokes vanilla levelUp once and syncs the fleet");
            check(w.fixture.drain().stream().anyMatch(p -> p.type == Msg.NOTICE), "host returns selection feedback");
            w.submit(request);
            check(w.levelUps == 1, "accepted request cannot be replayed");
        }
        try (World w = new World()) {
            OfficerSkillRequest stale = w.request();
            w.xp++;
            w.submit(stale);
            check(w.levelUps == 0, "changed officer XP invalidates the menu");
            OfficerSkillRequest beforeBattle = w.request();
            w.fixture.session.battlesEnded++;
            w.submit(beforeBattle);
            check(w.levelUps == 0, "in-flight pre-battle menu is rejected after a battle even without officer progression");
            check(w.request().skillRevision != beforeBattle.skillRevision, "fresh offers carry the new battle epoch");
            OfficerSkillRequest forged = w.request();
            forged.skillId = "not_offered";
            w.submit(forged);
            check(w.levelUps == 0, "unoffered skill does not mutate the officer");
            forged = w.request(); forged.officerId = "other_officer"; w.submit(forged);
            check(w.levelUps == 0, "wrong officer identity is rejected");
            forged = w.request(); forged.memberId = "other_ship"; w.submit(forged);
            check(w.levelUps == 0, "wrong ship identity is rejected");
            w.tags.clear(); w.tags.add(OfficerPlayerBinding.TAG_PREFIX + "somebody_else");
            w.submit(w.request());
            check(w.levelUps == 0, "another player's linked officer is protected");
        }
        try (World w = new World()) {
            w.eligible = false; w.submit(w.request());
            check(w.levelUps == 0, "level-up eligibility is enforced by the host");
            w.eligible = true; w.locked = true; w.submit(w.request());
            check(w.levelUps == 0, "unremovable officers remain protected");
            w.locked = false; w.members.add(w.member); w.submit(w.request());
            check(w.levelUps == 0, "duplicate fleet-member identity is rejected");
            w.members.remove(w.members.size() - 1); w.officers.add(w.officer); w.submit(w.request());
            check(w.levelUps == 0, "duplicate officer data/bindings fail closed");
            w.officers.remove(w.officers.size() - 1); w.fixture.player.replaced = true; w.submit(w.request());
            check(w.levelUps == 0 && w.fixture.player.officerSkillRequests.isEmpty(), "replaced connection cannot apply pending input");
        }
        try (World w = new World()) {
            w.coreOpen = true; w.submit(w.request());
            check(w.levelUps == 0 && w.fixture.player.officerSkillRequests.isEmpty(), "host's cached core menu rejects and drains selections");
            w.coreOpen = false;
            java.lang.reflect.Field battle = HostSession.class.getDeclaredField("battleOpen");
            battle.setAccessible(true); battle.setBoolean(w.fixture.session, true);
            w.submit(w.request());
            check(w.levelUps == 0 && w.fixture.player.officerSkillRequests.isEmpty(), "battle context rejects and drains selections");
            battle.setBoolean(w.fixture.session, false); w.state = GameState.TITLE; w.submit(w.request());
            check(w.levelUps == 0, "title/simulation context cannot mutate the host campaign");
        }
        try (World w = new World()) {
            w.fixture.conn.close("test disconnected peer");
            w.submit(w.request());
            check(w.levelUps == 0 && w.fixture.player.officerSkillRequests.isEmpty(), "closed connection cannot apply pending input");
        }
        try (World w = new World()) {
            w.madePicks = false;
            w.submit(w.request());
            check(w.levelUps == 0 && w.generated == 0, "request cannot generate unadvertised skill choices");
            w.coreOpen = true; w.populateOffers();
            check(w.generated == 0, "host's cached core screen cannot generate or reroll choices");
            w.coreOpen = false; w.populateOffers(); w.populateOffers();
            check(w.generated == 1 && w.madePicks, "newly eligible officer receives host-generated vanilla offers once, never rerolled");
            w.throwAfterMutation = true;
            OfficerSkillRequest request = w.request(); w.submit(request);
            check(w.levelUps == 1 && w.level == 3 && w.syncs == 1, "partial vanilla/mod failure still invalidates fleet caches");
            w.submit(request);
            check(w.levelUps == 1, "failed callback cannot cause an accepted request to replay");
        }
        Global.setSector(null);
        System.out.println("Officer skill mutation: " + checks + " checks passed (real host handler, authority, stale input, lifecycle and failure safety).");
    }

    private static final class World implements AutoCloseable {
        final HostCombatFixture fixture = new HostCombatFixture();
        final List<String> picks = new ArrayList<String>(Arrays.asList("helmsmanship", "gunnery"));
        final Set<String> tags = new HashSet<String>();
        final List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>();
        final List<OfficerDataAPI> officers = new ArrayList<OfficerDataAPI>();
        boolean eligible = true, madePicks = true, locked, coreOpen, throwAfterMutation;
        int level = 2, levelUps, syncs, generated;
        long xp = 2000L;
        GameState state = GameState.CAMPAIGN;
        final MutableCharacterStatsAPI stats = proxy(MutableCharacterStatsAPI.class, (m, a) -> {
            if (m.equals("getLevel")) return level;
            if (m.equals("getXP")) return xp;
            throw unexpected(m);
        });
        final MemoryAPI memory = proxy(MemoryAPI.class, (m, a) -> {
            if (m.equals("is")) return locked && "$captain_unremovable".equals(a[0]) && Boolean.TRUE.equals(a[1]);
            throw unexpected(m);
        });
        final PersonAPI person = proxy(PersonAPI.class, (m, a) -> {
            switch (m) {
                case "getId": return "officer_1";
                case "getStats": return stats;
                case "getTags": return tags;
                case "getMemoryWithoutUpdate": return memory;
                case "isDefault": case "isPlayer": case "isAICore": return false;
                default: throw unexpected(m);
            }
        });
        final OfficerDataAPI officer = proxy(OfficerDataAPI.class, (m, a) -> {
            switch (m) {
                case "getPerson": return person;
                case "canLevelUp": return eligible;
                case "isMadePicks": return madePicks;
                case "getSkillPicks": return picks;
                case "makeSkillPicks": generated++; madePicks = true; return null;
                case "levelUp":
                    check(picks.contains(a[0]), "vanilla levelUp receives the selected host offer");
                    levelUps++; level++; eligible = false;
                    if (throwAfterMutation) throw new IllegalStateException("test callback failed after progression");
                    return null;
                default: throw unexpected(m);
            }
        });
        final FleetMemberAPI member = proxy(FleetMemberAPI.class, (m, a) -> {
            if (m.equals("getId")) return "ship_1";
            if (m.equals("getCaptain")) return person;
            throw unexpected(m);
        });
        final FleetDataAPI data = proxy(FleetDataAPI.class, (m, a) -> {
            switch (m) {
                case "getMembersListCopy": return new ArrayList<FleetMemberAPI>(members);
                case "getOfficersCopy": return new ArrayList<OfficerDataAPI>(officers);
                case "getOfficerData": return a[0] == person ? officer : null;
                case "setSyncNeeded": syncs++; return null;
                default: throw unexpected(m);
            }
        });
        final CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (m, a) -> {
            if (m.equals("getFleetData")) return data;
            throw unexpected(m);
        });

        World() throws Exception {
            fixture.player.name = "Alice";
            tags.add(OfficerPlayerBinding.TAG_PREFIX + "alice");
            members.add(member); officers.add(officer);
            Global.setSettings(proxy(SettingsAPI.class, (m, a) -> {
                if (m.equals("getCurrentState")) return state;
                if (m.equals("getFloat")) return 1f;
                if (m.equals("getInt")) return 1;
                if (m.equals("getBoolean")) return false;
                if (m.equals("getColor")) return java.awt.Color.WHITE;
                throw unexpected(m);
            }));
            CampaignUIAPI ui = proxy(CampaignUIAPI.class, (m, a) -> {
                if (m.equals("getCurrentCoreTab")) return coreOpen ? com.fs.starfarer.api.campaign.CoreUITabId.FLEET : null;
                throw unexpected(m);
            });
            Global.setSector(proxy(SectorAPI.class, (m, a) -> {
                if (m.equals("getPlayerFleet")) return fleet;
                if (m.equals("getCampaignUI")) return ui;
                throw unexpected(m);
            }));
        }

        OfficerSkillRequest request() throws Exception {
            Method revision = CampaignLobby.class.getDeclaredMethod("skillRevision", int.class, PersonAPI.class, boolean.class, boolean.class, List.class);
            revision.setAccessible(true);
            OfficerSkillRequest request = new OfficerSkillRequest();
            request.memberId = "ship_1"; request.officerId = "officer_1"; request.skillId = picks.get(0);
            request.skillRevision = (Long) revision.invoke(null, fixture.session.battlesEnded, person, eligible, madePicks, picks);
            return request;
        }

        void submit(OfficerSkillRequest request) {
            fixture.player.officerSkillRequests.addLast(request);
            CampaignLobby.handleOfficerSkillPicks(fixture.session);
        }

        void populateOffers() throws Exception {
            Roster.Member roster = new Roster.Member(); roster.officerId = "officer_1";
            Method populate = CampaignLobby.class.getDeclaredMethod("populateSkillChoices", HostSession.class, CampaignFleetAPI.class,
                    FleetMemberAPI.class, PersonAPI.class, Roster.Member.class);
            populate.setAccessible(true); populate.invoke(null, fixture.session, fleet, member, person, roster);
        }

        public void close() { fixture.close(); }
    }

    private interface Calls { Object call(String method, Object[] args) throws Throwable; }

    private static <T> T proxy(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, a) -> {
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return type.getSimpleName() + " test double";
            return calls.call(m.getName(), a);
        }));
    }

    private static AssertionError unexpected(String method) { return new AssertionError("Unexpected API call: " + method); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
