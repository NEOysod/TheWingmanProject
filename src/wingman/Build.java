package wingman;

/** Which build of the jar this is (build.sh writes the real one into build/gen; this copy is only for editors), for the log: zips of one version differ. */
final class Build {
    private Build() {}
    static final String ID = "unknown (not built by build.sh)";
}
