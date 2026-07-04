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

    /** 悬停内容模式。 */
    public enum HoverMode {
        /**
         * 自动降级链（默认）：完整 NBT 装得下就用；超预算退到「名字 + lore」文本 hover；
         * 仍超预算才退到纯文本。既尽量保留最丰富的悬停，又绝不因体积踢人。
         */
        AUTO,
        /** 强制携带完整物品 NBT，超预算直接降级纯文本（不退到 text 档）。 */
        FULL,
        /** 强制仅携带物品名 + lore 文本的 SHOW_TEXT hover（体积小，适合大 NBT 服）。 */
        TEXT;

        static HoverMode of(final String raw) {
            if (raw == null) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase()) {
                case "full", "item", "nbt" -> FULL;
                case "text", "lore", "name" -> TEXT;
                default -> AUTO;
            };
        }
    }

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
    private final HoverMode hoverMode;
    private final EmptySlotAction emptySlotAction;
    private final String emptySlotText;
    private final int maxItemsPerMessage;
    private final int maxItemWireBytes;
    private final int maxMessageWireBytes;
    private final boolean showAmountSuffix;
    private final boolean crossServerCache;
    private final int cacheMaxEntries;
    private final int cacheTtlSeconds;
    private final boolean debug;

    private PluginConfig(final String triggerPrefix, final String triggerSuffix, final boolean hotbarEnabled,
                         final boolean slotSyntaxEnabled, final String displayFormat, final HoverMode hoverMode,
                         final EmptySlotAction emptySlotAction, final String emptySlotText,
                         final int maxItemsPerMessage, final int maxItemWireBytes, final int maxMessageWireBytes,
                         final boolean showAmountSuffix, final boolean crossServerCache, final int cacheMaxEntries,
                         final int cacheTtlSeconds, final boolean debug) {
        this.triggerPrefix = triggerPrefix;
        this.triggerSuffix = triggerSuffix;
        this.hotbarEnabled = hotbarEnabled;
        this.slotSyntaxEnabled = slotSyntaxEnabled;
        this.displayFormat = displayFormat;
        this.hoverMode = hoverMode;
        this.emptySlotAction = emptySlotAction;
        this.emptySlotText = emptySlotText;
        this.maxItemsPerMessage = maxItemsPerMessage;
        this.maxItemWireBytes = maxItemWireBytes;
        this.maxMessageWireBytes = maxMessageWireBytes;
        this.showAmountSuffix = showAmountSuffix;
        this.crossServerCache = crossServerCache;
        this.cacheMaxEntries = cacheMaxEntries;
        this.cacheTtlSeconds = cacheTtlSeconds;
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
                HoverMode.of(config.getString("hover-mode", "auto")),
                EmptySlotAction.of(config.getString("empty-slot-action", "keep")),
                config.getString("empty-slot-text", "&7[空]"),
                Math.max(1, config.getInt("max-items-per-message", 5)),
                resolveItemWireBytes(config),
                resolveMessageWireBytes(config),
                config.getBoolean("show-amount-suffix", true),
                config.getBoolean("cross-server-cache", true),
                Math.max(64, config.getInt("cache-max-entries", 10000)),
                Math.max(1, config.getInt("cache-ttl-seconds", 3600)),
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

    /**
     * 单个物品的线路字节上限。优先读新键 {@code max-item-wire-bytes}；
     * 未配置时兼容旧键 {@code max-item-bytes}（旧值是「原始字节」语义，
     * 按 12 倍膨胀折算成线路字节，避免沿用旧配置仍踢人）。
     */
    private static int resolveItemWireBytes(final FileConfiguration config) {
        if (config.isInt("max-item-wire-bytes")) {
            return clampWire(config.getInt("max-item-wire-bytes"));
        }
        if (config.isInt("max-item-bytes")) {
            return clampWire(config.getInt("max-item-bytes") * 12);
        }
        return 6000;
    }

    /** 单条消息所有物品合计的线路字节预算。 */
    private static int resolveMessageWireBytes(final FileConfiguration config) {
        return clampWire(config.getInt("max-message-wire-bytes", 20000));
    }

    /**
     * 夹取到安全区间：下限 512（太小则几乎所有物品都降级），
     * 上限 24000（为 VC/Bungee plugin message 头部、其他文本与 §JSON 结构留出余量，
     * 硬上限是 32767）。
     */
    private static int clampWire(final int v) {
        return Math.max(512, Math.min(24000, v));
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

    public HoverMode hoverMode() {
        return hoverMode;
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

    public int maxItemWireBytes() {
        return maxItemWireBytes;
    }

    public int maxMessageWireBytes() {
        return maxMessageWireBytes;
    }

    public boolean showAmountSuffix() {
        return showAmountSuffix;
    }

    /** 是否启用跨服缓存档位（大物品走独立通道传输，消息只带引用 id）。 */
    public boolean crossServerCache() {
        return crossServerCache;
    }

    /** 本地缓存最多保留多少条物品（LRU 淘汰）。 */
    public int cacheMaxEntries() {
        return cacheMaxEntries;
    }

    /** 缓存条目存活秒数，超时后老消息 hover 失效。 */
    public int cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public boolean debug() {
        return debug;
    }
}