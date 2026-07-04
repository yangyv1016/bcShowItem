package com.bcshow.showitem.render;

import com.bcshow.showitem.token.ItemTokenCodec;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * 出站聊天包拦截器（全流程的还原端）。
 *
 * <p>VentureChat 在 Paper 1.20.4+ 用 ProtocolLib 发送 {@code SYSTEM_CHAT} 包，
 * 其聊天组件以 JSON 形式写在 chatComponents[0]。本适配器拦截同一个包：
 * <pre>
 *   出站 SYSTEM_CHAT
 *     → 读 chatComponents[0] 的 JSON
 *     → GsonComponentSerializer 反序列化为 Adventure Component
 *     → ItemHoverRenderer 递归替换 token 为物品 hover 组件
 *     → 回写 JSON
 * </pre>
 * 仅当 JSON 中含起始哨兵时才做完整解析，其余包零开销放行。</p>
 */
public final class ChatPacketInjector extends PacketAdapter {

    private final ItemHoverRenderer renderer;
    private final GsonComponentSerializer gson = GsonComponentSerializer.gson();

    private ChatPacketInjector(final Plugin plugin, final ItemHoverRenderer renderer) {
        super(plugin, ListenerPriority.HIGH,
                PacketType.Play.Server.SYSTEM_CHAT,
                PacketType.Play.Server.CHAT);
        this.renderer = renderer;
    }

    /**
     * 注册拦截器到 ProtocolLib。
     *
     * @param plugin   本插件实例
     * @param renderer token 还原器
     * @return 已注册的实例
     */
    public static ChatPacketInjector register(final Plugin plugin, final ItemHoverRenderer renderer) {
        final ChatPacketInjector injector = new ChatPacketInjector(plugin, renderer);
        ProtocolLibrary.getProtocolManager().addPacketListener(injector);
        return injector;
    }

    /** 从 ProtocolLib 注销。 */
    public void unregister() {
        final ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        if (pm != null) {
            pm.removePacketListener(this);
        }
    }

    @Override
    public void onPacketSending(final PacketEvent event) {
        try {
            processComponentSlot(event.getPacket());
        } catch (final Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[BcShowItem] Failed to process outbound chat packet", ex);
        }
    }

    /**
     * 处理包中的聊天组件槽位。SYSTEM_CHAT / CHAT 在 1.20.4+ 均把组件写在 chatComponents[0]。
     */
    private void processComponentSlot(final PacketContainer packet) {
        if (packet.getChatComponents().size() == 0) {
            return;
        }
        final WrappedChatComponent wrapped = packet.getChatComponents().readSafely(0);
        if (wrapped == null) {
            return;
        }
        final String json = wrapped.getJson();
        if (json == null || !ItemTokenCodec.mayContainToken(json)) {
            return;
        }
        final Component source = gson.deserialize(json);
        final Component rendered = renderer.render(source);
        packet.getChatComponents().write(0, WrappedChatComponent.fromJson(gson.serialize(rendered)));
    }
}