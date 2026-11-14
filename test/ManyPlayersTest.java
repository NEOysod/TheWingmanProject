import java.util.*;
import wingman.net.*;

/** A game well past the old 16-player cap: 40 players on one host, numbered, listed and reported. No game needed. */
public class ManyPlayersTest {
    static int fails;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        int port = 25000 + new Random().nextInt(2000), cap = 40;
        HostSession host = HostSession.getOrStart(port, cap, 20, "Host", NetTest.MODS, HostSession.MOD_CHECK_CONTENT);
        List<Conn> conns = new ArrayList<Conn>();
        for (int i = 2; i <= cap; i++) {
            Conn c = NetTest.join("Pilot" + i, port);
            conns.add(c);
            Conn.Packet w = NetTest.waitFor(c, Msg.WELCOME, host);
            if (w == null) { check("Pilot" + i + " welcomed", false); break; }
        }
        for (int k = 0; k < 20; k++) { host.pump(); Thread.sleep(10); }
        List<HostSession.Player> ps = host.getPlayers();
        Set<Integer> slots = new HashSet<Integer>();
        for (HostSession.Player p : ps) slots.add(p.slot);
        check("39 joiners in a game of 40 (" + ps.size() + ")", ps.size() == cap - 1);
        check("each has its own P number, P2 to P40", slots.size() == cap - 1 && Collections.min(slots) == 2 && Collections.max(slots) == cap);
        Conn extra = NetTest.join("OneTooMany", port);
        String why = NetTest.rejectText(extra, host);
        check("the 41st is told the game is full (" + why + ")", why.contains("full"));

        Roster r = new Roster();
        r.hostName = "Host"; r.maxShipSize = "CAPITAL_SHIP";
        for (HostSession.Player p : ps) { Roster.Player rp = new Roster.Player(); rp.name = p.name; rp.slot = p.slot; r.players.add(rp); }
        Roster back = Roster.decode(r.encode());
        check("the roster lists all 39 (" + back.players.size() + ")", back.players.size() == cap - 1 && back.players.get(cap - 2).slot == ps.get(cap - 2).slot);

        BattleReport br = new BattleReport();
        for (HostSession.Player p : ps) { BattleReport.Pilot pp = new BattleReport.Pilot(); pp.name = p.name; pp.slot = p.slot; br.pilots.add(pp); }
        BattleReport br2 = BattleReport.decode(br.encode());
        check("the battle report keeps all 39 pilots (" + br2.pilots.size() + ")", br2.pilots.size() == cap - 1);

        Roster huge = new Roster(); huge.hostName = "H"; huge.maxShipSize = "CAPITAL_SHIP";
        for (int i = 0; i < 254; i++) { Roster.Player rp = new Roster.Player(); rp.name = "P" + (i + 2); rp.slot = i + 2; huge.players.add(rp); }
        Roster hb = Roster.decode(huge.encode());
        check("a full 255-player roster round-trips, P255 last (" + hb.players.get(253).slot + ")", hb.players.size() == 254 && hb.players.get(253).slot == 255);

        for (Conn c : conns) c.close("done");
        extra.close("done");
        host.shutdown();
        System.out.println(fails == 0 ? "MANY PLAYERS PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
