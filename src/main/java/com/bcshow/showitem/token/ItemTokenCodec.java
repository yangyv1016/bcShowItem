package com.bcshow.showitem.token;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 物品 <-> 聊天 token 的无状态编解码器（隐写版）。
 *
 * <p>token 结构：<pre>
 *   \u2060  [可见回退名]  \u2061  &lt;零宽物品数据&gt;  \u2062
 *   START     visible      MID       zwData          END
 * </pre>
 * 三个哨兵 {@code \u2060 \u2061 \u2062}（WORD JOINER / FUNCTION APPLICATION /
 * INVISIBLE TIMES）与零宽数据在 Minecraft 客户端均不渲染任何宽度。</p>
 *
 * <p>两端行为：
 * <ul>
 *   <li><b>已装本插件的接收服</b>：{@link #TOKEN_PATTERN} 命中整块，解码
 *       {@code zwData} 还原物品，重建彩色 {@code [物品名]} 并附带 hover。</li>
 *   <li><b>未装本插件的接收服</b>：所有哨兵与零宽数据不可见，玩家仅看到可见回退名
 *       {@code [物品名]} 纯文本 —— 优雅降级，不再出现乱码。</li>
 * </ul></p>
 *
 * <p>物品数据随消息文本传输（编码进 token 而非服务端缓存），因此跨服无状态：
 * 每个装了本插件的子服都能独立还原。所有字符均避开 {@code & § # % " \ .}，
 * 可安全穿过 VentureChat 的颜色化 / 过滤 / JSON 转义 / URL 识别管线，且不含
 * {@code §}，不会被 VC 拆成多个 JSON 组件。</p>
 */
public final class ItemTokenCodec {

    public static final char SENTINEL_START = '\u2060';
    public static final char SENTINEL_MID = '\u2061';
    public static final char SENTINEL_END = '\u2062';

    /** group(1) = 可见回退名（丢弃），group(2) = 零宽数据。 */
    public static final Pattern TOKEN_PATTERN = Pattern.compile(
            SENTINEL_START + "(.*?)" + SENTINEL_MID
                    + "([\u200B\u200C\u200D\uFEFF]*)" + SENTINEL_END);

    /** 零宽数据首字节：完整物品 NBT 模式，接收端还原 ItemStack 并用原生 hover。 */
    public static final byte MODE_ITEM = 0x00;
    /** 零宽数据首字节：文本 hover 模式，payload 为已构建组件的 JSON（名字 + lore）。 */
    public static final byte MODE_TEXT = 0x01;
    /**
     * 零宽数据首字节：跨服缓存引用模式。payload 仅为 8 字节内容哈希 id，
     * 物品 NBT 走独立的插件消息通道传输、在接收端本地缓存中回查。消息体积恒定极小、
     * 永不撑爆数据包，大物品也能还原完整 tooltip。见 {@code cache.ItemCacheService}。
     */
    public static final byte MODE_CACHE_REF = 0x02;

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private ItemTokenCodec() {
    }

    /**
     * 解码后的 token 载荷。按 {@code mode} 取对应字段，其余为 null/0：
     * <ul>
     *   <li>{@link #MODE_ITEM}：{@code item} 有效</li>
     *   <li>{@link #MODE_TEXT}：{@code component} 有效</li>
     *   <li>{@link #MODE_CACHE_REF}：{@code cacheId} 有效，需向缓存服务回查物品</li>
     * </ul>
     */
    public record Payload(byte mode, ItemStack item, Component component, long cacheId) {
    }

    /**
     * 编码结果：token 文本 + 其在网络线路上占用的字节数。
     *
     * <p>两者由同一次压缩产出，避免「先估算再编码」重复压缩。wireBytes 用于体积预算判断：
     * 压缩后含模式头共 {@code F} 字节 → 零宽字符 {@code 4F} → UTF-8 线路 {@code 12F} 字节。
     * 哨兵与可见回退名相对极小，此处忽略。</p>
     */
    public record Encoded(String token, int wireBytes) {
    }

    /**
     * 组装「完整物品」模式 token（携带整个 NBT，接收端复用客户端原生 tooltip）。
     *
     * @param visibleFallback 未装插件方可见的纯文本回退（如 {@code [钻石剑]}），不应含哨兵字符
     * @param item            要嵌入的物品
     */
    public static Encoded encodeItem(final String visibleFallback, final ItemStack item) {
        return frame(visibleFallback, MODE_ITEM, deflate(item.serializeAsBytes()));
    }

    /**
     * 组装「文本 hover」模式 token：payload 仅为最终显示组件（含名字 + lore 的 showText hover）
     * 的 JSON。数据量远小于完整 NBT，大 NBT 物品也能稳定携带。
     *
     * @param visibleFallback 未装插件方可见的纯文本回退
     * @param rendered        已构建好的最终组件（[物品名] + showText hover）
     */
    public static Encoded encodeText(final String visibleFallback, final Component rendered) {
        final byte[] json = GSON.serialize(rendered).getBytes(StandardCharsets.UTF_8);
        return frame(visibleFallback, MODE_TEXT, deflate(json));
    }

    /**
     * 组装「跨服缓存引用」模式 token：payload 仅为 8 字节内容哈希 id。物品 NBT 已通过
     * {@code ItemCacheService} 走独立通道传输，接收端凭 id 回查本地缓存还原完整 tooltip。
     *
     * <p>体积恒定：8 字节 id → 零宽 36 字符（含模式头 9 字节 × 4）→ 线路约 108 字节，
     * 与物品大小无关，永不撑爆数据包。id 不压缩（8 字节压缩无收益且可能变大）。</p>
     *
     * @param visibleFallback 未装插件方可见的纯文本回退
     * @param cacheId         {@code ItemCacheService.hash(bytes)} 得到的内容哈希
     */
    public static Encoded encodeCacheRef(final String visibleFallback, final long cacheId) {
        final byte[] id = new byte[8];
        for (int i = 0; i < 8; i++) {
            id[i] = (byte) (cacheId >>> (56 - i * 8));
        }
        return frameRaw(visibleFallback, MODE_CACHE_REF, id);
    }

    /** 把压缩后的 body 加上模式头、零宽编码、包上哨兵，并算出线路字节。 */
    private static Encoded frame(final String visibleFallback, final byte mode, final byte[] compressedBody) {
        return frameRaw(visibleFallback, mode, compressedBody);
    }

    /**
     * 与 {@link #frame} 相同的封帧过程，但 body 已是最终字节（不再压缩）。
     * CACHE_REF 的 8 字节 id 走这里，避免对短数据做无意义的 Deflate。
     */
    private static Encoded frameRaw(final String visibleFallback, final byte mode, final byte[] body) {
        final byte[] framed = withMode(mode, body);
        final String zw = ZeroWidthCodec.encode(framed);
        final String token = SENTINEL_START + visibleFallback + SENTINEL_MID + zw + SENTINEL_END;
        return new Encoded(token, framed.length * 12);
    }

    /**
     * 还原 token 的零宽数据段。按首字节模式分派：
     * item 模式还原 {@link ItemStack}，text 模式还原 {@link Component}。
     *
     * @param zwPayload {@link #TOKEN_PATTERN} 捕获组 2
     * @return 还原成功返回载荷，数据损坏返回空
     */
    public static Optional<Payload> decode(final String zwPayload) {
        try {
            // 与 frame() 严格对称：zerowidth 解出 [mode 字节] ++ 压缩体，
            // 先剥离 mode 字节，再对「其后的压缩体」inflate。mode 字节不参与压缩，
            // 若先 inflate 整段会把 mode 前缀当成 zlib 头的一部分而解压失败。
            final byte[] framed = ZeroWidthCodec.decode(zwPayload);
            if (framed == null || framed.length < 1) {
                return Optional.empty();
            }
            final byte mode = framed[0];
            final byte[] rawBody = new byte[framed.length - 1];
            System.arraycopy(framed, 1, rawBody, 0, rawBody.length);

            // CACHE_REF 的 body 是未压缩的 8 字节 id，先于 inflate 分派。
            if (mode == MODE_CACHE_REF) {
                if (rawBody.length < 8) {
                    return Optional.empty();
                }
                long id = 0;
                for (int i = 0; i < 8; i++) {
                    id = (id << 8) | (rawBody[i] & 0xFF);
                }
                return Optional.of(new Payload(MODE_CACHE_REF, null, null, id));
            }

            final byte[] body = inflate(rawBody);
            if (body == null) {
                return Optional.empty();
            }
            if (mode == MODE_TEXT) {
                final Component c = GSON.deserialize(new String(body, StandardCharsets.UTF_8));
                return Optional.of(new Payload(MODE_TEXT, null, c, 0L));
            }
            return Optional.of(new Payload(MODE_ITEM, ItemStack.deserializeBytes(body), null, 0L));
        } catch (final Exception ex) {
            return Optional.empty();
        }
    }

    /** 在数据前置一个模式标志字节。 */
    private static byte[] withMode(final byte mode, final byte[] body) {
        final byte[] out = new byte[body.length + 1];
        out[0] = mode;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    /**
     * 快速判断文本是否可能含 token（避免对每条消息都跑正则）。
     */
    public static boolean mayContainToken(final String text) {
        return text != null && text.indexOf(SENTINEL_START) >= 0;
    }

    /** 用 Deflate 压缩物品字节（NBT 文本冗余度高，通常可压到 30%~60%）。 */
    private static byte[] deflate(final byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            final byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Deflate 逆操作。数据损坏返回 null。 */
    private static byte[] inflate(final byte[] compressed) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 2));
            final byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                final int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        return null;
                    }
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (final Exception ex) {
            return null;
        } finally {
            inflater.end();
        }
    }
}