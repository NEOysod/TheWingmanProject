import java.io.BufferedReader;
import java.io.FileReader;

/**
 * With -javaagent: loads each class named in a list (one per line, a/b/C form), so the agent patches it and the
 * JVM's verifier checks the result. A VerifyError is a failure; a class whose own dependencies are missing here
 * (no game install in the test) is counted apart, not as a pass.
 */
public class AgentVerify {
    public static void main(String[] args) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(args[0]));
        int ok = 0, bad = 0, unknown = 0;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String name = line.replace('/', '.');
            try {
                Class.forName(name, true, AgentVerify.class.getClassLoader());
                ok++;
            } catch (VerifyError e) {
                bad++;
                System.out.println("VERIFY ERROR " + name + ": " + e);
            } catch (ExceptionInInitializerError e) {
                ok++; // verified; its static setup wants the running game
            } catch (Throwable e) {
                unknown++;
                if (args.length > 1) System.out.println("could not load " + name + ": " + e);
            }
        }
        System.out.println("verified " + ok + ", verify errors " + bad + ", not checkable here " + unknown);
        try { System.out.println(Class.forName("wingman.agent.Agent").getMethod("report").invoke(null)); } catch (Throwable t) { /* needs the game's classes */ }
        if (bad > 0) System.exit(1);
    }
}
