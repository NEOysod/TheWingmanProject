package wingman;

import java.io.IOException;

import wingman.net.News;

/** The campaign date and notifications ride Msg.NEWS: they round-trip, are capped, and a bad count is refused. */
public final class NewsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        News n = new News();
        n.date = "Cycle 207, March 4";
        n.items.add("New intel: a bounty");
        n.items.add("Fleet destroyed");
        News back = News.decode(n.encode());
        check(back.date.equals(n.date), "date round-trips");
        check(back.items.equals(n.items), "items round-trip");

        News empty = News.decode(new News().encode());
        check(empty.date.isEmpty() && empty.items.isEmpty(), "empty round-trips");

        News many = new News();
        for (int i = 0; i < News.MAX_ITEMS + 10; i++) many.items.add("m" + i);
        check(News.decode(many.encode()).items.size() == News.MAX_ITEMS, "item count is capped");

        News longText = new News();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append('x');
        longText.items.add(sb.toString());
        check(News.decode(longText.encode()).items.get(0).length() == 160, "text is capped at 160");

        byte[] bad = new News().encode();
        bad[bad.length - 1] = (byte) (News.MAX_ITEMS + 1);
        boolean refused = false;
        try { News.decode(bad); } catch (IOException ex) { refused = true; }
        check(refused, "too many items refused");

        System.out.println("NewsTest: " + checks + " checks passed");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
