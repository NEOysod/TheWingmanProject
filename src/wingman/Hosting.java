package wingman;

import java.io.IOException;

import wingman.net.Conn;
import wingman.net.HostSession;

/** Opening the host's session with the saved settings: by a battle starting, or by another mod (WingmanAPI.openHost). */
public final class Hosting {
    private Hosting() {}

    /** Opens the session (or takes the one already open) with these settings. */
    public static HostSession open(WingmanConfig cfg) throws IOException {
        int mode = "off".equals(cfg.modCheck) ? HostSession.MOD_CHECK_OFF
                : "all".equals(cfg.modCheck) ? HostSession.MOD_CHECK_ALL : HostSession.MOD_CHECK_CONTENT;
        HostSession session = HostSession.getOrStart(cfg.port, cfg.maxPlayers, cfg.snapshotRate, cfg.playerName,
                Mods.enabled(), mode);
        session.setAccess(cfg.password, Mods.gameVersion());
        Conn.compression = cfg.compressLink;
        Conn.udp = cfg.useUdp;
        session.setSteadyControls(cfg.steadyControls);
        wingman.net.Budget.bytesPerSecond = Math.max(0, cfg.bandwidthLimit) * 1024;
        return session;
    }

    /** This build of Wingman, as written in the log at start-up. */
    public static String buildId() { return Build.ID; }
}
