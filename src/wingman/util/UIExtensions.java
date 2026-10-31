package wingman.util;

import java.util.List;

import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;

public final class UIExtensions {
    private UIExtensions() {}

    @SuppressWarnings("unchecked")
    public static List<UIComponentAPI> getChildrenCopy(UIPanelAPI panel) throws Throwable {
        return (List<UIComponentAPI>) ReflectionUtils.invoke("getChildrenCopy", panel);
    }

    @SuppressWarnings("unchecked")
    public static List<UIComponentAPI> getChildrenNonCopy(UIPanelAPI panel) throws Throwable {
        return (List<UIComponentAPI>) ReflectionUtils.invoke("getChildrenNonCopy", panel);
    }

    public static float getWidth(UIComponentAPI component) throws Throwable {
        return (Float) ReflectionUtils.invoke("getWidth", component);
    }

    public static float getHeight(UIComponentAPI component) throws Throwable {
        return (Float) ReflectionUtils.invoke("getHeight", component);
    }

    public static void clearChildren(UIPanelAPI panel) throws Throwable {
        ReflectionUtils.invoke("clearChildren", panel);
    }

    public static UIPanelAPI getParent(UIComponentAPI component) throws Throwable {
        return (UIPanelAPI) ReflectionUtils.invoke("getParent", component);
    }

    public static UIComponentAPI getParentWidget(TooltipMakerAPI tooltip) throws Throwable {
        return (UIComponentAPI) ReflectionUtils.invoke("getParentWidget", tooltip);
    }

    public static void setOpacity(UIComponentAPI component, float alpha) throws Throwable {
        ReflectionUtils.invoke("setOpacity", component, alpha);
    }

    public static float getOpacity(UIComponentAPI component) throws Throwable {
        return (Float) ReflectionUtils.invoke("getOpacity", component);
    }
}
