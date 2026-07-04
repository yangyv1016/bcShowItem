package com.bcshow.showitem.chat;

import com.bcshow.showitem.config.PluginConfig;
import com.bcshow.showitem.item.ItemDisplay;
import com.bcshow.showitem.item.SlotSelector;
import com.bcshow.showitem.token.ItemTokenCodec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * 触发符展开器：把玩家消息中的触发符替换为携带物品数据的 token。纯函数式、无副作用。
 *
 * <p>触发符模型 = {@code prefix + selector (+ suffix)}：
 * <pre>
 *   prefix="%" suffix=""   ->  %i  %3  %i:head
 *   prefix="[" suffix="]"  ->  [i] [3] [head]
 * </pre>
 * selector 解析交给 {@link SlotSelector}。命中后：
 * <ul>
 *   <li>有物品 -> 输出 token（可见回退名 + 零宽物品数据）</li>
 *   <li>空槽位 -> 输出配置的占位文本</li>
 *   <li>无法识别 selector -> 原样保留，视作普通文本</li>
 * </ul></p>
 */
public final class TriggerExpander {

    private final PluginConfig config;

    public TriggerExpander(final PluginConfig config) {
        this.config = config;
    }

    /** 展开结果：替换后的文本 + 实际生成的 token 数量。 */
    public record Result(String message, int itemCount) {
    }

    public Result expand(final Player player, final String message) {
        final String prefix = config.triggerPrefix();
        if (prefix.isEmpty() || message.indexOf(prefix) < 0) {
            return new Result(message, 0);
        }
        final String suffix = config.triggerSuffix();

        final StringBuilder out = new StringBuilder(message.length() + 64);
        int cursor = 0;
        int produced = 0;
        int wireUsed = 0;

        while (cursor < message.length()) {
            final int hit = message.indexOf(prefix, cursor);
            if (hit < 0) {
                out.append(message, cursor, message.length());
                break;
            }
            out.append(message, cursor, hit);
            final int selStart = hit + prefix.length();

            // 提取 selector 段与整个触发符的结束位置
            final ParsedTrigger parsed = parseSelector(message, selStart, suffix);
            if (parsed == null) {
                // 非合法触发符：原样保留 prefix，继续扫描其后
                out.append(prefix);
                cursor = selStart;
                continue;
            }

            final Optional<SlotSelector> selector = SlotSelector.parse(parsed.selector());
            final boolean hotbarSelector = parsed.selector().length() == 1
                    && parsed.selector().charAt(0) >= '1' && parsed.selector().charAt(0) <= '9';
            if (selector.isEmpty() || (hotbarSelector && !config.hotbarEnabled())
                    || (!parsed.selector().equalsIgnoreCase("i") && !hotbarSelector && !config.slotSyntaxEnabled())) {
                // selector 不合法或被配置关闭：原样保留整段
                out.append(message, hit, parsed.end());
                cursor = parsed.end();
                continue;
            }

            if (produced >= config.maxItemsPerMessage()) {
                out.append(message, hit, parsed.end());
                cursor = parsed.end();
                continue;
            }

            final String rawTrigger = message.substring(hit, parsed.end());
            final Rendered rendered = renderSlot(player, selector.get(), rawTrigger,
                    config.maxMessageWireBytes() - wireUsed);
            out.append(rendered.text());
            wireUsed += rendered.wireBytes();
            if (rendered.embedded()) {
                produced++;
            }
            cursor = parsed.end();
        }

        return new Result(out.toString(), produced);
    }

    /** 解析结果：selector 内容 + 触发符在原串中的结束下标（exclusive）。 */
    private record ParsedTrigger(String selector, int end) {
    }

    /**
     * 从 prefix 之后解析 selector。
     *
     * <p>有 suffix：读到下一个 suffix 之间的内容作为 selector。
     * 无 suffix：贪婪匹配 {@code i} / 单个数字 / {@code i:关键字} 这一小段。</p>
     *
     * @return 解析失败返回 null
     */
    private ParsedTrigger parseSelector(final String msg, final int from, final String suffix) {
        if (from > msg.length()) {
            return null;
        }
        if (!suffix.isEmpty()) {
            final int close = msg.indexOf(suffix, from);
            if (close < 0) {
                return null;
            }
            final String sel = msg.substring(from, close);
            if (sel.isEmpty()) {
                return null;
            }
            return new ParsedTrigger(sel, close + suffix.length());
        }
        // 无 suffix：先看单数字
        if (from < msg.length()) {
            final char c = msg.charAt(from);
            if (c >= '1' && c <= '9') {
                return new ParsedTrigger(String.valueOf(c), from + 1);
            }
            if (c == 'i' || c == 'I') {
                // 支持 i:关键字
                int end = from + 1;
                if (end < msg.length() && msg.charAt(end) == ':') {
                    final int kwStart = end + 1;
                    int kwEnd = kwStart;
                    while (kwEnd < msg.length() && isKeywordChar(msg.charAt(kwEnd))) {
                        kwEnd++;
                    }
                    if (kwEnd > kwStart) {
                        return new ParsedTrigger(msg.substring(kwStart, kwEnd), kwEnd);
                    }
                }
                return new ParsedTrigger("i", from + 1);
            }
        }
        return null;
    }

    private Rendered renderSlot(final Player player, final SlotSelector slot, final String rawTrigger,
                                final int remainingWireBudget) {
        final ItemStack item = slot.read(player);
        if (isEmpty(item)) {
            // 空槽位策略：keep=原样保留触发符文本，text=占位文本，remove=删除
            final String text = switch (config.emptySlotAction()) {
                case TEXT -> config.emptySlotText();
                case REMOVE -> "";
                case KEEP -> rawTrigger;
            };
            return new Rendered(text, 0, false);
        }
        final int wire = ItemTokenCodec.estimateWireBytes(item);
        final String fallback = ItemDisplay.plainFallback(item, config);
        // 超单物品上限，或超出本条消息剩余总预算：降级为纯文本（无 hover），
        // 但绝不产生会撑爆 32767 字节包上限的零宽数据。
        if (wire > config.maxItemWireBytes() || wire > remainingWireBudget) {
            return new Rendered(fallback, 0, false);
        }
        return new Rendered(ItemTokenCodec.encode(fallback, item), wire, true);
    }

    /**
     * 单个 selector 的渲染结果。
     *
     * @param text      写入消息的文本（token 或纯文本回退）
     * @param wireBytes 本次嵌入占用的线路字节（降级为纯文本时为 0）
     * @param embedded  是否真正嵌入了物品数据（用于 maxItemsPerMessage 计数）
     */
    private record Rendered(String text, int wireBytes, boolean embedded) {
    }

    private static boolean isEmpty(final ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static boolean isKeywordChar(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '\u4e00' && c <= '\u9fff');
    }
}