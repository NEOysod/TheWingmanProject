package wingman.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.campaign.fleet.CampaignFleet;
import com.fs.starfarer.coreui.CaptainPickerDialog;
import com.fs.starfarer.rpg.OfficerData;
import com.fs.starfarer.rpg.Person;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

import wingman.net.Roster;

/**
 * Opens the vanilla officer skill-choice modal over Wingman's title-screen lobby.
 *
 * The native skill picker is the 0.98a-RC8 coreui.B dialog. It normally gets opened by the officer-management
 * row (coreui.ooo0)'s private ÓÔ0000() method. This bridge creates a hidden, presentation-only owner row with a
 * full-screen native UI parent, then invokes that method so PatchLib hooks on the game's ordinary picker path
 * continue to run. The game objects used here are disposable copies; only the selected skill ID is returned.
 *
 * The controller class/method and reflective row constructor are version-specific. They are localized here and guarded
 * by the game-version check and runtime signature checks. If the game's layout changes, opening fails closed.
 */
public final class OfficerSkillMenu {
    private OfficerSkillMenu() {}

    /** Main receives only a skill choice; it must send that intent to the host for validation/application. */
    public interface Callback {
        void selected(String skillId);
    }

    private static final String GAME_VERSION = "0.98a-RC8";
    private static final String NATIVE_OWNER = "com.fs.starfarer.coreui.ooo0";
    private static final String NATIVE_OWNER_OPEN_SKILLS = "ÓÔ0000";

    private static Session session;
    private static String error;

    private static final class Session {
        final CustomPanelAPI root;
        final NativeOfficerData data;
        Object row;
        final Callback callback;
        String selected;
        boolean modalObserved;
        boolean dismissed;
        boolean delivered;

        Session(CustomPanelAPI root, NativeOfficerData data, Object row, Callback callback) {
            this.root = root;
            this.data = data;
            this.row = row;
            this.callback = callback;
        }
    }

    /**
     * Open the normal (non-elite) vanilla skill picker for the supplied disposable officer copy.
     * The list is the host's already-computed offer set; it is never re-rolled locally.
     * Returns false and exposes a message through takeError() if it could not be opened.
     */
    public static synchronized boolean open(PersonAPI presentationCopy, List<String> hostOfferedSkillIds, Callback callback) {
        if (isOpen()) return fail("A vanilla officer skill menu is already open.", null);
        error = null;
        if (Global.getCurrentState() != GameState.TITLE) return fail("The vanilla officer skill menu is only available from the title lobby.", null);
        if (presentationCopy == null || callback == null) return fail("The officer skill menu has no officer or callback.", null);
        if (hostOfferedSkillIds == null || hostOfferedSkillIds.isEmpty()) return fail("The host did not offer any skills for this officer.", null);
        try {
            String version = Global.getSettings().getVersionString();
            if (version == null || !version.contains(GAME_VERSION)) {
                return fail("The vanilla officer skill menu is not verified for game version " + version + ".", null);
            }

            List<String> choices = checkedChoices(hostOfferedSkillIds);
            if (choices.isEmpty()) return fail("The host's skill choices are unavailable in this game.", null);

            Person copy = copyPerson(presentationCopy);
            NativeOfficerData data = new NativeOfficerData(copy, choices);
            CampaignFleet fleet = (CampaignFleet) Global.getFactory().createEmptyFleet("player", "Wingman officer skill menu", false);
            copy.setFleet(fleet);
            copy.getStats().setFleet(fleet);
            fleet.getFleetData().addOfficer(data);

            CustomPanelAPI root = Global.getSettings().createCustom(
                    Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight(), null);
            root.getPosition().setLocation(0f, 0f);
            Session opening = new Session(root, data, null, callback);
            session = opening;

            Object owner = createPickerOwner(fleet, root);
            Object row = createOfficerRow(owner, fleet, data);
            opening.row = row;
            invokeVanillaOpener(row);
            // Refresh the native panel's child snapshot and verify the vanilla show() attached its modal and
            // event interceptor. This also arms a source-verified dismissal fallback if the PatchLib hook fails.
            root.advance(0f);
            if (!hasRootChildren(root)) {
                closeInternal();
                return fail("The vanilla officer skill menu did not attach to the title UI.", null);
            }
            opening.modalObserved = true;
            return true;
        } catch (Throwable t) {
            Global.getLogger(OfficerSkillMenu.class).warn("Wingman: the vanilla officer skill menu could not be opened", t);
            closeInternal();
            return fail("The vanilla officer skill menu could not be opened (see the log).", t);
        }
    }

    private static List<String> checkedChoices(List<String> source) {
        if (source.size() > Roster.MAX_SKILL_CHOICES) {
            throw new IllegalArgumentException("host skill choice list exceeds the roster choice limit");
        }
        List<String> out = new ArrayList<String>();
        for (String id : source) {
            if (id == null || id.isEmpty() || out.contains(id)) continue;
            SkillSpecAPI spec = Global.getSettings().getSkillSpec(id);
            // The host owns eligibility. Keep modded/expanded offers; only omit IDs this client cannot render.
            if (spec == null) continue;
            out.add(id);
        }
        return Collections.unmodifiableList(out);
    }

    /** Copy all visible officer identity, progression, skills, scalar memory and tags; never share mutable stats. */
    private static Person copyPerson(PersonAPI source) {
        String personality = source.getPersonalityAPI() == null ? "steady" : source.getPersonalityAPI().getId();
        Person result = new Person(personality == null || personality.isEmpty() ? "steady" : personality);
        result.setId(source.getId());
        result.setName(new FullName(source.getName().getFirst(), source.getName().getLast(), source.getGender()));
        result.setGender(source.getGender());
        result.setRankId(source.getRankId());
        result.setPostId(source.getPostId());
        result.setPortraitSprite(source.getPortraitSprite());
        result.setFaction(source.getFaction() == null ? "neutral" : source.getFaction().getId());
        result.setAICoreId(source.getAICoreId());
        result.setImportance(source.getImportance());
        result.setVoice(source.getVoice());
        for (String tag : source.getTags()) result.addTag(tag);

        MutableCharacterStatsAPI from = source.getStats();
        MutableCharacterStatsAPI to = result.getStats();
        to.setSkipRefresh(true);
        to.setLevel(from.getLevel());
        to.setXP(from.getXP());
        to.setBonusXp(from.getBonusXp());
        to.setPoints(from.getPoints());
        for (MutableCharacterStatsAPI.SkillLevelAPI skill : from.getSkillsCopy()) {
            if (skill.getLevel() > 0f && skill.getSkill() != null) to.setSkillLevel(skill.getSkill().getId(), skill.getLevel());
        }
        for (String aptitude : Global.getSettings().getAptitudeIds()) {
            float level = from.getAptitudeLevel(aptitude);
            if (level > 0f) to.setAptitudeLevel(aptitude, level);
        }
        to.setSkipRefresh(false);

        // Campaign references (fleets, entities, markets) are intentionally not copied into the title-screen view.
        // Scalar memory includes host-only display markers such as an officer's follow identity.
        try {
            for (String key : source.getMemoryWithoutUpdate().getKeys()) {
                Object value = source.getMemoryWithoutUpdate().get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    result.getMemoryWithoutUpdate().set(key, value);
                }
            }
        } catch (Throwable ignored) { /* the visible officer data does not depend on optional memory */ }
        return result;
    }

    private static Object createPickerOwner(CampaignFleet fleet, CustomPanelAPI root) {
        if (!(root instanceof com.fs.starfarer.ui.interfacenew)) {
            throw new IllegalStateException("vanilla title UI parent has an unexpected type");
        }
        return new QuietCaptainPickerDialog(fleet, root);
    }

    /**
     * CaptainPickerDialog's constructor calls sizeToInner(), which normally builds campaign officer rows and reads
     * CampaignEngine's player person. The vanilla picker only needs its parent pointer; this fixture suppresses that
     * virtual size callback during construction, avoiding all campaign-only screen initialization at TITLE.
     */
    private static final class QuietCaptainPickerDialog extends CaptainPickerDialog {
        QuietCaptainPickerDialog(CampaignFleet fleet, CustomPanelAPI root) {
            super(fleet, null, (com.fs.starfarer.ui.interfacenew) root, null);
            // ooo0 opens the skill picker on getParent(), which only setParent() assigns; the constructor
            // argument above fills a different field. Parent without adding, so the owner itself never renders.
            setParent((com.fs.starfarer.ui.interfacenew) root);
        }

        @Override public void sizeChanged(float width, float height) {
            // Intentionally do not build the officer-management screen's campaign-backed list.
        }
    }

    private static Object createOfficerRow(Object owner, Object fleet, NativeOfficerData data) throws Throwable {
        ClassLoader loader = owner.getClass().getClassLoader();
        Class<?> rowType = Class.forName(NATIVE_OWNER, false, loader);
        Class<?> pickerType = CaptainPickerDialog.class;
        Class<?> fleetType = Class.forName("com.fs.starfarer.campaign.fleet.CampaignFleet", false, loader);
        Class<?> memberType = Class.forName("com.fs.starfarer.campaign.fleet.FleetMember", false, loader);
        MethodType signature = MethodType.methodType(void.class, pickerType, fleetType, memberType, OfficerData.class,
                boolean.class, boolean.class);
        return privateLookup(rowType).findConstructor(rowType, signature).invoke(owner, fleet, null, data, true, false);
    }

    private static void invokeVanillaOpener(Object row) throws Throwable {
        Class<?> type = row.getClass();
        privateLookup(type).findVirtual(type, NATIVE_OWNER_OPEN_SKILLS, MethodType.methodType(void.class)).invoke(row);
    }

    /**
     * Source-verified for 0.98a-RC8: CustomPanelAPI's concrete panel extends interfacenew, whose getCopy() returns
     * its current component snapshot. Vanilla oo0O.show adds exactly the dialog and its input interceptor there;
     * oo0O.advanceImpl removes both once dismissal fades out. This fallback avoids leaving a blank modal if a
     * PatchLib dismissal handler was not installed.
     */
    private static boolean hasRootChildren(CustomPanelAPI root) {
        if (!(root instanceof com.fs.starfarer.ui.interfacenew)) return false;
        List<?> children = ((com.fs.starfarer.ui.interfacenew) root).getCopy();
        return children != null && !children.isEmpty();
    }

    private static MethodHandles.Lookup privateLookup(Class<?> type) throws Throwable {
        MethodHandle privateLookupIn = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
        return (MethodHandles.Lookup) privateLookupIn.invoke(type, MethodHandles.lookup());
    }

    private static boolean fail(String message, Throwable cause) {
        error = message;
        if (cause != null) Global.getLogger(OfficerSkillMenu.class).info("Wingman: " + message, cause);
        return false;
    }

    /** The menu is active until native confirmation, native cancellation, a failure, or explicit close. */
    public static synchronized boolean isOpen() {
        return session != null && session.root != null && !session.dismissed;
    }

    /** Advances the same UI tree that owns the vanilla dialog. */
    public static synchronized void advance(float amount) {
        Session s = session;
        if (s == null) return;
        try {
            s.root.advance(amount);
            if (s.modalObserved && !hasRootChildren(s.root)) s.dismissed = true;
            deliverIfDismissed(s);
        } catch (Throwable t) {
            Global.getLogger(OfficerSkillMenu.class).warn("Wingman: the vanilla officer skill menu failed while advancing", t);
            closeInternal();
            error = "The vanilla officer skill menu failed (see the log).";
        }
    }

    /** Sends input to the vanilla UI first and consumes it so the lobby cannot act underneath the modal. */
    public static synchronized void input(List<InputEventAPI> events) {
        Session s = session;
        if (s == null || events == null) return;
        try {
            s.root.processInput(events);
            for (InputEventAPI event : events) {
                try { event.consume(); } catch (Throwable ignored) {}
            }
            deliverIfDismissed(s);
        } catch (Throwable t) {
            Global.getLogger(OfficerSkillMenu.class).warn("Wingman: the vanilla officer skill menu failed while processing input", t);
            closeInternal();
            error = "The vanilla officer skill menu failed (see the log).";
        }
    }

    /** Renders the native modal after the lobby so it stays visually and interactively on top. */
    public static synchronized void render() {
        Session s = session;
        if (s == null) return;
        try {
            s.root.render(1f);
        } catch (Throwable t) {
            Global.getLogger(OfficerSkillMenu.class).warn("Wingman: the vanilla officer skill menu failed while rendering", t);
            closeInternal();
            error = "The vanilla officer skill menu failed (see the log).";
        }
    }

    /** Closes without a selection (for Escape from the lobby, disconnect, battle start, or title leave). */
    public static synchronized void close() {
        closeInternal();
    }

    /** Takes and clears the most recent clean failure message for lobby presentation. */
    public static synchronized String takeError() {
        String result = error;
        error = null;
        return result;
    }

    private static void deliverIfDismissed(Session s) {
        if (session != s || !s.dismissed || s.delivered) return;
        s.delivered = true;
        String selected = s.selected;
        Callback callback = s.callback;
        closeInternal();
        if (selected != null && callback != null) {
            try {
                callback.selected(selected);
            } catch (Throwable t) {
                Global.getLogger(OfficerSkillMenu.class).warn("Wingman: the officer skill selection callback failed", t);
                error = "The selected skill could not be sent (see the log).";
            }
        }
    }

    private static void closeInternal() {
        session = null;
    }

    /**
     * Native coreui.ooo0 notifies its vanilla dialog parent after the normal picker closes. Observe only the
     * hidden row created by this helper so both confirm and cancel end the title modal exactly once.
     */
    @Patch(target = @ClassMatch(typeName = NATIVE_OWNER))
    public static final class NativeOwnerDismissHook {
        @Before(target = @MethodMatch(methodName = "dialogDismissed", parameterCount = 2))
        public static void before(BeforeContext ctx) {
            try {
                Session s = session;
                if (s != null && ctx.getSelf() == s.row) {
                    s.dismissed = true;
                    // The synthetic owner is intentionally not laid out as an officer-management screen.
                    // Bypass its callback only; the vanilla skill-picker dialog itself has already completed.
                    ctx.skipOriginal(null);
                }
            } catch (Throwable t) {
                Global.getLogger(OfficerSkillMenu.class).info("Wingman: native officer menu dismissal could not be observed", t);
            }
        }
    }

    /** Native coreui.B invokes OfficerData.levelUp(skillId); override it to emit intent, never mutate the copy. */
    private static final class NativeOfficerData extends OfficerData {
        private final List<String> picks;
        private boolean submitted;

        NativeOfficerData(Person person, List<String> picks) {
            super(person);
            this.picks = picks;
        }

        @Override public List<String> getSkillPicks() { return picks; }
        @Override public boolean isMadePicks() { return true; }
        @Override public void makeSkillPicks() { /* host supplied the choices; never reroll */ }
        @Override public void makeSkillPicks(java.util.Random random) { /* host supplied the choices; never reroll */ }
        @Override public boolean canLevelUp() { return !submitted && !picks.isEmpty(); }
        @Override public boolean canLevelUp(boolean allowAnyLevel) { return !submitted && !picks.isEmpty(); }

        @Override public void levelUp(String skillId) {
            Session s = session;
            if (submitted || s == null || s.data != this || skillId == null || !picks.contains(skillId)) return;
            submitted = true;
            s.selected = skillId;
        }

        @Override public void levelUp(String skillId, java.util.Random random) {
            levelUp(skillId);
        }
    }
}
