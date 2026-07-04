package com.bcshow.showitem.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 不可变的配置快照。插件启动或 reload 时从 {@link FileConfiguration} 构建一次，
 * 之后所有监听器/注入器只读它，避免热路径反复访问 Bukkit config。
 *
 * <p>触发符采用「前缀 + selector + 后缀」模型，可覆盖多种书写习惯：
 * <pre>
 *   prefix="%" suffix=""   ->  %i   %3         （前缀式，裸数字）
 *   prefix="[" suffix="]"  ->  [i]  [3]        （包裹式）
 *   prefix="{" suffix="}"  ->  {i}  {3}
 * </pre>
 * selector ∈ { i / hand / offhand / head / chest / legs / feet / 1..9 及中文别名 }。</p>
 */
public final class PluginConfig {

    /** 触发的槽位为空时的处理策略。 */
    public enum EmptySlotAction {
        /** 原样保留触发符文本（如 {@code %i}），当作普通聊天内容。默认。 */
        KEEP,
        /** 替换为 {@code empty-slot-text} 占位文本（如 {@code [空]}）。 */
        TEXT,
        /** 直接删除该触发符，什么都不留。 */
        REMOVE;

        static EmptySlotAction of(final String raw) {
            if (raw == null) {
                return KEEP;
            }
            return switch (raw.trim().toLowerCase()) {
                case "text", "placeholder" -> TEXT;
                case "remove", "delete", "drop" -> REMOVE;
                default -> KEEP;
            };
        }
    }

    private final String triggerPrefix;
    private final String triggerSuffix;
    private final boolean hotbarEnabled;
    private final boolean slotSyntaxEnabled;
    private final String displayFormat;
    private final EmptySlotAction emptySlotAction;
    private final String emptySlotText;
    private final int maxItemsPerMessage;
    private final int maxItemBytes;
    private final boolean showAmountSuffix;
    private final boolean debug;

    private PluginConfig(final String triggerPrefix, final String triggerSuffix, final boolean hotbarEnabled,
                         final boolean slotSyntaxEnabled, final String displayFormat,
                         final EmptySlotAction emptySlotAction, final String emptySlotText,
                         final int maxItemsPerMessage, final int maxItemBytes, final boolean showAmountSuffix,
                         final boolean debug) {
        this.triggerPrefix = triggerPrefix;
        this.triggerSuffix = triggerSuffix;
        this.hotbarEnabled = hotbarEnabled;
        this.slotSyntaxEnabled = slotSyntaxEnabled;
        this.displayFormat = displayFormat;
        this.emptySlotAction = emptySlotAction;
        this.emptySlotText = emptySlotText;
        this.maxItemsPerMessage = maxItemsPerMessage;
        this.maxItemBytes = maxItemBytes;
        this.showAmountSuffix = showAmountSuffix;
        this.debug = debug;
    }

    /**
     * 从 Bukkit 配置构建快照，缺失项回退到与 config.yml 一致的默认值。
     *
     * <p>兼容旧配置：若存在旧键 {@code trigger}（如 {@code "%i"}）而未显式配置
     * {@code trigger-prefix}，则从旧值推导前缀（去掉结尾的 selector 字母 {@code i}）。</p>
     */
    public static PluginConfig from(final FileConfiguration config) {
        final String prefix = resolvePrefix(config);
        return new PluginConfig(
                prefix,
                config.getString("trigger-suffix", ""),
                config.getBoolean("hotbar-enabled", true),
                config.getBoolean("slot-syntax-enabled", true),
                config.getString("display-format", "&b[{name}]&r"),
                EmptySlotAction.of(config.getString("empty-slot-action", "keep")),
                config.getString("empty-slot-text", "&7[空]"),
                Math.max(1, config.getInt("max-items-per-message", 5)),
                Math.max(64, config.getInt("max-item-bytes", 8192)),
                config.getBoolean("show-amount-suffix", true),
                "debug".equalsIgnoreCase(config.getString("log-level", "info")));
    }

    private static String resolvePrefix(final FileConfiguration config) {
        if (config.isString("trigger-prefix")) {
            return config.getString("trigger-prefix", "%");
        }
        final String legacy = config.getString("trigger", null);
        if (legacy != null && legacy.endsWith("i") && legacy.length() > 1) {
            return legacy.substring(0, legacy.length() - 1);
        }
        return "%";
    }

    public String triggerPrefix() {
        return triggerPrefix;
    }

    public String triggerSuffix() {
        return triggerSuffix;
    }

    public boolean hotbarEnabled() {
        return hotbarEnabled;
    }

    public boolean slotSyntaxEnabled() {
        return slotSyntaxEnabled;
    }

    public String displayFormat() {
        return displayFormat;
    }

    public EmptySlotAction emptySlotAction() {
        return emptySlotAction;
    }

    public String emptySlotText() {
        return emptySlotText;
    }

    public int maxItemsPerMessage() {
        return maxItemsPerMessage;
    }

    public int maxItemBytes() {
        return maxItemBytes;
    }

    public boolean showAmountSuffix() {
        return showAmountSuffix;
    }

    public boolean debug() {
        return debug;
    }
}