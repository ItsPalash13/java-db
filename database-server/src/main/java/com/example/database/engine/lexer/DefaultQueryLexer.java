package com.example.database.engine.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scans a query into {@link Token}s. On failure throws {@link LexException} with the exact index.
 */
public final class DefaultQueryLexer implements QueryLexer {

    private static final Map<String, TokenCatalog> KEYWORDS = Map.ofEntries(
            Map.entry("CREATE", TokenCatalog.CREATE),
            Map.entry("ALTER", TokenCatalog.ALTER),
            Map.entry("DROP", TokenCatalog.DROP),
            Map.entry("DATABASE", TokenCatalog.DATABASE),
            Map.entry("DB", TokenCatalog.DATABASE),
            Map.entry("TABLE", TokenCatalog.TABLE),
            Map.entry("INDEX", TokenCatalog.INDEX),
            Map.entry("COLUMN", TokenCatalog.COLUMN),
            Map.entry("ADD", TokenCatalog.ADD),
            Map.entry("ON", TokenCatalog.ON),
            Map.entry("SELECT", TokenCatalog.SELECT),
            Map.entry("UPDATE", TokenCatalog.UPDATE),
            Map.entry("DELETE", TokenCatalog.DELETE),
            Map.entry("INSERT", TokenCatalog.INSERT),
            Map.entry("INTO", TokenCatalog.INTO),
            Map.entry("FROM", TokenCatalog.FROM),
            Map.entry("SET", TokenCatalog.SET),
            Map.entry("VALUES", TokenCatalog.VALUES),
            Map.entry("WHERE", TokenCatalog.WHERE),
            Map.entry("TRUE", TokenCatalog.BOOLEAN),
            Map.entry("FALSE", TokenCatalog.BOOLEAN)
    );

    @Override
    public List<Token> tokenize(String query) {
        if (query == null) {
            throw new LexException(0, "query is null");
        }

        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = query.length();

        while (i < n) {
            char c = query.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (i + 1 < n) {
                char next = query.charAt(i + 1);
                if (c == '>' && next == '=') {
                    tokens.add(new Token(TokenCatalog.GTE, ">=", i));
                    i += 2;
                    continue;
                }
                if (c == '<' && next == '=') {
                    tokens.add(new Token(TokenCatalog.LTE, "<=", i));
                    i += 2;
                    continue;
                }
                if (c == '!' && next == '=') {
                    tokens.add(new Token(TokenCatalog.NEQ, "!=", i));
                    i += 2;
                    continue;
                }
                if (c == '<' && next == '>') {
                    tokens.add(new Token(TokenCatalog.NEQ, "<>", i));
                    i += 2;
                    continue;
                }
            }

            TokenCatalog single = singleChar(c);
            if (single != null) {
                tokens.add(new Token(single, String.valueOf(c), i));
                i++;
                continue;
            }

            if (c == '\'' || c == '"') {
                i = readString(query, i, c, tokens);
                continue;
            }

            if (Character.isDigit(c)) {
                i = readNumber(query, i, tokens);
                continue;
            }

            if (isIdentStart(c)) {
                i = readWord(query, i, tokens);
                continue;
            }

            throw new LexException(i, "unexpected character '" + c + "'");
        }

        tokens.add(new Token(TokenCatalog.EOF, "", n));
        return tokens;
    }

    private static TokenCatalog singleChar(char c) {
        return switch (c) {
            case '*' -> TokenCatalog.STAR;
            case '>' -> TokenCatalog.GT;
            case '<' -> TokenCatalog.LT;
            case '=' -> TokenCatalog.EQ;
            case '/' -> TokenCatalog.SLASH;
            case '+' -> TokenCatalog.PLUS;
            case '-' -> TokenCatalog.MINUS;
            case ',' -> TokenCatalog.COMMA;
            case '(' -> TokenCatalog.LPAREN;
            case ')' -> TokenCatalog.RPAREN;
            case ';' -> TokenCatalog.SEMICOLON;
            default -> null;
        };
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Reads a quoted string; lexeme is the inner text without quotes. Returns next index. */
    private static int readString(String query, int start, char quote, List<Token> tokens) {
        int i = start + 1;
        int n = query.length();
        StringBuilder value = new StringBuilder();
        while (i < n) {
            char c = query.charAt(i);
            if (c == quote) {
                tokens.add(new Token(TokenCatalog.STRING, value.toString(), start));
                return i + 1;
            }
            value.append(c);
            i++;
        }
        throw new LexException(start, "unclosed string literal");
    }

    private static int readNumber(String query, int start, List<Token> tokens) {
        int i = start;
        int n = query.length();
        boolean seenDot = false;
        while (i < n) {
            char c = query.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !seenDot) {
                seenDot = true;
                i++;
            } else {
                break;
            }
        }
        if (i < n && isIdentStart(query.charAt(i))) {
            throw new LexException(start, "invalid number near '" + query.substring(start, Math.min(n, i + 1)) + "'");
        }
        String lexeme = query.substring(start, i);
        if (lexeme.endsWith(".")) {
            throw new LexException(start, "invalid number '" + lexeme + "'");
        }
        tokens.add(new Token(TokenCatalog.NUMBER, lexeme, start));
        return i;
    }

    private static int readWord(String query, int start, List<Token> tokens) {
        int i = start;
        int n = query.length();
        while (i < n && isIdentPart(query.charAt(i))) {
            i++;
        }
        String lexeme = query.substring(start, i);
        String upper = lexeme.toUpperCase(Locale.ROOT);
        TokenCatalog kind = KEYWORDS.getOrDefault(upper, TokenCatalog.IDENTIFIER);
        String stored;
        if (kind == TokenCatalog.IDENTIFIER) {
            stored = lexeme;
        } else if (kind == TokenCatalog.BOOLEAN) {
            stored = upper.equals("TRUE") ? "true" : "false";
        } else if (kind == TokenCatalog.DATABASE) {
            stored = "DATABASE";
        } else {
            stored = upper;
        }
        tokens.add(new Token(kind, stored, start));
        return i;
    }
}
