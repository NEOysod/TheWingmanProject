package wingman;

import java.awt.Color;
import java.util.Map;

/**
 * The round trip to the host, at the top of the battle screen (setting pingReadout): "mine" is one short line that
 * turns orange or red when the link is slow, and says so when it spikes; "everyone" is every player's, by P number.
 */
final class PingReadout {
    private PingReadout() {}

    /** A spike: the worst of the last ten seconds well past the usual. */
    static boolean spiking(int rtt, int worst) { return rtt >= 0 && worst > 150 && worst > rtt * 2 && worst > rtt + 80; }

    static void draw(String mode, int mine, int worst, Map<Integer, Integer> everyone) {
        float sw = Style.sw(), sh = Style.sh();
        float top = sh - 6f;
        if ("everyone".equals(mode) && everyone != null && !everyone.isEmpty()) {
            StringBuilder b = new StringBuilder();
            // one chip-like piece per player, centred, wrapping past 640 wide
            float maxW = Math.min(640f, sw - 40f), lh = Style.lineHeight(Style.SMALL_FONT) + 4f;
            float x0 = (sw - maxW) / 2f, x = x0, y = top;
            float total = 0f;
            for (Map.Entry<Integer, Integer> e : everyone.entrySet()) total += piece(e.getKey(), e.getValue()) + 14f;
            if (total < maxW) x = (sw - total) / 2f;
            Style.fill(Math.max(x0, x) - 8f, y - lh * Math.max(1, (int) Math.ceil(total / maxW)) - 2f,
                    Math.min(maxW, total) + 16f, lh * Math.max(1, (int) Math.ceil(total / maxW)) + 4f, new Color(0, 0, 0, 90));
            for (Map.Entry<Integer, Integer> e : everyone.entrySet()) {
                float pw = piece(e.getKey(), e.getValue());
                if (x + pw > x0 + maxW && x > x0) { x = x0; y -= lh; }
                int ms = e.getValue();
                Style.text("P" + e.getKey(), Style.SMALL_FONT, PlayerTags.color(e.getKey()), x, y, 60f);
                float nx = x + Style.width("P" + e.getKey(), Style.SMALL_FONT) + 5f;
                Style.text(e.getKey() == 1 ? "host" : wingman.net.Pings.text(ms), Style.SMALL_FONT, e.getKey() == 1 ? Style.FAINT : CampaignPlayerList.pingColor(ms), nx, y, 80f);
                x += pw + 14f;
            }
            return;
        }
        if (mine < 0) return;
        boolean spike = spiking(mine, worst);
        String t = "PING " + mine + " MS" + (spike ? "   SPIKE " + worst + " MS" : "");
        Color c = spike ? Panel.WARN : CampaignPlayerList.pingColor(mine);
        if (c == Style.DIM) c = Style.FAINT; // all is well: quiet
        float w = Style.width(t, Style.SMALL_FONT);
        Style.text(t, Style.SMALL_FONT, c, (sw - w) / 2f, top, w + 20f);
    }

    private static float piece(int slot, int ms) {
        return Style.width("P" + slot, Style.SMALL_FONT) + 5f + Style.width(slot == 1 ? "host" : wingman.net.Pings.text(ms), Style.SMALL_FONT);
    }
}
