import com.gamepadbt.controller.CommandMap;
import com.gamepadbt.controller.CommandMap.Dir;
import com.gamepadbt.controller.ControlLogic;

import java.util.ArrayList;
import java.util.List;

/** Prueba la lógica sin Android:  ver tools/run-tests.sh */
public class LogicTest {
    static int failures = 0;
    static int checks = 0;

    static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FALLA: " + what + " -> esperado " + expected + " pero fue " + actual);
        }
    }

    static List<String> out;
    static ControlLogic make(CommandMap m) {
        out = new ArrayList<>();
        return new ControlLogic(m, (p, l) -> out.add(p));
    }

    public static void main(String[] a) {
        CommandMap m = new CommandMap();

        // --- direcciones 4 sentidos (y hacia abajo = +) ---
        eq(Dir.NONE, CommandMap.direction4(0.1f, 0.1f, 0.35f), "zona muerta");
        eq(Dir.UP, CommandMap.direction4(0f, -0.9f, 0.35f), "arriba");
        eq(Dir.DOWN, CommandMap.direction4(0f, 0.9f, 0.35f), "abajo");
        eq(Dir.LEFT, CommandMap.direction4(-0.9f, 0.2f, 0.35f), "izquierda");
        eq(Dir.RIGHT, CommandMap.direction4(0.9f, -0.2f, 0.35f), "derecha");
        eq(Dir.UP, CommandMap.direction4(0.5f, -0.6f, 0.35f), "eje dominante vertical");

        // --- 8 sentidos ---
        eq(Dir.UP_RIGHT, CommandMap.direction8(0.7f, -0.7f, 0.35f), "diag arriba-der");
        eq(Dir.UP_LEFT, CommandMap.direction8(-0.7f, -0.7f, 0.35f), "diag arriba-izq");
        eq(Dir.DOWN_LEFT, CommandMap.direction8(-0.7f, 0.7f, 0.35f), "diag abajo-izq");
        eq(Dir.DOWN_RIGHT, CommandMap.direction8(0.7f, 0.7f, 0.35f), "diag abajo-der");
        eq(Dir.RIGHT, CommandMap.direction8(0.95f, -0.2f, 0.35f), "casi derecha");

        // --- sin diagonales configuradas cae al eje dominante ---
        eq(Dir.UP, m.resolve("move", 0.4f, -0.6f), "sin diagonal -> arriba");
        m.set("move_ur", "E");
        eq(Dir.UP_RIGHT, m.resolve("move", 0.7f, -0.7f), "con diagonal -> UP_RIGHT");
        m.set("move_ur", "");

        // --- sintaxis press|release ---
        eq("X", CommandMap.pressPart("X|x"), "press");
        eq("x", CommandMap.releasePart("X|x"), "release");
        eq("X", CommandMap.pressPart("X"), "press sin |");
        eq("", CommandMap.releasePart("X"), "release sin |");

        // --- escenario: empujar stick adelante, mantener, soltar ---
        ControlLogic c = make(m);
        c.onLeftStick(0f, -1f, 0);
        c.onLeftStick(0f, -0.95f, 10);   // misma dirección: no debe mandar de nuevo
        eq("[F]", out.toString(), "adelante una sola vez");
        c.onTick(50);                      // aún no pasan 120 ms
        eq("[F]", out.toString(), "sin repetir antes de tiempo");
        c.onTick(120);
        c.onTick(200);                     // 80 ms después del último: no repite
        c.onTick(240);
        eq("[F, F, F]", out.toString(), "repite cada 120 ms");
        c.onLeftStick(0f, 0f, 250);
        eq("[F, F, F, S]", out.toString(), "al soltar manda S");
        c.onTick(1000);
        eq("[F, F, F, S]", out.toString(), "ya parado no repite");

        // --- cambio de dirección sin pasar por el centro ---
        c = make(m);
        c.onLeftStick(0f, -1f, 0);
        c.onLeftStick(1f, 0f, 20);
        c.onLeftStick(0f, 1f, 40);
        c.onLeftStick(-1f, 0f, 60);
        eq("[F, R, B, L]", out.toString(), "F R B L");

        // --- cruceta tiene prioridad sobre el stick ---
        c = make(m);
        c.onLeftStick(0f, -1f, 0);         // F
        c.onDpad(Dir.LEFT, 10);            // L (gana cruceta)
        c.onLeftStick(0f, -1f, 20);        // sigue siendo L
        c.onDpad(Dir.NONE, 30);            // vuelve al stick -> F
        c.onLeftStick(0f, 0f, 40);         // S
        eq("[F, L, F, S]", out.toString(), "prioridad cruceta");

        // --- botón STOP ---
        c = make(m);
        c.onLeftStick(0f, -1f, 0);
        c.emergencyStop();
        eq("[F, S]", out.toString(), "STOP manda S");
        c.onTick(500);
        eq("[F, S]", out.toString(), "tras STOP no repite");
        c.onLeftStick(0f, 0f, 510);        // soltar el dedo después del STOP no manda otra S
        eq("[F, S]", out.toString(), "soltar tras STOP no duplica");

        // --- releaseAll (salir de pantalla / perder conexión) ---
        c = make(m);
        c.onLeftStick(1f, 0f, 0);
        c.releaseAll();
        eq("[R, S]", out.toString(), "releaseAll para si se movía");
        c.releaseAll();
        eq("[R, S]", out.toString(), "releaseAll sin movimiento no manda nada");

        // --- botones: vacío no manda; con comando manda; press|release ---
        c = make(m);
        c.onButton("cross", true);
        eq("[]", out.toString(), "botón sin comando no manda");
        m.set("btn_cross", "X|x");
        m.set("btn_circle", "O");
        c.onButton("cross", true);
        c.onButton("cross", false);
        c.onButton("circle", true);
        c.onButton("circle", false);
        eq("[X, x, O]", out.toString(), "botones press/release");

        // --- salto de línea ---
        m.set(CommandMap.OPT_NEWLINE, "1");
        c = make(m);
        c.onLeftStick(0f, -1f, 0);
        c.onLeftStick(0f, 0f, 5);
        eq("[F\n, S\n]", out.toString(), "salto de línea");
        m.set(CommandMap.OPT_NEWLINE, "0");

        // --- repetición desactivada ---
        m.set(CommandMap.OPT_REPEAT_MS, "0");
        c = make(m);
        c.onLeftStick(0f, -1f, 0);
        c.onTick(5000);
        eq("[F]", out.toString(), "repeat 0 no repite");
        m.set(CommandMap.OPT_REPEAT_MS, "120");

        // --- stick derecho independiente ---
        m.set("rs_up", "U");
        m.set("rs_stop", "u");
        c = make(m);
        c.onRightStick(0f, -1f, 0);
        c.onLeftStick(0f, -1f, 0);
        c.onRightStick(0f, 0f, 10);
        eq("[U, F, u]", out.toString(), "stick derecho independiente");

        // --- zona muerta configurable ---
        m.set(CommandMap.OPT_DEADZONE, "60");
        eq(Dir.NONE, m.resolve("move", 0f, -0.5f), "zona muerta 60%");
        eq(Dir.UP, m.resolve("move", 0f, -0.7f), "fuera de zona muerta 60%");

        // --- valores inválidos no rompen ---
        m.set(CommandMap.OPT_REPEAT_MS, "abc");
        eq(120, m.repeatMs(), "repeat inválido -> 120");
        m.set(CommandMap.OPT_REPEAT_MS, "5");
        eq(30, m.repeatMs(), "repeat mínimo 30");

        System.out.println(checks + " verificaciones, " + failures + " fallas");
        if (failures > 0) System.exit(1);
    }
}
