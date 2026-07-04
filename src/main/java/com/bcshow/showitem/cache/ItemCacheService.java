package com.bcshow.showitem.cache;

import com.bcshow.showitem.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * 跨服物品缓存服务：把「大物品的完整 NBT」从聊天消息里搬出来，改走独立通道，
 * 消息里只留一个 8 字节内容哈希 id。这样消息体积恒定极小、永不撑爆数据包，
 * 物品再大也能在接收端还原完整 tooltip。
 *
 * <pre>
 *   发送端 store(player, bytes):
 *     id = hash(bytes)            内容寻址：同物品同 id，天然去重
 *     本地缓存 put(id, bytes)      发送者自己的服永远命中
 *     Forward 广播 (id, bytes)     经 Velocity bungee 兼容层转发给其余子服
 *   接收端 lookup(id):
 *     命中 → 还原完整 hover
 *     未命中 → 交由调用方降级为名字（自愈：同物品下次即命中）
 * </pre>
 *
 * <p>传输走传统 {@code BungeeCord} 插件消息通道的 {@code Forward}/{@code ALL} 子命令。
 * Velocity 默认开启 {@code bungee-plugin-message-channel}，代理端无需安装任何插件。
 * 广播必须借一个在线玩家的连接发出，且必须在主线程调用，故异步聊天线程里会调度回主线程。</p>
 */
public final class ItemCacheService implements PluginMessageListener {

    /** 传统 BungeeCord 兼容通道名（Velocity 内置转发）。 */
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    /** 自定义 Forward 子频道名，接收端据此过滤本插件的广播。 */
    private static final String SUB_CHANNEL = "BcShowItem";
    /** Forward 单条数据的硬上限（writeShort 长度字段）。超过则跳过广播，仅保留本地缓存。 */
    private static final int FORWARD_MAX = 32000;

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final Map<Long, CacheEntry> store;

    private record CacheEntry(byte[] bytes, long expireAtMillis) {
    }

    public ItemCacheService(final Plugin plugin, final Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.config = config;
        // 访问序 LinkedHashMap + removeEldestEntry = LRU；容量按当前配置动态判定。
        this.store = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Long, CacheEntry> eldest) {
                return size() > Math.max(64, config.get().cacheMaxEntries());
            }
        });
    }

    /** 注册收发通道。 */
    public void register() {
        final var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
    }

    /** 注销收发通道。 */
    public void unregister() {
        final var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        messenger.unregisterIncomingPluginChannel(plugin, BUNGEE_CHANNEL);
    }

    /** 内容寻址哈希：SHA-256 取前 8 字节折成 long（64 位，聊天缓存规模下碰撞可忽略）。 */
    public static long hash(final byte[] itemBytes) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(itemBytes);
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (h[i] & 0xFF);
            }
            return v;
        } catch (final Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * 发送端登记物品：本地缓存 + 广播给其余子服。
     *
     * @param broadcaster 借其连接发广播的玩家（通常即发消息者），可为 null 则另找在线玩家
     * @param itemBytes   {@code ItemStack#serializeAsBytes()} 的原始字节
     * @return 该物品的内容哈希 id，写入 token 供接收端回查
     */
    public long store(final Player broadcaster, final byte[] itemBytes) {
        final long id = hash(itemBytes);
        putLocal(id, itemBytes);
        broadcast(broadcaster, id, itemBytes);
        return id;
    }

    /** 接收端回查物品字节。未命中或已过期返回空。 */
    public Optional<byte[]> lookup(final long id) {
        final CacheEntry e = store.get(id);
        if (e == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > e.expireAtMillis()) {
            store.remove(id);
            return Optional.empty();
        }
        return Optional.of(e.bytes());
    }

    private void putLocal(final long id, final byte[] bytes) {
        final long ttl = Math.max(1, config.get().cacheTtlSeconds()) * 1000L;
        store.put(id, new CacheEntry(bytes, System.currentTimeMillis() + ttl));
    }

    /**
     * 经 BungeeCord Forward/ALL 把 (id, bytes) 广播给其余子服。
     *
     * <p>外层是 bungee 兼容协议帧，内层是本插件自定义帧：
     * <pre>
     *   外层: UTF("Forward") + UTF("ALL") + UTF(subChannel="BcShowItem")
     *         + short(payloadLen) + payload
     *   内层 payload: long(id) + int(bytesLen) + bytes
     * </pre>
     * 单服（无代理）时无在线目标，Forward 静默丢弃，本地缓存已足够。</p>
     */
    private void broadcast(final Player preferred, final long id, final byte[] itemBytes) {
        final byte[] inner = buildInner(id, itemBytes);
        if (inner.length > FORWARD_MAX) {
            return; // 超帧上限：放弃广播，接收端将降级为名字（本服仍命中）
        }
        // Forward 必须借在线玩家连接、且在主线程发送
        Bukkit.getScheduler().runTask(plugin, () -> {
            final Player carrier = pickCarrier(preferred);
            if (carrier == null) {
                return;
            }
            try {
                final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                final DataOutputStream out = new DataOutputStream(buf);
                out.writeUTF("Forward");
                out.writeUTF("ALL");
                out.writeUTF(SUB_CHANNEL);
                out.writeShort(inner.length);
                out.write(inner);
                carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, buf.toByteArray());
            } catch (final Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[BcShowItem] 广播物品缓存失败", ex);
            }
        });
    }

    private static byte[] buildInner(final long id, final byte[] itemBytes) {
        try {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream(itemBytes.length + 16);
            final DataOutputStream out = new DataOutputStream(buf);
            out.writeLong(id);
            out.writeInt(itemBytes.length);
            out.write(itemBytes);
            return buf.toByteArray();
        } catch (final Exception ex) {
            return new byte[0];
        }
    }

    private Player pickCarrier(final Player preferred) {
        if (preferred != null && preferred.isOnline()) {
            return preferred;
        }
        return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
    }

    /**
     * 接收其他子服经 Forward 广播来的物品缓存。只认子频道 {@code BcShowItem} 的帧，
     * 解析出 (id, bytes) 存入本地缓存，供本服玩家看到该消息时还原 hover。
     */
    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!BUNGEE_CHANNEL.equals(channel)) {
            return;
        }
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            if (!SUB_CHANNEL.equals(in.readUTF())) {
                return; // 非本插件的 Forward 帧
            }
            final short len = in.readShort();
            final byte[] inner = new byte[len];
            in.readFully(inner);

            final DataInputStream innerIn = new DataInputStream(new ByteArrayInputStream(inner));
            final long id = innerIn.readLong();
            final int bytesLen = innerIn.readInt();
            if (bytesLen < 0 || bytesLen > FORWARD_MAX) {
                return;
            }
            final byte[] itemBytes = new byte[bytesLen];
            innerIn.readFully(itemBytes);
            putLocal(id, itemBytes);
        } catch (final Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[BcShowItem] 接收物品缓存失败", ex);
        }
    }
}