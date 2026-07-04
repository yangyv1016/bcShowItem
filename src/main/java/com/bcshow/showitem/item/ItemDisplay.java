package com.bcshow.showitem.item;

import com.bcshow.showitem.config.PluginConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;

/**
 * 物品显示文本的单一生成源。发送端（生成可见回退纯文本）与渲染端（生成彩色组件）
 * 都调用它，避免 {@code display-format} 逻辑在两处重复实现而产生分叉。
 */
public final class ItemDisplay {

    private ItemDisplay() {
    }

    /**
     * 按 {@code display-format} 生成物品的展示字符串（含 & 颜色码，未反序列化）。
     *
     * @param item   物品
     * @param config 配置快照
     * @return 形如 {@code &b[钻石剑 x64]&r} 的字符串
     */
    public static String format(final ItemStack item, final PluginConfig config) {
        final String name = PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
        final String amountSuffix = config.showAmountSuffix() && item.getAmount() > 1
                ? " x" + item.getAmount() : "";
        return config.displayFormat()
                .replace("{name}", name + amountSuffix)
                .replace("{amount}", String.valueOf(item.getAmount()))
                .replace("{type}", item.getType().getKey().getKey());
    }

    /**
     * 生成「未装插件方可见的纯文本回退」：去掉颜色码后的纯文本。
     *
     * @param item   物品
     * @param config 配置快照
     * @return 形如 {@code [钻石剑 x64]} 的纯文本，不含任何颜色码与哨兵字符
     */
    public static String plainFallback(final ItemStack item, final PluginConfig config) {
        final String formatted = format(item, config);
        return stripAmpersandCodes(formatted);
    }

    /**
     * 去除 &x 形式的颜色/格式码（含十六进制 &#RRGGBB）。仅用于生成纯文本回退。
     */
    private static String stripAmpersandCodes(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                final char next = s.charAt(i + 1);
                if (next == '#' && i + 7 < s.length()) {
                    i += 7; // 跳过 &#RRGGBB
                    continue;
                }
                if (isCodeChar(next)) {
                    i += 1; // 跳过 &x
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isCodeChar(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || "klmnorxKLMNORX".indexOf(c) >= 0;
    }
}