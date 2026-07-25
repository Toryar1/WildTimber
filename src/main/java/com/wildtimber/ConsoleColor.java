package com.wildtimber;

/**
 * Utilité de couleurs ANSI pour la console du serveur.
 */
public class ConsoleColor {
    public static final String PINK    = "\033[95m";
    public static final String GRAY    = "\033[90m";
    public static final String GREEN   = "\033[92m";
    public static final String RED     = "\033[91m";
    public static final String YELLOW  = "\033[93m";
    public static final String RESET   = "\033[0m";
    public static final String BOLD    = "\033[1m";

    public static final String DEBUG_PREFIX = PINK + BOLD + "[WildTimber]" + RESET + " " + GRAY + "[DEBUG]" + RESET + " ";
    public static final String WARN_PREFIX  = PINK + BOLD + "[WildTimber]" + RESET + " " + RED + "[WARN]" + RESET + " ";
}
