package com.bcshow.showitem.chat;

import com.bcshow.showitem.config.PluginConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.function.Supplier;

/**
 * 聊天捕获监听器：在 {@link EventPriority#LOWEST} 阶段介入，把玩家消息中的
 * 触发词展开为携带物品数据的 token，再交给后续插件（VentureChat 在 HIGHEST 接管）。
 *
 * <p>为什么在 LOWEST：VentureChat 在 HIGHEST 优先级 {@code setCancelled(true)} 并接管
 * 全部渲染与发包。我们必须早于它把 token 写进 {@code event.getMessage()}，token 作为
 * 普通文本被 VC 原样格式化、序列化进聊天 JSON，随后由 ProtocolLib 出站拦截还原为 hover。</p>
 */
public final class ChatCaptureListener implements Listener {

    private final Supplier<PluginConfig> configSupplier;

    public ChatCaptureListener(final Supplier<PluginConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(final AsyncPlayerChatEvent event) {
        if (!event.getPlayer().hasPermission("bcshowitem.use")) {
            return;
        }
        final PluginConfig config = configSupplier.get();
        final TriggerExpander expander = new TriggerExpander(config);
        final TriggerExpander.Result result = expander.expand(event.getPlayer(), event.getMessage());
        if (result.itemCount() > 0 || !result.message().equals(event.getMessage())) {
            event.setMessage(result.message());
        }
    }
}