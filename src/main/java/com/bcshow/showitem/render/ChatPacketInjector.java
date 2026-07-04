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
import org.bukkit.entity.Player;
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

    /**
     * 递归防护标记。
     *
     * <p>本注入器的还原策略是「取消原包 + 用 Paper 原生 {@code sendMessage} 重发渲染结果」。
     * 但 {@code sendMessage} 会在<b>同一 netty 线程同步</b>产生一个新的 {@code SYSTEM_CHAT}
     * 出站包，再次进入本监听器。渲染结果此时已带 {@code SHOW_ITEM} hover，若对它调用
     * ProtocolLib 的 {@link WrappedChatComponent#getJson()} 去序列化，会踩中 Paper 1.21.11 上
     * 那个未初始化的物品 codec（{@code PARTIAL_RESULT_MESSAGE_ACCESSOR} 为 null）而抛异常刷屏。</p>
     *
     * <p>因 {@code sendMessage} 是同线程同步调用，用 {@link ThreadLocal} 在重发期间置位、
     * 让重入的那一次在读 JSON 之前直接放行即可：渲染结果交由 Paper 原生管线发出，
     * 原生编码器能正确处理 item hover，所有物品都能正常显示。</p>
     */
    private static final ThreadLocal<Boolean> RESENDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
            processComponentSlot(event);
        } catch (final Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[BcShowItem] Failed to process outbound chat packet", ex);
        }
    }

    /**
     * 处理包中的聊天组件槽位。SYSTEM_CHAT / CHAT 在 1.20.4+ 均把组件写在 chatComponents[0]。
     *
     * <p>写回策略：<b>不</b>用 {@code WrappedChatComponent.fromJson} 回写包——ProtocolLib 5.3.0
     * 在 Paper 1.21.11 上该路径的 Mojang codec 反射字段未初始化会 NPE。改为取消原包、用 Paper
     * 原生 Adventure 把渲染后的组件重发给接收者。重发组件已不含 token 哨兵，
     * {@link ItemTokenCodec#mayContainToken} 会放行，不会递归拦截。</p>
     */
    private void processComponentSlot(final PacketEvent event) {
        // 本插件重发渲染结果时同步触发的重入包：直接放行，交给 Paper 原生管线发出，
        // 不去读它的 JSON（避免踩 ProtocolLib 在 1.21.11 上损坏的物品 codec）。
        if (RESENDING.get()) {
            return;
        }
        final PacketContainer packet = event.getPacket();
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
        if (!(event.getPlayer() instanceof Player receiver)) {
            return;
        }

        final Component rendered = renderer.render(gson.deserialize(json));

        // overlay=true 表示 actionbar 文本，不能当聊天发；此处仅接管聊天消息。
        final boolean overlay = packet.getType() == PacketType.Play.Server.SYSTEM_CHAT
                && Boolean.TRUE.equals(packet.getBooleans().readSafely(0));

        event.setCancelled(true);
        RESENDING.set(Boolean.TRUE);
        try {
            if (overlay) {
                receiver.sendActionBar(rendered);
            } else {
                receiver.sendMessage(rendered);
            }
        } finally {
            RESENDING.set(Boolean.FALSE);
        }
    }
}