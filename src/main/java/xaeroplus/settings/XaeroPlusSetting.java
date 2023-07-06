package xaeroplus.settings;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.ITextComponent;

public abstract class XaeroPlusSetting {
    public static final String SETTING_PREFIX = "[XP] ";
    private final String settingName; // enumString
    private ITextComponent tooltip;
    private static boolean ingameOnly = false;
    private static boolean requiresMinimap = false;
    private static boolean switchScreen = false;

    private KeyBinding keyBinding;

    public XaeroPlusSetting(String settingName,
                            ITextComponent tooltip, // nullable
                            KeyBinding keyBinding // nullable
    ) {
        this.settingName = settingName;
        this.tooltip = tooltip;
        this.keyBinding = keyBinding;
    }

    protected static String defaultValueStr(final String settingName, final Object defaultVal) {
        return settingName + " \\n    Default: " + defaultVal + " \\n ";
    }

    public String getSettingName() {
        return settingName;
    }

    public ITextComponent getTooltip() {
        return tooltip;
    }
    public boolean isIngameOnly() {
        return ingameOnly;
    }
    public boolean isRequiresMinimap() {
        return requiresMinimap;
    }
    public KeyBinding getKeyBinding() {
        return keyBinding;
    }
    public void setKeyBinding(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }
    public abstract void init();
}
