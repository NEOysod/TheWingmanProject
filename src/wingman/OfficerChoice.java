package wingman;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;

import wingman.net.In;
import wingman.net.Msg;

/**
 * Joiner side: the officer the host linked to us, when it can level up, and the skills the host's game offers
 * (Msg.OFFICER_LEVEL). The lobby shows them in YOUR SHIP; a number key picks one, sent to the host (Msg.OFFICER_PICK),
 * which checks it is one of those offered and levels the officer up through the game's own level-up.
 */
final class OfficerChoice {
    private OfficerChoice() {}

    static volatile String officer = "";
    static volatile int toLevel;
    static volatile List<String> skills = new ArrayList<String>();
    private static final List<String> outgoing = new ArrayList<String>();

    static void onMessage(byte[] data) {
        try {
            In in = Msg.reader(data);
            String name = in.readUTF();
            int level = in.readInt();
            int n = in.readByte() & 0xFF;
            if (n > 8) return;
            List<String> s = new ArrayList<String>();
            for (int i = 0; i < n; i++) s.add(in.readUTF());
            officer = name;
            toLevel = level;
            skills = s;
        } catch (Throwable t) { /* garbled: nothing shown */ }
    }

    static boolean pending() { return !skills.isEmpty(); }

    /** What YOUR SHIP says while the officer can level up. */
    static String line() {
        List<String> s = skills;
        if (s.isEmpty()) return "";
        StringBuilder b = new StringBuilder("Your officer " + officer + " can reach level " + toLevel + ". Press a number to pick a skill:");
        for (int i = 0; i < s.size(); i++) b.append("\n").append(i + 1).append(": ").append(name(s.get(i)));
        return b.toString();
    }

    static String name(String id) {
        try { return Global.getSettings().getSkillSpec(id).getName(); } catch (Throwable t) { return id; }
    }

    /** A number key in the lobby (1 is the first skill). True if it picked one. */
    static boolean pick(int index) {
        List<String> s = skills;
        if (index < 0 || index >= s.size()) return false;
        synchronized (outgoing) { outgoing.add(s.get(index)); }
        skills = new ArrayList<String>(); // until the host's answer (the next level's choices, if any)
        return true;
    }

    static List<String> takeOutgoing() {
        synchronized (outgoing) {
            List<String> out = new ArrayList<String>(outgoing);
            outgoing.clear();
            return out;
        }
    }

    static void forget() { officer = ""; toLevel = 0; skills = new ArrayList<String>(); }
}
