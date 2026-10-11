import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import wingman.agent.Agent;
import wingman.agent.Patcher;

/**
 * Offline check of the agent's patcher: runs it over every class in the jars given, as the agent would at load
 * time, writes the changed classes to a folder (for javap and the verifier), and checks each changed class
 * reads back cleanly with nothing left to swap.
 *
 * java -cp WingmanAgent.jar:agentcheck AgentCheck outDir a.jar b.jar ...
 */
public class AgentCheck {
    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        int seen = 0, changed = 0, swaps = 0, failed = 0;
        for (int i = 1; i < args.length; i++) {
            ZipFile z = new ZipFile(args[i]);
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                if (!en.getName().endsWith(".class")) continue;
                String name = en.getName().substring(0, en.getName().length() - 6);
                List<Patcher.Rule> rules = Agent.rulesFor(name);
                if (rules == null) continue;
                seen++;
                byte[] b = read(z.getInputStream(en));
                Patcher.Result r;
                try { r = Patcher.patch(b, rules, Agent.framesFor(rules), false); } catch (Throwable t) { failed++; System.out.println("CANNOT READ " + name + ": " + t); continue; }
                if (r == null) continue;
                changed++;
                swaps += r.swaps;
                if (r.bytes.length <= b.length) throw new AssertionError("no constants added: " + name);
                // Read back: the pool must parse and nothing must match any more.
                Patcher.Result again = Patcher.patch(r.bytes, rules);
                if (again != null) throw new AssertionError("still matches after patching: " + name);
                File f = new File(out, en.getName());
                f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(r.bytes);
                fo.close();
                System.out.println("changed " + name + " (" + r.swaps + ")");
            }
            z.close();
        }
        System.out.println("classes looked at " + seen + ", changed " + changed + ", calls swapped " + swaps + ", unreadable " + failed);
        System.out.println(Agent.report());
    }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
