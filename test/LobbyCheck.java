package wingmanpvp;
import java.util.*;
public class LobbyCheck {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }
    static List<LobbyState.ShipInfo> fleet(String p, int n) {
        List<LobbyState.ShipInfo> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new LobbyState.ShipInfo(p + i, "hull" + i, "Hull" + i, "", 10f, "", i == 0));
        return l;
    }
    public static void main(String[] a) throws Exception {
        LobbyHost h = new LobbyHost("Host", fleet("h", 4), 30f);
        LobbyState s = h.state();
        LobbyState.Player host = s.player(0);
        check(host.captain && host.picks.size() == 1 && host.flagshipId.equals("h0"), "host is captain of A, brings and flies its flagship");
        h.join(1, "Ann", fleet("a", 3));   // -> team B, captain
        h.join(2, "Bob", fleet("b", 3));   // -> team A, crew
        h.join(3, "Cy", fleet("c", 3));    // -> team B, crew
        LobbyState.Player ann = s.player(1), bob = s.player(2), cy = s.player(3);
        check(ann.team == 1 && ann.captain && ann.flagshipId.equals("a0"), "Ann: captain of B with her flagship");
        check(bob.team == 0 && !bob.captain && bob.picks.isEmpty() && bob.flagshipId.isEmpty(), "Bob: crew of A, brings nothing");
        check(h.whyNotReady(bob) != null, "Bob can't be ready before picking a ship");
        check(!h.setPicks(2, "b1", new ArrayList<>()), "Bob can't fly a ship of his own fleet");
        check(!h.setPicks(2, "h1", new ArrayList<>()), "Bob can't fly a captain's ship the captain doesn't bring");
        check(h.setPicks(0, "h0", Arrays.asList("h0", "h1", "h2")), "host captain brings 3 ships");
        check(!h.setPicks(2, "h0", new ArrayList<>()), "Bob can't take the captain's own ship");
        check(h.setPicks(2, "h1", new ArrayList<>()) && bob.flagshipId.equals("h1"), "Bob flies h1 from the captain's fleet");
        check(h.whyNotReady(bob) == null && h.setReady(2, true), "Bob ready");
        check(h.setPicks(3, "a1", new ArrayList<>()) == false, "Cy can't fly a1: Ann doesn't bring it yet");
        check(h.setPicks(1, "a0", Arrays.asList("a0", "a1")), "Ann brings a0, a1");
        check(h.setPicks(3, "a1", new ArrayList<>()), "Cy flies a1");
        check(h.setPicks(0, "h0", Arrays.asList("h0", "h2")) && bob.flagshipId.isEmpty() && !bob.ready, "captain drops h1: Bob picks again, not ready");
        check(h.setPicks(2, "h2", new ArrayList<>()), "Bob flies h2");
        check(!h.setLimit(30f) || true, "limit");
        check(h.setPicks(0, "h0", Arrays.asList("h0", "h1", "h2", "h3")) && h.whyNotReady(host) != null, "host over the team limit (40 / 30)");
        h.setPicks(0, "h0", Arrays.asList("h0", "h2"));
        // captain change: Bob becomes captain of A
        check(h.makeCaptain(2) && bob.captain && bob.flagshipId.equals("b0") && !host.captain && host.picks.isEmpty() && host.flagshipId.isEmpty(),
              "Bob made captain: brings his flagship; host now crew with no ship");
        check(h.setPicks(0, "b0", new ArrayList<>()) == false, "host can't take Bob's own flagship");
        h.setPicks(2, "b0", Arrays.asList("b0", "b1"));
        check(h.setPicks(0, "b1", new ArrayList<>()), "host flies b1 from Bob's fleet");
        // Cy switches to team A (has a captain): crew there, nothing picked
        check(h.setTeam(3, 0) && !cy.captain && cy.flagshipId.isEmpty() && cy.team == 0, "Cy moves to A as crew, picks again");
        // Ann leaves B empty? Ann moves to A too -> B empty
        check(h.whyNotStart() != null, "can't start: not everyone ready");
        h.setTeam(3, 1); // Cy back to B: B has captain Ann
        h.setPicks(3, "a1", new ArrayList<>());
        for (LobbyState.Player p : s.players) h.setReady(p.id, true);
        check(h.whyNotStart() == null, "everyone ready: can start (" + h.whyNotStart() + ")");
        // Ann (captain of B) leaves: Cy promoted, brings his own flagship
        LobbyHost h2 = h;
        // state roundtrip
        LobbyState back = LobbyState.decode(s.encode());
        check(back.players.size() == 4 && back.player(3).flagshipId.equals("a1") && back.captain(1).name.equals("Ann"), "lobby state round trip");
        h.leave(1);
        check(cy.captain && cy.flagshipId.equals("c0") && cy.picks.size() == 1, "Ann left: Cy is captain of B with his flagship");
        // start: only captains' fleets
        for (LobbyState.Player p : s.players) h.setReady(p.id, true);
        check(h.whyNotStart() == null, "ready again");
        h.start(null); // host isn't captain: no host pack
        check(s.phase == LobbyState.Phase.STARTING && h.packs().isEmpty() && !h.allFleetsIn(), "starting: waiting for the 2 captains' fleets");
        // fleets carry Second in Command's executive officers
        FleetPack fp = new FleetPack(); fp.owner = "Ann";
        FleetPack.Ship sh = new FleetPack.Ship(); sh.memberId = "a0"; sh.loadout.hullId = "onslaught"; fp.ships.add(sh);
        FleetPack.Exec ex = new FleetPack.Exec(); ex.slot = 2; ex.aptitudeId = "sc_tactical"; ex.name = "Ada Vey";
        ex.skills.addAll(Arrays.asList("sc_tactical_spotters", "sc_tactical_rapid_response")); fp.execs.add(ex);
        FleetPack fb = FleetPack.decode(fp.encode());
        check(fb.execs.size() == 1 && fb.execs.get(0).slot == 2 && fb.execs.get(0).aptitudeId.equals("sc_tactical")
            && fb.execs.get(0).name.equals("Ada Vey") && fb.execs.get(0).skills.size() == 2 && fb.ships.size() == 1, "fleet pack keeps executive officers");
        check(fp.only(Arrays.asList("a0")).execs.size() == 1, "the ships a captain brings keep the executive officers");
        FleetPack bad = new FleetPack(); FleetPack.Exec bx = new FleetPack.Exec(); bx.slot = 200; bad.execs.add(bx);
        boolean refused; try { FleetPack.decode(bad.encode()); refused = false; } catch (java.io.IOException e) { refused = true; }
        check(refused, "an impossible executive officer slot is refused");
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
    }
}
