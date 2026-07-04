package com.bcshow.showitem.render;

import com.bcshow.showitem.cache.ItemCacheService;
import com.bcshow.showitem.config.PluginConfig;
import com.bcshow.showitem.item.ItemDisplay;
import com.bcshow.showitem.token.ItemTokenCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 把「聊天组件树中的 token」还原为「带物品悬停界面的 [物品名] 组件」。
 *
 * <p>核心是 Adventure 的 {@link Component#replaceText}：递归遍历整棵组件树，对匹配
 * {@link ItemTokenCodec#TOKEN_PATTERN} 的片段执行替换。token 的零宽数据段（捕获组 2）
 * 解码为物品，重建彩色 {@code [物品名]} 并附加 {@link ItemStack#asHoverEvent()}，
 * 复用客户端原生物品 tooltip —— 与背包中悬停一致。</p>
 */
public final class ItemHoverRenderer {

    private final Supplier<PluginConfig> configSupplier;
    private final ItemCacheService cache;

    public ItemHoverRenderer(final Supplier<PluginConfig> configSupplier, final ItemCacheService cache) {
        this.configSupplier = configSupplier;
        this.cache = cache;
    }

    public Component render(final Component source) {
        final TextReplacementConfig replacement = TextReplacementConfig.builder()
                .match(ItemTokenCodec.TOKEN_PATTERN)
                // group(1) = 可见回退名（缓存未命中时兜底），group(2) = 零宽数据段
                .replacement((matchResult, builder) ->
                        buildReplacement(matchResult.group(1), matchResult.group(2)))
                .build();
        return source.replaceText(replacement);
    }

    /**
     * 根据 token 构造替换组件。解码失败时回退为空白，避免残留哨兵。
     *
     * <p>按 token 模式分派：
     * <ul>
     *   <li>{@code MODE_TEXT}：payload 已是构建好的最终组件（名字 + showText hover），直接用。</li>
     *   <li>{@code MODE_ITEM}：payload 为物品，重建 [物品名] 并附加原生 SHOW_ITEM hover。</li>
     *   <li>{@code MODE_CACHE_REF}：凭 id 查本地缓存。命中则还原完整 hover；
     *       未命中（数据尚未到达或已过期）降级为可见回退名纯文本，同物品下次即命中。</li>
     * </ul></p>
     *
     * @param visibleFallback token 内的可见回退名，如 {@code [钻石剑]}
     * @param zwPayload       token 的零宽数据段
     */
    private ComponentLike buildReplacement(final String visibleFallback, final String zwPayload) {
        final Optional<ItemTokenCodec.Payload> decoded = ItemTokenCodec.decode(zwPayload);
        if (decoded.isEmpty()) {
            return Component.empty();
        }
        final ItemTokenCodec.Payload payload = decoded.get();
        if (payload.mode() == ItemTokenCodec.MODE_TEXT) {
            return payload.component();
        }

        final PluginConfig config = configSupplier.get();
        if (payload.mode() == ItemTokenCodec.MODE_CACHE_REF) {
            final Optional<byte[]> bytes = cache == null
                    ? Optional.empty() : cache.lookup(payload.cacheId());
            if (bytes.isEmpty()) {
                // 未命中：仅显示名字（无 hover）。不是错误，属可自愈的降级。
                return Component.text(visibleFallback);
            }
            return renderItem(ItemStack.deserializeBytes(bytes.get()), config);
        }

        return renderItem(payload.item(), config);
    }

    /** 重建 [物品名] 组件并附加原生 SHOW_ITEM hover（保留可翻译名，客户端本地化）。 */
    private static ComponentLike renderItem(final ItemStack item, final PluginConfig config) {
        return ItemDisplay.formatComponent(item, config)
                .hoverEvent(item.asHoverEvent());
    }
}