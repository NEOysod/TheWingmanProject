import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Build tool: the obfuscated names MessageSpy is pointed at, read from the game's own jars, so a game update that
 * renames them needs no edit here. Prints, one per line: as internal names (a/b/C), the class of
 * CombatState.messageWidget (the battle's message feed) and the type its (Object...) method returns (one line of the
 * feed); then that method's name (o00000 in 0.98.5a-RC7, super in RC8), which MessageSpy overrides.
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
        Method m = null;
        for (Method x : feed.getDeclaredMethods()) {
            Class<?>[] p = x.getParameterTypes();
            if (p.length == 1 && p[0] == Object[].class && !x.getReturnType().isPrimitive()) {
                if (m != null) throw new IllegalStateException("two (Object...) methods in the feed");
                m = x;
            }
        }
        if (m == null) throw new IllegalStateException("no (Object...) method in the feed");
        feed.getDeclaredMethod(m.getName(), int.class, Object[].class); // and its (int, Object...) partner, of the same name
        System.out.println(feed.getName().replace('.', '/'));
        System.out.println(m.getReturnType().getName().replace('.', '/'));
        System.out.println(m.getName());
    }
}
