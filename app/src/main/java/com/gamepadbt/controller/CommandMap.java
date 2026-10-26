package com.gamepadbt.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qué texto se manda por Bluetooth para cada control del mando.
 * Clase de Java puro (sin Android) para poder probarla en cualquier PC.
 *
 * Sintaxis de un comando: "X"  -> manda X al presionar.
 *                         "X|x" -> manda X al presionar y x al soltar.
 * Un comando vacío significa "no mandar nada".
 */
public final class CommandMap {

    public enum Dir { NONE, UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT }

    public enum Kind { TEXT, NUMBER, BOOL }

    public static final class Entry {
        public final String group;
        public final String key;
        public final String label;
        public final String def;
        public final Kind kind;

        Entry(String group, String key, String label, String def, Kind kind) {
            this.group = group;
            this.key = key;
            this.label = label;
            this.def = def;
            this.kind = kind;
        }
    }

    public static final String G_MOVE = "Movimiento (stick izquierdo y cruceta)";
    public static final String G_RS = "Stick derecho";
    public static final String G_BTN = "Botones";
    public static final String G_OPT = "Opciones";

    public static final String OPT_NEWLINE = "opt_newline";
    public static final String OPT_REPEAT_MS = "opt_repeat_ms";
    public static final String OPT_DEADZONE = "opt_deadzone";

    public static final List<Entry> ENTRIES;

    static {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry(G_MOVE, "move_up", "Adelante", "F", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_down", "Atrás", "B", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_left", "Izquierda", "L", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_right", "Derecha", "R", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_stop", "Al soltar (parar)", "S", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_ul", "Diagonal adelante-izq. (opcional)", "", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_ur", "Diagonal adelante-der. (opcional)", "", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_dl", "Diagonal atrás-izq. (opcional)", "", Kind.TEXT));
        l.add(new Entry(G_MOVE, "move_dr", "Diagonal atrás-der. (opcional)", "", Kind.TEXT));

        l.add(new Entry(G_RS, "rs_up", "Arriba", "", Kind.TEXT));
        l.add(new Entry(G_RS, "rs_down", "Abajo", "", Kind.TEXT));
        l.add(new Entry(G_RS, "rs_left", "Izquierda", "", Kind.TEXT));
        l.add(new Entry(G_RS, "rs_right", "Derecha", "", Kind.TEXT));
        l.add(new Entry(G_RS, "rs_stop", "Al soltar", "", Kind.TEXT));

        l.add(new Entry(G_BTN, "btn_stop", "STOP (botón central, parada de emergencia)", "S", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_triangle", "Triángulo", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_circle", "Círculo", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_cross", "Equis (X)", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_square", "Cuadrado", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_l1", "L1", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_r1", "R1", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_l2", "L2", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_r2", "R2", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_share", "Share", "", Kind.TEXT));
        l.add(new Entry(G_BTN, "btn_options", "Options", "", Kind.TEXT));

        l.add(new Entry(G_OPT, OPT_NEWLINE, "Agregar salto de línea (\\n) a cada comando", "0", Kind.BOOL));
        l.add(new Entry(G_OPT, OPT_REPEAT_MS, "Repetir comando cada (ms, 0 = no repetir)", "120", Kind.NUMBER));
        l.add(new Entry(G_OPT, OPT_DEADZONE, "Zona muerta de los sticks (%)", "35", Kind.NUMBER));
        ENTRIES = Collections.unmodifiableList(l);
    }

    private final Map<String, String> values = new HashMap<>();

    public CommandMap() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        for (Entry e : ENTRIES) values.put(e.key, e.def);
    }

    public String get(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    public void set(String key, String value) {
        values.put(key, value == null ? "" : value);
    }

    public boolean getBool(String key) {
        return "1".equals(get(key));
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Zona muerta como fracción 0..0.9 */
    public float deadZone() {
        int pct = getInt(OPT_DEADZONE, 35);
        if (pct < 5) pct = 5;
        if (pct > 90) pct = 90;
        return pct / 100f;
    }

    public int repeatMs() {
        int ms = getInt(OPT_REPEAT_MS, 120);
        if (ms < 0) return 0;
        if (ms > 0 && ms < 30) return 30;
        return ms;
    }

    /** Parte que se manda al presionar. */
    public static String pressPart(String cmd) {
        if (cmd == null) return "";
        int i = cmd.indexOf('|');
        return i < 0 ? cmd : cmd.substring(0, i);
    }

    /** Parte que se manda al soltar. */
    public static String releasePart(String cmd) {
        if (cmd == null) return "";
        int i = cmd.indexOf('|');
        return i < 0 ? "" : cmd.substring(i + 1);
    }

    /** Agrega el salto de línea si está activado. Devuelve "" si no hay nada que mandar. */
    public String payload(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";
        return getBool(OPT_NEWLINE) ? cmd + "\n" : cmd;
    }

    // ---------------------------------------------------------------------
    // Dirección de un stick. x: derecha = +, y: ABAJO = + (coordenadas de pantalla)
    // ---------------------------------------------------------------------

    /** Dirección en 4 sentidos: gana el eje con más desplazamiento. */
    public static Dir direction4(float x, float y, float deadZone) {
        if (Math.hypot(x, y) < deadZone) return Dir.NONE;
        if (Math.abs(x) >= Math.abs(y)) return x > 0 ? Dir.RIGHT : Dir.LEFT;
        return y > 0 ? Dir.DOWN : Dir.UP;
    }

    /** Dirección en 8 sentidos (sectores de 45°). */
    public static Dir direction8(float x, float y, float deadZone) {
        if (Math.hypot(x, y) < deadZone) return Dir.NONE;
        // ángulo con 0° = derecha, 90° = arriba
        double deg = Math.toDegrees(Math.atan2(-y, x));
        if (deg < 0) deg += 360.0;
        int sector = (int) Math.floor((deg + 22.5) / 45.0) % 8;
        switch (sector) {
            case 0: return Dir.RIGHT;
            case 1: return Dir.UP_RIGHT;
            case 2: return Dir.UP;
            case 3: return Dir.UP_LEFT;
            case 4: return Dir.LEFT;
            case 5: return Dir.DOWN_LEFT;
            case 6: return Dir.DOWN;
            default: return Dir.DOWN_RIGHT;
        }
    }

    /**
     * Dirección final de un stick: usa diagonales solo si tienen comando configurado;
     * si no, cae al eje dominante (así nunca se manda "nada" estando inclinado).
     */
    public Dir resolve(String prefix, float x, float y) {
        Dir d = direction8(x, y, deadZone());
        switch (d) {
            case UP_LEFT:
            case UP_RIGHT:
            case DOWN_LEFT:
            case DOWN_RIGHT:
                if (commandFor(prefix, d).isEmpty()) return direction4(x, y, deadZone());
                return d;
            default:
                return d;
        }
    }

    /** Comando (parte "al presionar") configurado para una dirección. prefix = "move" o "rs". */
    public String commandFor(String prefix, Dir d) {
        String suffix;
        switch (d) {
            case UP: suffix = "up"; break;
            case DOWN: suffix = "down"; break;
            case LEFT: suffix = "left"; break;
            case RIGHT: suffix = "right"; break;
            case UP_LEFT: suffix = "ul"; break;
            case UP_RIGHT: suffix = "ur"; break;
            case DOWN_LEFT: suffix = "dl"; break;
            case DOWN_RIGHT: suffix = "dr"; break;
            default: return "";
        }
        return pressPart(get(prefix + "_" + suffix));
    }

    public String stopCommandFor(String prefix) {
        return pressPart(get(prefix + "_stop"));
    }
}
