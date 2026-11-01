package wingman;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * Item pictures, for the joiner's refit screen and the host's friend refit panel alike: the sprite for a weapon,
 * fighter wing ("w:" and "f:" keys) or hull mod, drawn in its box.
 */
public final class RefitIcons {
    private RefitIcons() {}

    /** Behind a picture that has no sprite, or is still loading. */
    public static final Color BACK = new Color(255, 255, 255, 16);

    /** A picture for an item key ("w:" weapon, "f:" fighter wing) or a hull mod id, or null. */
    public static SpriteAPI picture(String key, boolean mod) {
        if (key == null) return null;
        try {
            if (mod) return Style.sprite(Global.getSettings().getHullModSpec(key).getSpriteName());
            String id = key.substring(2);
            if (key.startsWith("f:")) return Style.sprite(Global.getSettings().getFighterWingSpec(id).getVariant().getHullSpec().getSpriteName());
            WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(id);
            SpriteAPI s = Style.sprite(ws.getTurretSpriteName());
            return s != null ? s : Style.sprite(ws.getHardpointSpriteName());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void iconBox(SpriteAPI s, float x, float y, float size, boolean faded) {
        Style.fill(x, y, size, size, BACK);
        if (s == null) return;
        s.setAlphaMult(faded ? 0.4f : 1f);
        Style.icon(s, x + 2f, y + 2f, size - 4f);
        s.setAlphaMult(1f);
    }
}