package com.bcshow.showitem.render;

import com.bcshow.showitem.config.PluginConfig;
import com.bcshow.showitem.item.ItemDisplay;
import com.bcshow.showitem.token.ItemTokenCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    public ItemHoverRenderer(final Supplier<PluginConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public Component render(final Component source) {
        final TextReplacementConfig replacement = TextReplacementConfig.builder()
                .match(ItemTokenCodec.TOKEN_PATTERN)
                .replacement((matchResult, builder) -> buildReplacement(matchResult.group(2)))
                .build();
        return source.replaceText(replacement);
    }

    /**
     * 根据 token 的零宽数据段构造替换组件。解码失败时回退为空白，避免残留哨兵。
     */
    private ComponentLike buildReplacement(final String zwPayload) {
        final Optional<ItemStack> decoded = ItemTokenCodec.decode(zwPayload);
        if (decoded.isEmpty()) {
            return Component.empty();
        }
        final ItemStack item = decoded.get();
        final PluginConfig config = configSupplier.get();
        final String formatted = ItemDisplay.format(item, config);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(formatted)
                .hoverEvent(item.asHoverEvent());
    }
}