package com.bcshow.showitem.token;

/**
 * 二进制数据 <-> 零宽字符序列的编解码器（隐写核心）。
 *
 * <p>用 4 个「在 Minecraft 客户端中不渲染任何宽度」的零宽字符表示 2 个比特，
 * 因此每个字节编码为 4 个零宽字符。这些字符不属于 {@code & § # % " \ .} 等
 * 会被 VentureChat 颜色化 / 过滤 / JSON 转义 / URL 识别改写的字符，可安全穿过
 * VC 的文本处理管线。</p>
 *
 * <p>关键收益：未安装本插件的接收服，这些零宽字符不可见，玩家只会看到可见的
 * {@code [物品名]} 回退文本，而不是一串 Base64 乱码。</p>
 */
public final class ZeroWidthCodec {

    /** 2-bit 值 0..3 对应的四个零宽字符。 */
    private static final char[] ALPHABET = {
            '\u200B', // ZERO WIDTH SPACE      -> 00
            '\u200C', // ZERO WIDTH NON-JOINER -> 01
            '\u200D', // ZERO WIDTH JOINER     -> 10
            '\uFEFF'  // ZERO WIDTH NO-BREAK   -> 11
    };

    private ZeroWidthCodec() {
    }

    /**
     * 判断一个字符是否为数据字母表中的零宽字符。
     */
    public static boolean isDataChar(final char c) {
        return c == ALPHABET[0] || c == ALPHABET[1] || c == ALPHABET[2] || c == ALPHABET[3];
    }

    /**
     * 把字节数组编码为零宽字符串（每字节 -> 4 字符）。
     */
    public static String encode(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 4);
        for (final byte b : bytes) {
            final int v = b & 0xFF;
            sb.append(ALPHABET[(v >> 6) & 0x03]);
            sb.append(ALPHABET[(v >> 4) & 0x03]);
            sb.append(ALPHABET[(v >> 2) & 0x03]);
            sb.append(ALPHABET[v & 0x03]);
        }
        return sb.toString();
    }

    /**
     * 把零宽字符串还原为字节数组。长度非 4 的倍数或含非法字符时返回 null。
     */
    public static byte[] decode(final String zw) {
        final int len = zw.length();
        if (len == 0 || (len & 0x03) != 0) {
            return null;
        }
        final byte[] out = new byte[len / 4];
        for (int i = 0; i < out.length; i++) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                final int bits = indexOf(zw.charAt(i * 4 + j));
                if (bits < 0) {
                    return null;
                }
                v = (v << 2) | bits;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    private static int indexOf(final char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}