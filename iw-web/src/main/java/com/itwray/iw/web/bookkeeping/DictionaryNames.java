package com.itwray.iw.web.bookkeeping;

public final class DictionaryNames {
    private DictionaryNames() { }
    public static boolean isBookkeeping(Integer type) {
        return Integer.valueOf(4002).equals(type) || Integer.valueOf(4001).equals(type)
                || Integer.valueOf(4011).equals(type);
    }
    public static String normalize(String name) {
        if (name == null) throw new IllegalArgumentException("名称不能为空");
        int start = 0, end = name.length();
        while (start < end && space(name.codePointAt(start))) start += Character.charCount(name.codePointAt(start));
        while (end > start && space(name.codePointBefore(end))) end -= Character.charCount(name.codePointBefore(end));
        String value = name.substring(start, end);
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 32) throw new IllegalArgumentException("名称须为1至32个字符");
        return value;
    }
    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
    }
}
