package com.bcshow.showitem.token;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
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

    private ItemTokenCodec() {
    }

    /**
     * 组装 token。
     *
     * @param visibleFallback 未装插件方可见的纯文本回退（如 {@code [钻石剑]}），不应含哨兵字符
     * @param item            要嵌入的物品
     * @return token 字符串
     */
    public static String encode(final String visibleFallback, final ItemStack item) {
        final String zw = ZeroWidthCodec.encode(deflate(item.serializeAsBytes()));
        return SENTINEL_START + visibleFallback + SENTINEL_MID + zw + SENTINEL_END;
    }

    /**
     * 估算某物品编入 token 后在网络线路上占用的字节数（用于体积上限判断）。
     *
     * <p>膨胀链：压缩后字节数 {@code C} → 零宽字符 {@code 4C}（每字节 4 字符）→ UTF-8
     * 线路字节 {@code 12C}（每个零宽码点 3 字节）。哨兵与可见回退名相对极小，此处忽略。</p>
     */
    public static int estimateWireBytes(final ItemStack item) {
        return deflate(item.serializeAsBytes()).length * 12;
    }

    /**
     * 把 token 的零宽数据段还原为物品。
     *
     * @param zwPayload {@link #TOKEN_PATTERN} 捕获组 2
     * @return 还原成功返回物品，数据损坏返回空
     */
    public static Optional<ItemStack> decode(final String zwPayload) {
        try {
            final byte[] compressed = ZeroWidthCodec.decode(zwPayload);
            if (compressed == null) {
                return Optional.empty();
            }
            final byte[] bytes = inflate(compressed);
            if (bytes == null) {
                return Optional.empty();
            }
            return Optional.of(ItemStack.deserializeBytes(bytes));
        } catch (final Exception ex) {
            return Optional.empty();
        }
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