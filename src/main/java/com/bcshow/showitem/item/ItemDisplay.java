package com.bcshow.showitem.item;

import com.bcshow.showitem.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 物品显示文本的单一生成源。发送端（生成可见回退纯文本）与渲染端（生成彩色组件）
 * 都调用它，避免 {@code display-format} 逻辑在两处重复实现而产生分叉。
 */
public final class ItemDisplay {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String NAME_PLACEHOLDER = "{name}";

    private ItemDisplay() {
    }

    /**
     * 按 {@code display-format} 生成物品的展示「组件」（供渲染端使用）。
     *
     * <p>关键点：物品名直接取 {@link ItemStack#effectiveName()}，它对原版物品是
     * <b>可翻译组件</b>（翻译键 {@code item.minecraft.xxx}）。保留该组件、随聊天包以 JSON
     * 下发，客户端才会按玩家语言本地化显示（中文客户端 → 中文物品名）。若像纯文本回退那样
     * 提前用 {@link PlainTextComponentSerializer} 拍平，就会锁死成英文。</p>
     *
     * <p>{@code {name}} 处会插入名字组件，并继承格式串在该位置的激活颜色/样式；
     * {@code {amount}}/{@code {type}} 为纯文本，直接替换。</p>
     */
    public static Component formatComponent(final ItemStack item, final PluginConfig config) {
        final String format = config.displayFormat()
                .replace("{amount}", String.valueOf(item.getAmount()))
                .replace("{type}", item.getType().getKey().getKey());

        final int idx = format.indexOf(NAME_PLACEHOLDER);
        if (idx < 0) {
            return LEGACY.deserialize(format);
        }
        final String before = format.substring(0, idx);
        final String after = format.substring(idx + NAME_PLACEHOLDER.length());

        Component name = item.effectiveName();
        if (config.showAmountSuffix() && item.getAmount() > 1) {
            name = Component.empty().append(name).append(Component.text(" x" + item.getAmount()));
        }
        // 让物品名继承 {name} 处的激活颜色/样式（仅在名字本身未显式设置时填充）
        name = applyIfAbsent(name, activeStyleAt(before));

        return Component.empty()
                .append(LEGACY.deserialize(before))
                .append(name)
                .append(LEGACY.deserialize(after));
    }

    /**
     * 生成「物品名 + lore（介绍）」的悬停文本组件，供 SHOW_TEXT hover 使用。
     *
     * <p>相比 SHOW_ITEM 携带完整 NBT，此处仅取名字与 lore 文本，数据量小得多，
     * 大 NBT 物品也能稳定携带而不触发体积降级。名字与 lore 均保留可翻译组件，
     * 跨服仍按客户端语言本地化。</p>
     *
     * <p>首行为物品名（继承其原有稀有度颜色），其后逐行追加 lore。</p>
     */
    public static Component hoverText(final ItemStack item, final PluginConfig config) {
        Component name = item.effectiveName();
        if (config.showAmountSuffix() && item.getAmount() > 1) {
            name = Component.empty().append(name).append(Component.text(" x" + item.getAmount()));
        }
        Component hover = name;
        final List<Component> lore = item.lore();
        if (lore != null) {
            for (final Component line : lore) {
                hover = hover.append(Component.newline()).append(line);
            }
        }
        return hover;
    }

    /**
     * 扫描格式串（legacy {@code &} 码），计算末尾处的激活颜色与装饰。
     * 遵循原版语义：颜色码会重置已有装饰，{@code &r} 重置全部。
     */
    private static Style activeStyleAt(final String s) {
        TextColor color = null;
        final Set<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '&' || i + 1 >= s.length()) {
                continue;
            }
            final char code = s.charAt(i + 1);
            if (code == '#' && i + 7 < s.length()) {
                color = TextColor.fromHexString("#" + s.substring(i + 2, i + 8));
                decorations.clear();
                i += 7;
            } else if (isColorCode(code)) {
                color = colorOf(code);
                decorations.clear();
                i += 1;
            } else if (code == 'r' || code == 'R') {
                color = null;
                decorations.clear();
                i += 1;
            } else {
                final TextDecoration deco = decorationOf(code);
                if (deco != null) {
                    decorations.add(deco);
                }
                i += 1;
            }
        }
        Style.Builder style = Style.style();
        if (color != null) {
            style = style.color(color);
        }
        for (final TextDecoration deco : decorations) {
            style = style.decoration(deco, true);
        }
        return style.build();
    }

    /** 仅在物品名未显式设置该属性时才填充（保留自定义命名物品自身的颜色/斜体）。 */
    private static Component applyIfAbsent(final Component name, final Style style) {
        Component out = name;
        if (style.color() != null) {
            out = out.colorIfAbsent(style.color());
        }
        for (final TextDecoration deco : TextDecoration.values()) {
            if (style.decoration(deco) == TextDecoration.State.TRUE) {
                out = out.decorationIfAbsent(deco, TextDecoration.State.TRUE);
            }
        }
        return out;
    }

    private static boolean isColorCode(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static TextColor colorOf(final char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private static TextDecoration decorationOf(final char code) {
        return switch (Character.toLowerCase(code)) {
            case 'k' -> TextDecoration.OBFUSCATED;
            case 'l' -> TextDecoration.BOLD;
            case 'm' -> TextDecoration.STRIKETHROUGH;
            case 'n' -> TextDecoration.UNDERLINED;
            case 'o' -> TextDecoration.ITALIC;
            default -> null;
        };
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