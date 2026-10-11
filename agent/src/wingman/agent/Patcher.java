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

    /** Apply the call rules only (as before frames were added). */
    public static Result patch(byte[] b, List<Rule> rules) throws IOException {
        return patch(b, rules, java.util.Collections.<Frame>emptyList(), false);
    }

    /** Classes left alone because they have names a strict JVM refuses in a changed class (see Agent.strict). */
    public static volatile int strictSkipped;

    /**
     * Apply the rules to one class: swap the matching calls in place, and wrap the methods that match a frame
     * rule (see {@link Frame}). Returns null if nothing in it matches (the class is left as it is). Throws on a
     * class file it can't read; the caller leaves such a class alone.
     *
     * strict: the JVM checks every name in a changed class even with verification off (Java 22 and later do), so
     * a class with names Java source can't have (the game's obfuscated ones, "this.super") is left alone.
     */
    public static Result patch(byte[] b, List<Rule> rules, List<Frame> frames, boolean strict) throws IOException {
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
        int major = p.u2(6);
        int access = p.u2(p.end), thisClass = p.u2(p.end + 2);
        boolean isInterface = (access & 0x0200) != 0;

        // Walk the class: fields, then methods (where they are, and which ones a frame rule wraps).
        boolean illegal = false;
        int at = p.end + 6;
        int interfaces = p.u2(at); at += 2 + 2 * interfaces;
        int fields = p.u2(at); at += 2;
        for (int i = 0; i < fields; i++) {
            if (badName(p.utf(p.u2(at + 2)), false)) illegal = true;
            at += 6;
            int attrs = p.u2(at); at += 2;
            for (int a = 0; a < attrs; a++) at += 6 + u4(b, at + 2);
        }
        int methodsCountAt = at;
        int methods = p.u2(at); at += 2;
        List<int[]> codes = new ArrayList<int[]>();      // {code start, code length}
        List<int[]> wraps = new ArrayList<int[]>();      // {method start, frame index}
        List<String> wrapNames = new ArrayList<String>();
        for (int m = 0; m < methods; m++) {
            int mStart = at;
            int mAccess = p.u2(at);
            String name = p.utf(p.u2(at + 2)), desc = p.utf(p.u2(at + 4));
            if (badName(name, true)) illegal = true;
            at += 6;
            int attrs = p.u2(at); at += 2;
            boolean hasCode = false;
            for (int a = 0; a < attrs; a++) {
                int nameIdx = p.u2(at);
                int len = u4(b, at + 2);
                int body = at + 6;
                if ("Code".equals(p.utf(nameIdx))) {
                    hasCode = true;
                    codes.add(new int[] { body + 8, u4(b, body + 4) });
                }
                at = body + len;
            }
            if (!isInterface && hasCode && (mAccess & 0x0008) == 0) { // instance methods with code, in classes
                for (int f = 0; f < frames.size(); f++) {
                    Frame fr = frames.get(f);
                    if (fr.name.equals(name) && fr.desc.equals(desc)) { wraps.add(new int[] { mStart, f }); wrapNames.add(name); break; }
                }
            }
        }
        int methodsEnd = at;
        for (int i = 1; i < p.count && !illegal; i++) {
            if (p.tag[i] == 12 && badName(p.utf(p.u2(p.start[i] + 1)), true)) illegal = true;
        }
        if (byIndex.isEmpty() && wraps.isEmpty()) return null;
        if (strict && illegal) { strictSkipped++; return null; }

        // New constants, appended: the Hooks class, a Methodref per call rule used, and what the wrappers need.
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        DataOutputStream ex = new DataOutputStream(extra);
        int[] next = { p.count };
        int hooksClass = classConst(ex, next, HOOKS);
        Map<Rule, Integer> refOf = new HashMap<Rule, Integer>();
        for (Rule r : new java.util.LinkedHashSet<Rule>(byIndex.values())) {
            refOf.put(r, methodRef(ex, next, hooksClass, r.hookName, r.hookDesc));
        }

        // Swap the calls.
        byte[] out = b.clone();
        int swaps = 0;
        if (!byIndex.isEmpty()) for (int[] c : codes) swaps += swapCalls(b, out, c[0], c[1], byIndex, refOf);

        // Wrap the methods: the original is renamed (private), and a new method of the old name runs it as part
        // of the right ship's frame.
        ByteArrayOutputStream added = new ByteArrayOutputStream();
        int wrapped = 0;
        if (!wraps.isEmpty()) {
            int enter = methodRef(ex, next, hooksClass, "enterFrame", "(Ljava/lang/Object;I)Ljava/lang/Object;");
            int exit = methodRef(ex, next, hooksClass, "exitFrame", "(Ljava/lang/Object;)V");
            int codeUtf = utf(ex, next, "Code");
            int smtUtf = major >= 50 ? utf(ex, next, "StackMapTable") : 0;
            int objClass = classConst(ex, next, "java/lang/Object");
            int throwClass = classConst(ex, next, "java/lang/Throwable");
            Map<String, Integer> classes = new HashMap<String, Integer>();
            for (int[] w : wraps) {
                Frame fr = frames.get(w[1]);
                int mStart = w[0];
                int mAccess = p.u2(mStart);
                int nameIdx = p.u2(mStart + 2), descIdx = p.u2(mStart + 4);
                String renamed = fr.name + "$wingman";
                int renamedUtf = utf(ex, next, renamed);
                int renamedRef = methodRef(ex, next, thisClass, renamed, fr.desc);
                // the original, renamed and made private (only the wrapper calls it, with invokespecial)
                out[mStart] = (byte) (((mAccess & ~0x0005) | 0x0002) >>> 8);
                out[mStart + 1] = (byte) ((mAccess & ~0x0005) | 0x0002);
                out[mStart + 2] = (byte) (renamedUtf >>> 8);
                out[mStart + 3] = (byte) renamedUtf;
                // reference types of the parameters, for the stack map
                List<String> params = params(fr.desc);
                int[] paramClass = new int[params.size()];
                for (int i = 0; i < params.size(); i++) {
                    String t = params.get(i);
                    if (t.charAt(0) != 'L' && t.charAt(0) != '[') continue;
                    String cn = t.charAt(0) == 'L' ? t.substring(1, t.length() - 1) : t;
                    Integer ci = classes.get(cn);
                    if (ci == null) { ci = classConst(ex, next, cn); classes.put(cn, ci); }
                    paramClass[i] = ci;
                }
                byte[] method = wrapper((mAccess & ~0x0060) /* not synchronized, not bridge */, nameIdx, descIdx, codeUtf, smtUtf,
                        params, paramClass, thisClass, objClass, throwClass, fr, enter, exit, renamedRef);
                added.write(method);
                wrapped++;
                synchronized (fr) { fr.hits++; }
            }
        }
        if (next[0] > 0xFFFF) throw new IOException("constant pool full");
        ex.flush();
        if (swaps == 0 && wrapped == 0) return null;

        // Reassemble: header, the old pool + the new constants, the members (with the new methods), the rest.
        ByteArrayOutputStream all = new ByteArrayOutputStream(b.length + extra.size() + added.size());
        all.write(out, 0, 8);
        all.write(next[0] >>> 8); all.write(next[0] & 0xFF);
        all.write(out, 10, p.end - 10);
        all.write(extra.toByteArray());
        all.write(out, p.end, methodsCountAt - p.end);
        int count = methods + wrapped;
        all.write(count >>> 8); all.write(count & 0xFF);
        all.write(out, methodsCountAt + 2, methodsEnd - methodsCountAt - 2);
        all.write(added.toByteArray());
        all.write(out, methodsEnd, out.length - methodsEnd);
        Result res = new Result();
        res.bytes = all.toByteArray();
        res.swaps = swaps + wrapped;
        return res;
    }

    /**
     * A name Java source can't produce, which a strict JVM refuses in a changed class: '.', ';', '[' or '/' (and
     * for methods '<' or '>' outside &lt;init&gt; / &lt;clinit&gt;).
     */
    static boolean badName(String n, boolean method) {
        if (n.isEmpty()) return true;
        if (method && (n.equals("<init>") || n.equals("<clinit>"))) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/' || (method && (c == '<' || c == '>'))) return true;
        }
        return false;
    }

    private static int utf(DataOutputStream ex, int[] next, String s) throws IOException {
        ex.writeByte(1); ex.writeUTF(s);
        return next[0]++;
    }

    private static int classConst(DataOutputStream ex, int[] next, String internalName) throws IOException {
        int u = utf(ex, next, internalName);
        ex.writeByte(7); ex.writeShort(u);
        return next[0]++;
    }

    private static int methodRef(DataOutputStream ex, int[] next, int cls, String name, String desc) throws IOException {
        int n = utf(ex, next, name), d = utf(ex, next, desc);
        ex.writeByte(12); ex.writeShort(n); ex.writeShort(d);
        int nat = next[0]++;
        ex.writeByte(10); ex.writeShort(cls); ex.writeShort(nat);
        return next[0]++;
    }

    /** The parameter types of a method descriptor, one descriptor string each. */
    static List<String> params(String desc) {
        List<String> out = new ArrayList<String>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int s = i;
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
            i++;
            out.add(desc.substring(s, i));
        }
        return out;
    }

    /**
     * The wrapper method:
     * <pre>
     *   prev = Hooks.enterFrame(context, kind);
     *   try { this.name$wingman(params...); } finally { Hooks.exitFrame(prev); }
     * </pre>
     */
    private static byte[] wrapper(int access, int nameIdx, int descIdx, int codeUtf, int smtUtf, List<String> params, int[] paramClass,
            int thisClass, int objClass, int throwClass, Frame fr, int enter, int exit, int renamedRef) throws IOException {
        int[] slot = new int[params.size()];
        int s = 1;
        for (int i = 0; i < params.size(); i++) {
            slot[i] = s;
            char c = params.get(i).charAt(0);
            s += c == 'J' || c == 'D' ? 2 : 1;
        }
        int prevLocal = s, errLocal = s + 1;
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        // context
        if (fr.arg < 0) code.write(0x2a); // aload_0
        else load(code, 'L', slot[fr.arg]);
        code.write(0x10); code.write(fr.kind); // bipush kind
        code.write(0xb8); code.write(enter >>> 8); code.write(enter & 0xFF);
        store(code, prevLocal);
        int tryStart = code.size();
        code.write(0x2a);
        for (int i = 0; i < params.size(); i++) load(code, params.get(i).charAt(0), slot[i]);
        code.write(0xb7); code.write(renamedRef >>> 8); code.write(renamedRef & 0xFF); // invokespecial
        int tryEnd = code.size();
        load(code, 'L', prevLocal);
        code.write(0xb8); code.write(exit >>> 8); code.write(exit & 0xFF);
        code.write(0xb1); // return
        int handler = code.size();
        store(code, errLocal);
        load(code, 'L', prevLocal);
        code.write(0xb8); code.write(exit >>> 8); code.write(exit & 0xFF);
        load(code, 'L', errLocal);
        code.write(0xbf); // athrow
        byte[] c = code.toByteArray();

        // StackMapTable: one full frame at the handler. Locals: this, the parameters, prev; stack: a Throwable.
        ByteArrayOutputStream smt = new ByteArrayOutputStream();
        DataOutputStream sm = new DataOutputStream(smt);
        if (smtUtf != 0) {
            sm.writeShort(1);                 // number of entries
            sm.writeByte(255);                // full_frame
            sm.writeShort(handler);           // offset delta (first frame: the offset itself)
            sm.writeShort(1 + params.size() + 1);
            sm.writeByte(7); sm.writeShort(thisClass);
            for (int i = 0; i < params.size(); i++) {
                char t = params.get(i).charAt(0);
                if (t == 'J') sm.writeByte(4);
                else if (t == 'D') sm.writeByte(3);
                else if (t == 'F') sm.writeByte(2);
                else if (t == 'L' || t == '[') { sm.writeByte(7); sm.writeShort(paramClass[i]); }
                else sm.writeByte(1);         // int, boolean, byte, char, short
            }
            sm.writeByte(7); sm.writeShort(objClass);
            sm.writeShort(1);
            sm.writeByte(7); sm.writeShort(throwClass);
            sm.flush();
        }
        byte[] smb = smt.toByteArray();

        ByteArrayOutputStream m = new ByteArrayOutputStream();
        DataOutputStream mo = new DataOutputStream(m);
        mo.writeShort(access); mo.writeShort(nameIdx); mo.writeShort(descIdx);
        mo.writeShort(1);                     // one attribute: Code
        int codeAttrLen = 2 + 2 + 4 + c.length + 2 + 8 + 2 + (smtUtf != 0 ? 6 + smb.length : 0);
        mo.writeShort(codeUtf); mo.writeInt(codeAttrLen);
        mo.writeShort(Math.max(2, s));        // max stack: this + every parameter slot (at least 2 for enterFrame)
        mo.writeShort(errLocal + 1);          // max locals
        mo.writeInt(c.length); mo.write(c);
        mo.writeShort(1);                     // exception table
        mo.writeShort(tryStart); mo.writeShort(tryEnd); mo.writeShort(handler); mo.writeShort(0);
        if (smtUtf != 0) {
            mo.writeShort(1);
            mo.writeShort(smtUtf); mo.writeInt(smb.length); mo.write(smb);
        } else {
            mo.writeShort(0);
        }
        mo.flush();
        return m.toByteArray();
    }

    private static void load(ByteArrayOutputStream code, char type, int slot) {
        int op;
        switch (type) {
            case 'J': op = 0x16; break;                  // lload
            case 'F': op = 0x17; break;                  // fload
            case 'D': op = 0x18; break;                  // dload
            case 'L': case '[': op = 0x19; break;        // aload
            default: op = 0x15;                          // iload (int, boolean, byte, char, short)
        }
        if (slot > 255) { code.write(0xc4); code.write(op); code.write(slot >>> 8); code.write(slot & 0xFF); }
        else { code.write(op); code.write(slot); }
    }

    private static void store(ByteArrayOutputStream code, int slot) {
        if (slot > 255) { code.write(0xc4); code.write(0x3a); code.write(slot >>> 8); code.write(slot & 0xFF); }
        else { code.write(0x3a); code.write(slot); }   // astore
    }

    /**
     * A method of mods' classes run for one ship: a hullmod's advanceInCombat, a ship system's apply, a weapon's
     * every-frame and on-fire scripts, a beam's script, a plugin's advance. Wrapped so that, while it runs, the
     * ship it runs for is the acting ship (see Hooks.enterFrame). Matched by name and descriptor in any class.
     * Where the game's own engine can be changed too, Ship.advance gives the same context for everything in a
     * ship's frame; on a strict JVM (see patch) it can't, and these are what remains.
     */
    public static final class Frame {
        final String name, desc, label;
        /** Which parameter gives the ship (-1: this, for plugins), and how (Hooks.enterFrame's kind). */
        final int arg, kind;
        int hits;

        Frame(String label, String name, String desc, int arg, int kind) {
            this.label = label; this.name = name; this.desc = desc; this.arg = arg; this.kind = kind;
        }
    }

    static final int SHIP = 0, STATS = 1, WEAPON = 2, BEAM = 3, PLUGIN = 4, PROJECTILE = 5;

    public static List<Frame> frameRules() {
        String C = "Lcom/fs/starfarer/api/combat/";
        List<Frame> f = new ArrayList<Frame>();
        f.add(new Frame("hullmod frame", "advanceInCombat", "(" + C + "ShipAPI;F)V", 0, SHIP));
        f.add(new Frame("ship system frame", "apply", "(" + C + "MutableShipStatsAPI;Ljava/lang/String;Lcom/fs/starfarer/api/plugins/ShipSystemStatsScript$State;F)V", 0, STATS));
        f.add(new Frame("ship system frame", "unapply", "(" + C + "MutableShipStatsAPI;Ljava/lang/String;)V", 0, STATS));
        f.add(new Frame("weapon frame", "advance", "(F" + C + "CombatEngineAPI;" + C + "WeaponAPI;)V", 2, WEAPON));
        f.add(new Frame("weapon frame", "onFire", "(" + C + "DamagingProjectileAPI;" + C + "WeaponAPI;" + C + "CombatEngineAPI;)V", 1, WEAPON));
        f.add(new Frame("weapon frame", "onHit", "(" + C + "DamagingProjectileAPI;" + C + "CombatEntityAPI;Lorg/lwjgl/util/vector/Vector2f;Z"
                + C + "listeners/ApplyDamageResultAPI;" + C + "CombatEngineAPI;)V", 0, PROJECTILE));
        f.add(new Frame("beam frame", "advance", "(F" + C + "CombatEngineAPI;" + C + "BeamAPI;)V", 2, BEAM));
        f.add(new Frame("plugin frame", "advance", "(FLjava/util/List;)V", -1, PLUGIN));
        return f;
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
