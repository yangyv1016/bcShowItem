package com.bcshow.showitem.chat;

import com.bcshow.showitem.cache.ItemCacheService;
import com.bcshow.showitem.config.PluginConfig;
import com.bcshow.showitem.item.ItemDisplay;
import com.bcshow.showitem.item.SlotSelector;
import com.bcshow.showitem.render.InventoryMirrorService;
import com.bcshow.showitem.token.ItemTokenCodec;
import net.kyori.adventure.text.Component;
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
    private final ItemCacheService cache;
    private final InventoryMirrorService mirror;

    /**
     * @param config 配置快照
     * @param cache  跨服缓存服务；为 null 时禁用 CACHE 档位（AUTO 链跳过它）
     * @param mirror 背包镜像服务；为 null 时取物直接用背包本体（不含附魔插件注入层）
     */
    public TriggerExpander(final PluginConfig config, final ItemCacheService cache,
                           final InventoryMirrorService mirror) {
        this.config = config;
        this.cache = cache;
        this.mirror = mirror;
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

            final Optional<SlotSelector> selector = SlotSelector.parse(parsed.selector(), this::heldSlot);
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
        final ItemStack item = resolveItem(player, slot);
        if (isEmpty(item)) {
            // 空槽位策略：keep=原样保留触发符文本，text=占位文本，remove=删除
            final String text = switch (config.emptySlotAction()) {
                case TEXT -> config.emptySlotText();
                case REMOVE -> "";
                case KEEP -> rawTrigger;
            };
            return new Rendered(text, 0, false);
        }
        final String fallback = ItemDisplay.plainFallback(item, config);
        final int budget = Math.min(config.maxItemWireBytes(), remainingWireBudget);

        // AUTO 降级链，逐档尝试取第一个装得下预算的：
        //   FULL  档 -> 完整 NBT 内嵌消息，客户端原生 tooltip（信息最全，零竞态，最稳）
        //   CACHE 档 -> 物品走独立通道 + 消息只带 8 字节引用 id，体积恒定不爆，大物品也能完整还原
        //   TEXT  档 -> 仅「物品名 + lore」文本 hover（不依赖缓存通道）
        //   都不行 -> 纯文本 [物品名]（无 hover），绝不撑爆数据包
        final PluginConfig.HoverMode mode = config.hoverMode();

        if (mode != PluginConfig.HoverMode.TEXT) {
            final ItemTokenCodec.Encoded full = ItemTokenCodec.encodeItem(fallback, item);
            if (full.wireBytes() <= budget) {
                return new Rendered(full.token(), full.wireBytes(), true);
            }
            // FULL 强制档：装不下直接降级纯文本，不退到后续档位
            if (mode == PluginConfig.HoverMode.FULL) {
                return new Rendered(fallback, 0, false);
            }
        }

        // CACHE 档（仅 AUTO 且缓存服务可用）：把物品搬到独立通道，消息只带极短引用 id。
        // 引用 token 体积恒定（约 108 线路字节），只要没被 maxItemWireBytes 卡到极低就装得下。
        if (mode == PluginConfig.HoverMode.AUTO && cache != null && config.crossServerCache()) {
            final long id = cache.store(player, item.serializeAsBytes());
            final ItemTokenCodec.Encoded ref = ItemTokenCodec.encodeCacheRef(fallback, id);
            if (ref.wireBytes() <= budget) {
                return new Rendered(ref.token(), ref.wireBytes(), true);
            }
        }

        if (mode != PluginConfig.HoverMode.FULL) {
            final Component rendered = ItemDisplay.formatComponent(item, config)
                    .hoverEvent(ItemDisplay.hoverText(item, config).asHoverEvent());
            final ItemTokenCodec.Encoded text = ItemTokenCodec.encodeText(fallback, rendered);
            if (text.wireBytes() <= budget) {
                return new Rendered(text.token(), text.wireBytes(), true);
            }
        }

        return new Rendered(fallback, 0, false);
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

    /**
     * 取物：真背包是<b>唯一权威内容源</b>，镜像仅作「显示装饰层」叠加。
     *
     * <p><b>为什么不能直接信镜像</b>：镜像来自「服务端→客户端」的 SET_SLOT/WINDOW_ITEMS 出站包，
     * 而 MC 1.17+ 背包点击是「客户端预测 + 服务端仅在预测不符时才纠正」。数字键快速换位等
     * 客户端可自行预测的操作不会触发出站包，镜像收不到更新而陈旧，导致 {@code %2} 展示换位前
     * 的旧物品。真背包 {@code getInventory().getItem()} 在点击包到达主线程时即更新，聊天时读它恒准。</p>
     *
     * <p><b>装饰生效条件</b>：镜像持有「客户端实际收到」的同一物品，含附魔插件在发包时注入、
     * 不落在物品本体 NBT 的显示层（否则附魔变英文、自定义附魔丢失）。仅当镜像该槽与权威物品是
     * <b>同一件物品</b>（类型 + 数量一致，见 {@link #sameItem}）时才采用其富显示版本；标识不符
     * 说明该槽已被换过、镜像已陈旧，回落权威真背包。附魔显示照旧生效，换位后内容恒准。</p>
     */
    private ItemStack resolveItem(final Player player, final SlotSelector slot) {
        final ItemStack base = slot.read(player);
        if (isEmpty(base) || mirror == null) {
            return base;
        }
        final ItemStack seen = mirror.mirrored(player, slot.windowSlot(player));
        return sameItem(seen, base) ? seen : base;
    }

    /**
     * 判定镜像物品与权威物品是否为「同一件物品」，用于决定镜像装饰层是否仍适用于当前槽位占据者。
     *
     * <p>附魔插件注入的只是显示名/lore，不改类型与数量，故用「类型 + 数量」作为轻量标识代理：
     * 一致即认为镜像描述的仍是当前这件物品，其富显示版本可用；不一致（换位/整理/堆叠变动）
     * 则镜像已陈旧。</p>
     */
    private static boolean sameItem(final ItemStack seen, final ItemStack base) {
        return !isEmpty(seen)
                && seen.getType() == base.getType()
                && seen.getAmount() == base.getAmount();
    }

    /**
     * 异步安全的玩家实时手持格号（0..8）。
     *
     * <p>{@code AsyncPlayerChatEvent} 在异步线程触发，而 {@code getHeldItemSlot()} 由主线程
     * 处理换手包时才更新，玩家换格后立刻发言可能读到落后一 tick 的旧格。优先取镜像服务按
     * netty 入站顺序记录的实时格号（与 CHAT 包同序，权威），未记录（如刚进服未换格）或镜像
     * 缺省时回退 {@code getHeldItemSlot()}，行为与旧版一致。</p>
     */
    private int heldSlot(final Player player) {
        if (mirror != null) {
            final Integer live = mirror.heldSlot(player);
            if (live != null) {
                return live;
            }
        }
        return player.getInventory().getHeldItemSlot();
    }

    private static boolean isEmpty(final ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static boolean isKeywordChar(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '\u4e00' && c <= '\u9fff');
    }
}