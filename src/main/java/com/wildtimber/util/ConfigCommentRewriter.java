package com.wildtimber.util;

import com.wildtimber.WildTimber;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

/**
 * Rewrites config.yml with language-specific comments while preserving user values.
 * <p>
 * Algorithm:
 * 1. Load the comment template lang/config_<lang>.yml from the JAR (default values + translated comments)
 * 2. Load the current config.yml values via Bukkit API
 * 3. Walk the template line by line, tracking the YAML path via indent depth
 * 4. For each scalar key, substitute the user's current value
 * 5. For list keys, inject the user's list items
 * 6. Write the merged result back to config.yml
 */
public class ConfigCommentRewriter {

    /**
     * Rewrites config.yml with comments for the given language code.
     *
     * @param plugin   the plugin instance
     * @param langCode the language code (e.g. "en", "fr", "pt_BR")
     */
    public static void rewrite(WildTimber plugin, String langCode) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        // Load template from JAR resources
        InputStream tplStream = plugin.getResource("lang/config_" + langCode + ".yml");
        if (tplStream == null) {
            plugin.getLogger().info("[CommentRewriter] No template for lang '" + langCode + "', falling back to 'en'.");
            tplStream = plugin.getResource("lang/config_en.yml");
        }
        if (tplStream == null) {
            plugin.getLogger().warning("[CommentRewriter] No config comment template found. Skipping.");
            return;
        }

        // Load current user config values (with Bukkit — strips comments but preserves values)
        FileConfiguration userCfg = YamlConfiguration.loadConfiguration(configFile);

        // Read template as raw lines (preserves comments)
        List<String> templateLines;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(tplStream, StandardCharsets.UTF_8))) {
            templateLines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                templateLines.add(line);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[CommentRewriter] Failed to read template.", e);
            return;
        }

        // Merge template comments with user values
        List<String> merged = mergeComments(templateLines, userCfg);

        // Write merged result back to config.yml
        try {
            Files.write(configFile.toPath(), merged, StandardCharsets.UTF_8);
            plugin.getLogger().info("[CommentRewriter] config.yml updated with comments for language: " + langCode);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[CommentRewriter] Failed to write config.yml.", e);
        }
    }

    // ─── Core merge logic ────────────────────────────────────────────────────

    private static List<String> mergeComments(List<String> templateLines, FileConfiguration userCfg) {
        List<String> output = new ArrayList<>(templateLines.size() + 20);

        // Track current YAML path via indentation stacks
        // ArrayDeque used as a stack: push/peek/pop at head
        Deque<String> keyStack   = new ArrayDeque<>();
        Deque<Integer> indStack  = new ArrayDeque<>();

        // When we've injected a user list, skip the template's list items
        boolean skipListItems    = false;
        int     skipBelowIndent  = -1;

        for (String line : templateLines) {
            String trimmed = line.trim();

            // ── Blank line ───────────────────────────────────────────────────
            if (trimmed.isEmpty()) {
                skipListItems = false;
                output.add(line);
                continue;
            }

            // ── Comment line (pass through unchanged) ────────────────────────
            if (trimmed.startsWith("#")) {
                output.add(line);
                continue;
            }

            int indent = indentOf(line);

            // ── List item ────────────────────────────────────────────────────
            if (trimmed.startsWith("- ")) {
                if (skipListItems && indent >= skipBelowIndent) {
                    continue; // skip template list items; user's list already written
                }
                skipListItems = false;
                output.add(line);
                continue;
            }

            skipListItems = false;

            // ── Pop the stack until we reach the parent of the current indent ─
            while (!indStack.isEmpty() && indStack.peek() >= indent) {
                indStack.pop();
                keyStack.pop();
            }

            // ── Extract key name (before the first colon) ─────────────────────
            int colonPos = trimmed.indexOf(':');
            if (colonPos < 0) {
                output.add(line);
                continue;
            }
            String key      = trimmed.substring(0, colonPos).trim();
            String afterCol = trimmed.substring(colonPos + 1); // " value # comment" or ""

            // Push this key
            keyStack.push(key);
            indStack.push(indent);

            String fullPath = buildPath(keyStack);

            // ── Determine template value and inline comment ───────────────────
            String templateValue  = stripInlineComment(afterCol.trim());
            String inlineComment  = extractInlineComment(afterCol);
            String spaces         = " ".repeat(indent);

            // ── Section header (no value on this line) ────────────────────────
            if (templateValue.isEmpty()) {
                output.add(line);
                continue;
            }

            // ── List value ────────────────────────────────────────────────────
            if (userCfg.isList(fullPath)) {
                List<?> userList = userCfg.getList(fullPath);
                output.add(spaces + key + ":");
                if (userList != null) {
                    for (Object item : userList) {
                        output.add(spaces + "  - " + formatValue(item));
                    }
                }
                skipListItems   = true;
                skipBelowIndent = indent + 2;
                continue;
            }

            // ── Scalar value ──────────────────────────────────────────────────
            Object userVal = userCfg.get(fullPath);
            if (userVal != null && !(userVal instanceof org.bukkit.configuration.ConfigurationSection)
                    && !(userVal instanceof Map)) {
                String formatted = formatValue(userVal);
                if (inlineComment.isEmpty()) {
                    output.add(spaces + key + ": " + formatted);
                } else {
                    output.add(spaces + key + ": " + formatted + " " + inlineComment);
                }
            } else {
                // Key absent in user config → keep template default
                output.add(line);
            }
        }

        return output;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Returns the number of leading spaces of a line. */
    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    /**
     * Builds the dotted YAML path from the stack.
     * Stack has the most recent key at the head, so we reverse for root→leaf order.
     */
    private static String buildPath(Deque<String> stack) {
        List<String> parts = new ArrayList<>(stack);
        Collections.reverse(parts);
        return String.join(".", parts);
    }

    /**
     * Strips the inline comment from a YAML value string, respecting quoted strings.
     * E.g. "true # some comment" → "true"
     */
    private static String stripInlineComment(String valueStr) {
        boolean inQuote  = false;
        char    quoteChar = 0;
        for (int i = 0; i < valueStr.length(); i++) {
            char c = valueStr.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote   = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote && c == '#') {
                return valueStr.substring(0, i).trim();
            }
        }
        return valueStr.trim();
    }

    /**
     * Extracts the inline comment from the text after the colon.
     * Returns the comment including the "#", or an empty string if none.
     */
    private static String extractInlineComment(String afterColon) {
        String val = afterColon.trim();
        boolean inQuote  = false;
        char    quoteChar = 0;
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote   = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote && c == '#') {
                return val.substring(i);
            }
        }
        return "";
    }

    /**
     * Formats a Java value for YAML output.
     * Strings are quoted; booleans and numbers are written as-is.
     */
    private static String formatValue(Object value) {
        if (value instanceof Boolean || value instanceof Integer
                || value instanceof Long  || value instanceof Double
                || value instanceof Float) {
            return value.toString();
        }
        if (value instanceof String s) {
            // Always quote strings to avoid YAML ambiguity
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        // Fallback: toString
        return value.toString();
    }
}
