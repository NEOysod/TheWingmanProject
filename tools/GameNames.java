import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Build tool: the obfuscated names MessageSpy is pointed at, read from the game's own jars, so a game update that
 * renames them needs no edit here. Prints, as internal names (a/b/C), the class of CombatState.messageWidget (the
 * battle's message feed) and the type its o00000(Object...) returns (one line of the feed), one per line.
 * Run with -noverify: the game's classes have names the verifier refuses. Usage: java -noverify GameNames core-folder
 */
public class GameNames {
    public static void main(String[] a) throws Exception {
        List<URL> jars = new ArrayList<URL>();
        for (File f : new File(a[0]).listFiles()) if (f.getName().endsWith(".jar")) jars.add(f.toURI().toURL());
        ClassLoader cl = new URLClassLoader(jars.toArray(new URL[0]), null);
        Class<?> state = Class.forName("com.fs.starfarer.combat.CombatState", false, cl);
        Field f = state.getDeclaredField("messageWidget");
        Class<?> feed = f.getType();
        Method m = feed.getDeclaredMethod("o00000", Object[].class);
        System.out.println(feed.getName().replace('.', '/'));
        System.out.println(m.getReturnType().getName().replace('.', '/'));
    }
}
