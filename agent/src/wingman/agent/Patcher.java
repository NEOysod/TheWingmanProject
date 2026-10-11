package wingman.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Swaps chosen method calls in a class file for calls to static methods in {@link Hooks}, in place.
 *
 * Every swap keeps the code exactly the same length and the operand stack exactly the same shape:
 * <ul>
 * <li>{@code invokeinterface Owner.m(args)R} (5 bytes, receiver + args on the stack) becomes
 * {@code invokestatic Hooks.m(Owner, args)R} (3 bytes) plus two {@code nop}s;</li>
 * <li>{@code invokevirtual Owner.m(args)R} or {@code invokestatic Owner.m(args)R} (3 bytes) becomes
 * {@code invokestatic Hooks.m(...)R} (3 bytes).</li>
 * </ul>
 * So no jump offset, exception range, line number or stack map frame changes, and nothing but the
 * swapped instructions and some constants appended to the end of the constant pool differs. That is
 * why this needs no bytecode library (ASM and the like): there is nothing to recompute.
 */
public final class Patcher {
    private Patcher() {}

    /** One call to swap: what it calls now, and which Hooks method takes its place. */
    public static final class Rule {
        final String owner, name, desc, hookName, hookDesc;
        final int[] opcodes; // which call instructions are swapped
        final String label;
        int hits;

        Rule(String label, String owner, String name, String desc, String hookName, String hookDesc, int... opcodes) {
            this.label = label; this.owner = owner; this.name = name; this.desc = desc;
            this.hookName = hookName; this.hookDesc = hookDesc; this.opcodes = opcodes;
        }
    }

    static final int INVOKEVIRTUAL = 0xb6, INVOKESTATIC = 0xb8, INVOKEINTERFACE = 0xb9;
    static final String HOOKS = "wingman/agent/Hooks";

    /** The constant pool of a class, as read. */
    private static final class Pool {
        int count;             // constant_pool_count as written (entries are 1..count-1)
        int[] tag;
        int[] start;           // offset of each entry's tag byte
        int end;               // offset just past the pool
        byte[] b;

        int u2(int at) { return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF); }

        String utf(int i) {
            int at = start[i] + 1, len = u2(at);
            return Mutf8.decode(b, at + 2, len);
        }

        String className(int i) { return utf(u2(start[i] + 1)); }

        /** For a Methodref / InterfaceMethodref: {owner, name, desc}. */
        String[] member(int i) {
            int s = start[i];
            int cls = u2(s + 1), nat = u2(s + 3);
            int ns = start[nat];
            return new String[] { className(cls), utf(u2(ns + 1)), utf(u2(ns + 3)) };
        }
    }

    private static Pool readPool(byte[] b) throws IOException {
        if (b.length < 10 || (b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE || (b[2] & 0xFF) != 0xBA || (b[3] & 0xFF) != 0xBE) throw new IOException("not a class file");
        Pool p = new Pool();
        p.b = b;
        p.count = p.u2(8);
        p.tag = new int[p.count];
        p.start = new int[p.count];
        int at = 10;
        for (int i = 1; i < p.count; i++) {
            int t = b[at] & 0xFF;
            p.tag[i] = t;
            p.start[i] = at;
            switch (t) {
                case 1: at += 3 + p.u2(at + 1); break;                   // Utf8
                case 3: case 4: at += 5; break;                         // Integer, Float
                case 5: case 6: at += 9; i++; break;                    // Long, Double take two slots
                case 7: case 8: case 16: case 19: case 20: at += 3; break; // Class, String, MethodType, Module, Package
                case 9: case 10: case 11: case 12: case 17: case 18: at += 5; break;
                case 15: at += 4; break;                                // MethodHandle
                default: throw new IOException("unknown constant tag " + t);
            }
        }
        p.end = at;
        return p;
    }

    /** What a patch did, for the log. */
    public static final class Result {
        public byte[] bytes;
        public int swaps;
    }

    /**
     * Apply the rules to one class. Returns null if nothing in it matches (the class is left as it is).
     * Throws on a class file it can't read; the caller leaves such a class alone.
     */
    public static Result patch(byte[] b, List<Rule> rules) throws IOException {
        Pool p = readPool(b);
        // Which pool entries are calls to swap, and by which rule.
        Map<Integer, Rule> byIndex = new HashMap<Integer, Rule>();
        for (int i = 1; i < p.count; i++) {
            if (p.tag[i] != 10 && p.tag[i] != 11) continue;
            String[] m = p.member(i);
            for (Rule r : rules) {
                if (r.owner.equals(m[0]) && r.name.equals(m[1]) && r.desc.equals(m[2])) { byIndex.put(i, r); break; }
            }
        }
        if (byIndex.isEmpty()) return null;

        // New constants, appended: the Hooks class, then a Methodref per rule used.
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        DataOutputStream ex = new DataOutputStream(extra);
        int next = p.count;
        int hooksUtf = next++;
        ex.writeByte(1); ex.writeUTF(HOOKS);
        int hooksClass = next++;
        ex.writeByte(7); ex.writeShort(hooksUtf);
        Map<Rule, Integer> refOf = new HashMap<Rule, Integer>();
        for (Rule r : new java.util.LinkedHashSet<Rule>(byIndex.values())) {
            int nameUtf = next++;
            ex.writeByte(1); ex.writeUTF(r.hookName);
            int descUtf = next++;
            ex.writeByte(1); ex.writeUTF(r.hookDesc);
            int nat = next++;
            ex.writeByte(12); ex.writeShort(nameUtf); ex.writeShort(descUtf);
            int ref = next++;
            ex.writeByte(10); ex.writeShort(hooksClass); ex.writeShort(nat);
            refOf.put(r, ref);
        }
        if (next > 0xFFFF) throw new IOException("constant pool full");
        ex.flush();

        // Walk to every method's code and swap.
        byte[] out = b.clone();
        int swaps = 0;
        int at = p.end + 6;                           // access, this, super
        int interfaces = p.u2(at); at += 2 + 2 * interfaces;
        at = skipMembers(p, at);                      // fields
        int methods = p.u2(at); at += 2;
        for (int m = 0; m < methods; m++) {
            at += 6;                                  // access, name, desc
            int attrs = p.u2(at); at += 2;
            for (int a = 0; a < attrs; a++) {
                int nameIdx = p.u2(at);
                int len = u4(b, at + 2);
                int body = at + 6;
                if ("Code".equals(p.utf(nameIdx))) {
                    int codeLen = u4(b, body + 4);
                    int code = body + 8;
                    swaps += swapCalls(b, out, code, codeLen, byIndex, refOf);
                }
                at = body + len;
            }
        }
        if (swaps == 0) return null;

        // Reassemble: header, the old pool + the new constants, the rest unchanged.
        ByteArrayOutputStream all = new ByteArrayOutputStream(b.length + extra.size());
        all.write(out, 0, 8);
        all.write(next >>> 8); all.write(next & 0xFF);
        all.write(out, 10, p.end - 10);
        all.write(extra.toByteArray());
        all.write(out, p.end, out.length - p.end);
        Result res = new Result();
        res.bytes = all.toByteArray();
        res.swaps = swaps;
        return res;
    }

    private static int skipMembers(Pool p, int at) {
        int n = p.u2(at); at += 2;
        for (int i = 0; i < n; i++) {
            at += 6;
            int attrs = p.u2(at); at += 2;
            for (int a = 0; a < attrs; a++) at += 6 + u4(p.b, at + 2);
        }
        return at;
    }

    static int u4(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** Walk one method's instructions; swap the matching calls into 'out'. Returns how many. */
    private static int swapCalls(byte[] b, byte[] out, int code, int len, Map<Integer, Rule> byIndex, Map<Rule, Integer> refOf) throws IOException {
        int n = 0;
        int pc = 0;
        while (pc < len) {
            int at = code + pc;
            int op = b[at] & 0xFF;
            int size = size(b, code, pc, op);
            if (op == INVOKEVIRTUAL || op == INVOKESTATIC || op == INVOKEINTERFACE) {
                int idx = ((b[at + 1] & 0xFF) << 8) | (b[at + 2] & 0xFF);
                Rule r = byIndex.get(idx);
                if (r != null && allows(r, op)) {
                    int ref = refOf.get(r);
                    out[at] = (byte) INVOKESTATIC;
                    out[at + 1] = (byte) (ref >>> 8);
                    out[at + 2] = (byte) ref;
                    if (op == INVOKEINTERFACE) { out[at + 3] = 0; out[at + 4] = 0; } // nop, nop
                    synchronized (r) { r.hits++; }
                    n++;
                }
            }
            pc += size;
        }
        if (pc != len) throw new IOException("instruction walk did not end at the code's end");
        return n;
    }

    private static boolean allows(Rule r, int op) {
        for (int o : r.opcodes) if (o == op) return true;
        return false;
    }

    /** Length of the instruction at pc (relative to the code start, which matters for the switch padding). */
    static int size(byte[] b, int code, int pc, int op) throws IOException {
        switch (op) {
            case 0x10: case 0x12: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3a: case 0xa9: case 0xbc:
                return 2;
            case 0x11: case 0x13: case 0x14: case 0x84: case 0xb2: case 0xb3: case 0xb4: case 0xb5:
            case 0xb6: case 0xb7: case 0xb8: case 0xbb: case 0xbd: case 0xc0: case 0xc1: case 0xc6: case 0xc7:
                return 3;
            case 0xc5:
                return 4;
            case 0xb9: case 0xba: case 0xc8: case 0xc9:
                return 5;
            case 0xc4: { // wide
                int next = b[code + pc + 1] & 0xFF;
                return next == 0x84 ? 6 : 4;
            }
            case 0xaa: { // tableswitch
                int p = pc + 1;
                p += (4 - (p % 4)) % 4;
                int low = u4(b, code + p + 4), high = u4(b, code + p + 8);
                if (high < low) throw new IOException("bad tableswitch");
                return p + 12 + 4 * (high - low + 1) - pc;
            }
            case 0xab: { // lookupswitch
                int p = pc + 1;
                p += (4 - (p % 4)) % 4;
                int pairs = u4(b, code + p + 4);
                if (pairs < 0) throw new IOException("bad lookupswitch");
                return p + 8 + 8 * pairs - pc;
            }
            default:
                if (op >= 0x99 && op <= 0xa8) return 3; // branches, goto, jsr
                if (op > 0xc9 && op != 0xca && op != 0xfe && op != 0xff) throw new IOException("unknown opcode " + op);
                return 1;
        }
    }

    /** Modified UTF-8 (the class file's text) to a String. */
    static final class Mutf8 {
        static String decode(byte[] b, int at, int len) {
            StringBuilder sb = new StringBuilder(len);
            int end = at + len;
            while (at < end) {
                int c = b[at++] & 0xFF;
                if (c < 0x80) sb.append((char) c);
                else if ((c & 0xE0) == 0xC0) sb.append((char) (((c & 0x1F) << 6) | (b[at++] & 0x3F)));
                else sb.append((char) (((c & 0x0F) << 12) | ((b[at++] & 0x3F) << 6) | (b[at++] & 0x3F)));
            }
            return sb.toString();
        }
    }

    /** The rules Wingman uses. {@code vanilla}: also the game's own scripts in com/fs/starfarer/api/impl. */
    public static List<Rule> modRules() {
        String E = "com/fs/starfarer/api/combat/CombatEngineAPI", S = "Lcom/fs/starfarer/api/combat/ShipAPI;";
        List<Rule> r = new ArrayList<Rule>();
        r.add(new Rule("getPlayerShip", E, "getPlayerShip", "()" + S, "getPlayerShip", "(L" + E + ";)" + S, INVOKEINTERFACE));
        r.add(new Rule("setPlayerShipExternal", E, "setPlayerShipExternal", "(" + S + ")V", "setPlayerShipExternal", "(L" + E + ";" + S + ")V", INVOKEINTERFACE));
        r.add(new Rule("maintainStatusForPlayerShip", E, "maintainStatusForPlayerShip", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V",
                "maintainStatusForPlayerShip", "(L" + E + ";Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", INVOKEINTERFACE));
        r.add(new Rule("getTimeMult", E, "getTimeMult", "()Lcom/fs/starfarer/api/combat/MutableStat;", "getTimeMult", "(L" + E + ";)Lcom/fs/starfarer/api/combat/MutableStat;", INVOKEINTERFACE));
        r.add(new Rule("Mouse.getX", "org/lwjgl/input/Mouse", "getX", "()I", "mouseX", "()I", INVOKESTATIC));
        r.add(new Rule("Mouse.getY", "org/lwjgl/input/Mouse", "getY", "()I", "mouseY", "()I", INVOKESTATIC));
        r.add(new Rule("Keyboard.isKeyDown", "org/lwjgl/input/Keyboard", "isKeyDown", "(I)Z", "isKeyDown", "(I)Z", INVOKESTATIC));
        r.add(new Rule("isUIAutopilotOn", E, "isUIAutopilotOn", "()Z", "autopilotOn", "(L" + E + ";)Z", INVOKEINTERFACE));
        r.add(new Rule("addPlugin", E, "addPlugin", "(Lcom/fs/starfarer/api/combat/EveryFrameCombatPlugin;)V", "addPlugin", "(L" + E + ";Lcom/fs/starfarer/api/combat/EveryFrameCombatPlugin;)V", INVOKEINTERFACE));
        // Things only for this PC's own screen: the camera and interface sounds. A script running for someone
        // else's ship must not move this player's camera or play them a UI sound.
        String V = "com/fs/starfarer/api/combat/ViewportAPI", VD = "L" + V + ";";
        r.add(new Rule("camera", V, "set", "(FFFF)V", "viewSet", "(" + VD + "FFFF)V", INVOKEINTERFACE));
        r.add(new Rule("camera", V, "setViewMult", "(F)V", "viewMult", "(" + VD + "F)V", INVOKEINTERFACE));
        r.add(new Rule("camera", V, "setExternalControl", "(Z)V", "viewExternal", "(" + VD + "Z)V", INVOKEINTERFACE));
        r.add(new Rule("camera", V, "setCenter", "(Lorg/lwjgl/util/vector/Vector2f;)V", "viewCenter", "(" + VD + "Lorg/lwjgl/util/vector/Vector2f;)V", INVOKEINTERFACE));
        String SP = "com/fs/starfarer/api/SoundPlayerAPI";
        r.add(new Rule("UI sound", SP, "playUISound", "(Ljava/lang/String;FF)Lcom/fs/starfarer/api/SoundAPI;", "uiSound",
                "(L" + SP + ";Ljava/lang/String;FF)Lcom/fs/starfarer/api/SoundAPI;", INVOKEINTERFACE));
        r.add(new Rule("UI sound", SP, "playUILoop", "(Ljava/lang/String;FF)V", "uiLoop", "(L" + SP + ";Ljava/lang/String;FF)V", INVOKEINTERFACE));
        r.add(new Rule("MagicSubsystem.advanceInternal", "org/magiclib/subsystems/MagicSubsystem", "advanceInternal", "(F)V", "subsystemAdvance", "(Ljava/lang/Object;F)V", INVOKEVIRTUAL));
        return r;
    }

    /**
     * The game's own code: each ship's frame runs inside the ship's context, and so does each frame of a
     * screen-wide plugin a script added while running for a joiner's ship.
     */
    public static List<Rule> engineRules() {
        String P = "com/fs/starfarer/api/combat/EveryFrameCombatPlugin";
        List<Rule> r = new ArrayList<Rule>();
        r.add(new Rule("Ship.advance", "com/fs/starfarer/combat/entities/Ship", "advance", "(F)V", "shipAdvance", "(Lcom/fs/starfarer/combat/entities/Ship;F)V", INVOKEVIRTUAL));
        r.add(new Rule("plugin advance", P, "advance", "(FLjava/util/List;)V", "pluginAdvance", "(L" + P + ";FLjava/util/List;)V", INVOKEINTERFACE));
        return r;
    }
}
