package wingman.agent;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Starts the game with Wingman's agent where {@code -javaagent} can't be used: a launcher that replaces Java's
 * own system class loader (as the Fast Rendering mod's {@code -Djava.system.class.loader=...} does) makes Java
 * refuse every {@code -javaagent} and stop. Java's own launcher has a second way in, meant for programs started
 * from a jar: "Launcher-Agent-Class". That way doesn't add anything to the class path (the jar is on it
 * already), so the system class loader is never asked to.
 *
 * In the launch settings, instead of {@code -javaagent:...}:
 * <ul>
 * <li>WingmanAgent.jar added to {@code -classpath};</li>
 * <li>{@code --add-exports=java.instrument/sun.instrument=ALL-UNNAMED} (Java's loader for this is internal);</li>
 * <li>{@code wingman.agent.Launch} as the main class, in place of {@code com.fs.starfarer.StarfarerLauncher}.</li>
 * </ul>
 * This loads the agent, then starts the game's own main class (or the one named by {@code -Dwingman.main=...})
 * with the same arguments. If the agent can't be loaded, the game starts without it.
 */
public final class Launch {
    private Launch() {}

    public static void main(String[] args) throws Throwable {
        try {
            File jar = new File(Launch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Class<?> impl = Class.forName("sun.instrument.InstrumentationImpl");
            Method load = impl.getMethod("loadAgent", String.class);
            load.invoke(null, jar.getPath());
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            System.err.println("Wingman agent: could not be loaded this way (" + c + "); the game starts without it."
                    + (c instanceof IllegalAccessException ? " Is --add-exports=java.instrument/sun.instrument=ALL-UNNAMED in the launch settings?" : ""));
        }
        String main = System.getProperty("wingman.main", "com.fs.starfarer.StarfarerLauncher");
        Method m = Class.forName(main, true, ClassLoader.getSystemClassLoader()).getMethod("main", String[].class);
        try {
            m.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
