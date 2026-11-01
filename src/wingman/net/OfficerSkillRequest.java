package wingman.net;

import java.io.IOException;

/** A joiner's request to level one specific host-owned linked officer skill. */
public final class OfficerSkillRequest {
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_SKILL_ID_LENGTH = 128;

    public String memberId = "";
    public String officerId = "";
    public String skillId = "";
    public long skillRevision;

    public byte[] encode() {
        if (empty(memberId) || empty(officerId) || empty(skillId)
                || memberId.length() > MAX_ID_LENGTH || officerId.length() > MAX_ID_LENGTH
                || skillId.length() > MAX_SKILL_ID_LENGTH
                || Roster.modifiedUtfLength(memberId) > MAX_ID_LENGTH
                || Roster.modifiedUtfLength(officerId) > MAX_ID_LENGTH
                || Roster.modifiedUtfLength(skillId) > Roster.MAX_SKILL_ID_BYTES)
            throw new IllegalArgumentException("invalid officer skill request");
        Msg.W w = new Msg.W();
        try {
            w.out.writeUTF(memberId);
            w.out.writeUTF(officerId);
            w.out.writeLong(skillRevision);
            w.out.writeUTF(skillId);
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static OfficerSkillRequest decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        OfficerSkillRequest request = new OfficerSkillRequest();
        request.memberId = in.readUTF();
        request.officerId = in.readUTF();
        request.skillRevision = in.readLong();
        request.skillId = in.readUTF();
        if (in.remaining() != 0) throw new IOException("trailing officer skill request data");
        if (empty(request.memberId) || empty(request.officerId) || empty(request.skillId)
                || request.memberId.length() > MAX_ID_LENGTH || request.officerId.length() > MAX_ID_LENGTH
                || request.skillId.length() > MAX_SKILL_ID_LENGTH
                || Roster.modifiedUtfLength(request.memberId) > MAX_ID_LENGTH
                || Roster.modifiedUtfLength(request.officerId) > MAX_ID_LENGTH
                || Roster.modifiedUtfLength(request.skillId) > Roster.MAX_SKILL_ID_BYTES)
            throw new IOException("invalid officer skill request");
        return request;
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
