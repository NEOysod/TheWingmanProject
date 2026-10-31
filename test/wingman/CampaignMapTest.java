package wingman;

import java.io.IOException;

import wingman.net.CampaignMap;

/** The joiner lobby's campaign map rides Msg.MAP: it round-trips, is capped, and bad numbers or formats are refused. */
public final class CampaignMapTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        CampaignMap m = sample();
        CampaignMap back = CampaignMap.decode(m.encode());
        check(back.location.equals("Corvus") && !back.hyperspace && back.paused, "header round-trips");
        check(back.viewRadius == 4000f && back.playerX == 120f && back.playerVy == -3f, "view and player round-trip");
        check(back.bodies.size() == 2 && back.bodies.get(1).name.equals("Jangala")
                && back.bodies.get(1).color == 0x40a0ff && back.bodies.get(1).kind == CampaignMap.PLANET, "bodies round-trip");
        check(back.fleets.size() == 1 && back.fleets.get(0).hostile && back.fleets.get(0).ships == 12
                && back.fleets.get(0).detail == CampaignMap.KNOWN && back.fleets.get(0).vx == 5f, "fleets round-trip");

        CampaignMap many = sample();
        for (int i = 0; i < CampaignMap.MAX_BODIES + 10; i++) many.bodies.add(new CampaignMap.Body());
        for (int i = 0; i < CampaignMap.MAX_FLEETS + 10; i++) many.fleets.add(new CampaignMap.Fleet());
        CampaignMap capped = CampaignMap.decode(many.encode());
        check(capped.bodies.size() == CampaignMap.MAX_BODIES && capped.fleets.size() == CampaignMap.MAX_FLEETS, "counts are capped");

        CampaignMap longName = sample();
        longName.location = "\u0001" + new String(new char[200]).replace('\0', 'x');
        check(CampaignMap.decode(longName.encode()).location.length() <= CampaignMap.MAX_NAME, "names are capped and cleaned");

        CampaignMap nan = sample();
        nan.fleets.get(0).x = Float.NaN;
        check(refused(nan.encode()), "NaN refused");

        CampaignMap noView = sample();
        noView.viewRadius = 0f;
        check(refused(noView.encode()), "zero view refused");

        CampaignMap tinyView = sample();
        tinyView.viewRadius = Float.MIN_VALUE;
        check(refused(tinyView.encode()), "tiny view refused");

        byte[] format = sample().encode();
        format[0] = 99;
        check(refused(format), "unknown format refused");

        byte[] cut = sample().encode();
        check(refused(java.util.Arrays.copyOf(cut, cut.length - 3)), "truncated refused");

        System.out.println("CampaignMapTest: " + checks + " checks passed");
    }

    private static CampaignMap sample() {
        CampaignMap m = new CampaignMap();
        m.location = "Corvus";
        m.paused = true;
        m.viewRadius = 4000f;
        m.playerX = 120f; m.playerY = 80f; m.playerVx = 1f; m.playerVy = -3f;
        CampaignMap.Body star = new CampaignMap.Body();
        star.kind = CampaignMap.STAR; star.radius = 500f; star.name = "Corvus";
        m.bodies.add(star);
        CampaignMap.Body planet = new CampaignMap.Body();
        planet.kind = CampaignMap.PLANET; planet.x = 2000f; planet.radius = 150f; planet.name = "Jangala"; planet.color = 0x40a0ff;
        m.bodies.add(planet);
        CampaignMap.Fleet f = new CampaignMap.Fleet();
        f.detail = CampaignMap.KNOWN; f.hostile = true; f.x = -300f; f.vx = 5f; f.ships = 12; f.name = "Pirate raiders"; f.color = 0xff0000;
        m.fleets.add(f);
        return m;
    }

    private static boolean refused(byte[] data) {
        try { CampaignMap.decode(data); return false; } catch (IOException ex) { return true; }
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
