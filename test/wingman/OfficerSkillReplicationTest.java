package wingman;

import java.util.Arrays;

import wingman.net.Msg;
import wingman.net.OfficerSkillRequest;
import wingman.net.Roster;

/** Wire-format bounds and round-trip checks; authoritative behavior is covered by OfficerSkillMutationTest. */
public final class OfficerSkillReplicationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        requestRoundTrip();
        rosterRoundTrip();
        skillChoiceBounds();
        System.out.println("Officer skill replication wire format: " + checks + " checks passed.");
    }

    private static void requestRoundTrip() throws Exception {
        check(Msg.SKILL_PICK == 69, "message id assigned");
        OfficerSkillRequest request = new OfficerSkillRequest();
        request.memberId = "fleet-member-1";
        request.officerId = "host-officer-7";
        request.skillRevision = 0x123456789abcdefL;
        request.skillId = "helmsmanship";
        OfficerSkillRequest decoded = OfficerSkillRequest.decode(request.encode());
        check(decoded.memberId.equals(request.memberId) && decoded.officerId.equals(request.officerId)
                && decoded.skillRevision == request.skillRevision && decoded.skillId.equals(request.skillId), "request fields round-trip");
        byte[] trailing = Arrays.copyOf(request.encode(), request.encode().length + 1);
        boolean rejected = false;
        try { OfficerSkillRequest.decode(trailing); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "trailing request bytes rejected");

        request.skillId = "";
        rejected = false;
        try { request.encode(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "empty skill ID cannot be encoded");
    }

    private static void rosterRoundTrip() throws Exception {
        Roster roster = new Roster();
        Roster.Member member = new Roster.Member();
        member.id = "fleet-member-1";
        member.officerId = "host-officer-7";
        member.skillRevision = 99L;
        member.skillChoices.add("helmsmanship");
        member.skillChoices.add("gunnery");
        roster.members.add(member);
        Roster decoded = Roster.decode(roster.encode());
        Roster.Member got = decoded.members.get(0);
        check(got.officerId.equals(member.officerId) && got.skillRevision == member.skillRevision
                && got.skillChoices.equals(member.skillChoices), "roster choices, officer identity and revision round-trip");
    }

    private static void skillChoiceBounds() throws Exception {
        Roster r = new Roster();
        Roster.Member m = new Roster.Member();
        for (int i = 0; i < Roster.MAX_SKILL_CHOICES; i++) m.skillChoices.add("mod_skill_" + i);
        r.members.add(m);
        check(Roster.decode(r.encode()).members.get(0).skillChoices.size() == 128, "128 mod skill choices round-trip without truncation");
        m.skillChoices.add("one_too_many");
        boolean rejected = false;
        try { r.encode(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "oversized skill offer rejected instead of truncated");
        m.skillChoices.clear();
        char[] wide = new char[50];
        Arrays.fill(wide, '\u0800');
        m.skillChoices.add(new String(wide));
        rejected = false;
        try { r.encode(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "skill IDs above bounded wire byte length rejected");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
