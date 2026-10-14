package io.github.eduardo.dbeaver.aichat.core.export;

final class Xml {

    private Xml() {
    }

    /** Escapes markup and drops characters XML 1.0 does not allow (control characters, lone surrogates). */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                        sb.append(c).append(text.charAt(++i));
                    } else if (c == '\t' || c == '\n' || c == '\r'
                            || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
