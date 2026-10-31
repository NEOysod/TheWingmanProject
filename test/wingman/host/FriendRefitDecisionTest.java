package wingman.host;

import java.lang.reflect.Field;
import java.util.List;

import wingman.WingmanConfig;
import wingman.net.Conn;
import wingman.net.HostCombatFixture;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.RefitRequest;
import wingman.net.RefitResult;

public final class FriendRefitDecisionTest {
    public static void main(String[] args) throws Exception {
        Field active = HostSession.class.getDeclaredField("active");
        active.setAccessible(true);
        Object previous = active.get(null);
        try (HostCombatFixture fixture = new HostCombatFixture()) {
            active.set(null, fixture.session);
            FriendRefit.resetForCampaignLoad();
            check(!FriendRefit.bypass(fixture.player), "a new connection requires approval");

            FriendRefit.Pending old = request(fixture.player, "old");
            FriendRefit.Pending newer = request(fixture.player, "new");
            FriendRefit.pending.put(fixture.player.id, newer);
            check(FriendRefit.accept(old) != null, "stale acceptance must fail");
            FriendRefit.deny(old);
            check(FriendRefit.pending.get(fixture.player.id) == newer, "stale decisions cannot remove a newer request");
            check(fixture.drain().isEmpty(), "stale decisions send no result for the newer request");
            FriendRefit.deny(newer);
            check(!FriendRefit.pending.containsKey(fixture.player.id), "the current request can be denied");
            check(result(fixture.drain()) == RefitResult.DENIED, "the requesting player receives the denial");

            old = request(fixture.player, "old");
            FriendRefit.pending.put(fixture.player.id, old);
            RefitRequest replacement = new RefitRequest();
            replacement.memberId = "new";
            fixture.player.refitRequest = replacement.encode();
            check(FriendRefit.accept(old) != null, "a queued replacement blocks acceptance of the old request");
            FriendRefit.deny(old);
            check(FriendRefit.pending.get(fixture.player.id) == old, "a queued replacement blocks denial of the old request");
            check(fixture.drain().isEmpty(), "no decision is sent before the replacement is read");
            WingmanConfig cfg = new WingmanConfig();
            cfg.friendRefit = false;
            FriendRefit.advance(fixture.session, cfg);
            check(!FriendRefit.pending.containsKey(fixture.player.id), "a rejected decoded replacement invalidates the old request");
            check(result(fixture.drain()) == RefitResult.REFUSED, "the rejected replacement is reported to the player");
            check(FriendRefit.accept(old) != null, "the replaced request cannot be accepted afterward");
            FriendRefit.deny(old);
            check(fixture.drain().isEmpty(), "the replaced request cannot produce another decision");

            FriendRefit.setBypass(fixture.player, true);
            fixture.player.refitRequest = replacement.encode();
            FriendRefit.advance(fixture.session, cfg);
            check(!FriendRefit.pending.containsKey(fixture.player.id), "bypass cannot override disabled refits");
            check(result(fixture.drain()) == RefitResult.REFUSED, "the bypassed request still receives a validation refusal");
            FriendRefit.setBypass(fixture.player, false);

            old = request(fixture.player, "old");
            FriendRefit.pending.put(fixture.player.id, old);
            fixture.player.replaced = true;
            check(FriendRefit.accept(old) != null, "a replaced connection cannot approve a request");
            check(!FriendRefit.pending.containsKey(fixture.player.id), "the replaced connection's request is removed");
            check(fixture.drain().isEmpty(), "a replaced connection receives no decision");

            fixture.player.replaced = false;
            fixture.conn.close("disconnected");
            old = request(fixture.player, "old");
            FriendRefit.pending.put(fixture.player.id, old);
            check(FriendRefit.accept(old) != null, "a closed connection cannot approve a request");
            check(!FriendRefit.pending.containsKey(fixture.player.id), "the closed connection's request is removed");
        } finally {
            FriendRefit.pending.clear();
            active.set(null, previous);
        }
    }

    private static FriendRefit.Pending request(HostSession.Player player, String ship) {
        FriendRefit.Pending pd = new FriendRefit.Pending();
        pd.player = player;
        pd.request = new RefitRequest();
        pd.request.memberId = ship;
        pd.ship = ship;
        return pd;
    }

    private static int result(List<Conn.Packet> packets) throws Exception {
        check(packets.size() == 1 && packets.get(0).type == Msg.REFIT_RESULT, "one refit result is sent");
        return RefitResult.decode(packets.get(0).data).status;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
