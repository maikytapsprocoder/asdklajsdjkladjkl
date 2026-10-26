package com.gamepadbt.controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pantalla del mando: dibuja el mando, manda los comandos por Bluetooth y muestra el estado.
 */
public final class GamepadActivity extends Activity implements BluetoothLink.Listener, ControlLogic.Sender {

    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";

    private static final String PREFS = "gamepad";
    private static final int LOG_LIMIT = 6000;
    private static final long TICK_MS = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CommandMap map = new CommandMap();
    private final StringBuilder log = new StringBuilder();

    private GamepadView gamepad;
    private ControlLogic logic;
    private BluetoothLink link;
    private TextView status;
    private Button reconnectButton;
    private String deviceName;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            logic.onTick(SystemClock.uptimeMillis());
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String address = getIntent().getStringExtra(EXTRA_ADDRESS);
        deviceName = getIntent().getStringExtra(EXTRA_NAME);
        if (deviceName == null) deviceName = address;

        loadCommands();

        gamepad = new GamepadView(this);
        logic = new ControlLogic(map, this);
        gamepad.setLogic(logic);

        FrameLayout root = new FrameLayout(this);
        root.addView(gamepad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildOverlay());
        setContentView(root);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            setStatus("Bluetooth no disponible", 0xFFFF7C6C);
            return;
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        link = new BluetoothLink(adapter, device, this);
        link.connect();
    }

    /** Botones pequeños (ajustes, registro, reconectar) y línea de estado sobre el panel central. */
    private View buildOverlay() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button settings = smallButton("⚙");
        settings.setContentDescription("Ajustes de comandos");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        Button logButton = smallButton("☰");
        logButton.setContentDescription("Registro de mensajes");
        logButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });
        reconnectButton = smallButton("↻");
        reconnectButton.setContentDescription("Reconectar");
        reconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (link != null) {
                    logic.releaseAll();
                    gamepad.resetTouches();
                    link.connect();
                }
            }
        });
        row.addView(settings);
        row.addView(logButton);
        row.addView(reconnectButton);
        col.addView(row);

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(0xFFC9CED8);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setMaxWidth(dp(170));
        status.setText("Conectando…");
        col.addView(status);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(14);
        col.setLayoutParams(lp);
        return col;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setTextColor(0xFFC9CED8);
        b.setBackgroundColor(0xFF3A3F4B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(36));
        lp.setMargins(dp(3), 0, dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        handler.postDelayed(ticker, TICK_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        // Seguridad: si salimos de la pantalla (llamada, botón inicio, ajustes) el robot se detiene.
        if (logic != null) logic.releaseAll();
        if (gamepad != null) gamepad.resetTouches();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (link != null) link.close();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------------
    // ControlLogic.Sender  (lo que se manda al robot)
    // ---------------------------------------------------------------------

    @Override
    public void send(String payload, String label) {
        if (link == null || !link.isConnected()) {
            if (label != null) setStatus("Sin conexión (no se envió " + printable(payload) + ")", 0xFFFFB74D);
            return;
        }
        link.send(payload);
        appendLog("→ " + printable(payload) + (label != null ? "   (" + label + ")" : ""));
        if (label != null) setStatus("→ " + printable(payload), 0xFFC9CED8);
    }

    // ---------------------------------------------------------------------
    // BluetoothLink.Listener
    // ---------------------------------------------------------------------

    @Override
    public void onState(BluetoothLink.State state, String detail) {
        boolean ok = state == BluetoothLink.State.CONNECTED;
        gamepad.setConnected(ok);
        if (!ok) {
            logic.releaseAll();
            gamepad.resetTouches();
        }
        int color;
        switch (state) {
            case CONNECTED: color = 0xFF4CC38A; break;
            case FAILED: color = 0xFFFF7C6C; break;
            default: color = 0xFFFFB74D; break;
        }
        String text = ok ? "Conectado: " + deviceName : detail;
        setStatus(text, color);
        appendLog("● " + detail);
        if (state == BluetoothLink.State.FAILED) {
            Toast.makeText(this, detail + "  Toca ↻ para reintentar.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onReceived(String text) {
        appendLog("← " + printable(text));
        setStatus("← " + printable(text), 0xFF90CAF9);
    }

    // ---------------------------------------------------------------------
    // Registro
    // ---------------------------------------------------------------------

    private void setStatus(String s, int color) {
        if (status == null) return;
        status.setText(s);
        status.setTextColor(color);
    }

    private void appendLog(String line) {
        log.append(line).append('\n');
        if (log.length() > LOG_LIMIT) log.delete(0, log.length() - LOG_LIMIT);
    }

    private static String printable(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }

    private void showLog() {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setText(log.length() == 0 ? "(todavía no hay mensajes)" : log.toString());
        final ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.post(new Runnable() {
            @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); }
        });
        new AlertDialog.Builder(this)
                .setTitle("Registro (→ enviado, ← recibido)")
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Borrar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { log.setLength(0); }
                })
                .show();
    }

    // ---------------------------------------------------------------------
    // Ajustes de comandos
    // ---------------------------------------------------------------------

    private void loadCommands() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (CommandMap.Entry e : CommandMap.ENTRIES) {
            map.set(e.key, sp.getString(e.key, e.def));
        }
    }

    private void saveCommands() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (CommandMap.Entry e : CommandMap.ENTRIES) ed.putString(e.key, map.get(e.key));
        ed.apply();
    }

    private void showSettings() {
        logic.releaseAll();
        gamepad.resetTouches();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView intro = new TextView(this);
        intro.setText("Escribe la letra o texto que se manda por Bluetooth con cada control. "
                + "Déjalo vacío para que no mande nada.\n"
                + "Usa \"X|x\" para mandar X al presionar y x al soltar.\n"
                + "Los valores de movimiento deben coincidir con tu programa de Arduino.");
        intro.setTextSize(13);
        intro.setPadding(0, 0, 0, dp(8));
        col.addView(intro);

        final Map<String, View> inputs = new LinkedHashMap<>();
        String lastGroup = null;
        for (CommandMap.Entry e : CommandMap.ENTRIES) {
            if (!e.group.equals(lastGroup)) {
                lastGroup = e.group;
                TextView header = new TextView(this);
                header.setText(e.group);
                header.setTextSize(15);
                header.setTypeface(Typeface.DEFAULT_BOLD);
                header.setTextColor(0xFF90CAF9);
                header.setPadding(0, dp(14), 0, dp(2));
                col.addView(header);
            }
            if (e.kind == CommandMap.Kind.BOOL) {
                CheckBox cb = new CheckBox(this);
                cb.setText(e.label);
                cb.setChecked(map.getBool(e.key));
                col.addView(cb);
                inputs.put(e.key, cb);
            } else {
                TextView label = new TextView(this);
                label.setText(e.label);
                label.setTextSize(13);
                label.setPadding(0, dp(6), 0, 0);
                col.addView(label);
                EditText et = new EditText(this);
                et.setSingleLine(true);
                et.setText(map.get(e.key));
                if (e.kind == CommandMap.Kind.NUMBER) {
                    et.setInputType(InputType.TYPE_CLASS_NUMBER);
                } else {
                    et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    et.setHint("vacío = no manda nada");
                }
                col.addView(et);
                inputs.put(e.key, et);
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(col);

        new AlertDialog.Builder(this)
                .setTitle("Comandos del mando")
                .setView(sv)
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        for (CommandMap.Entry e : CommandMap.ENTRIES) {
                            View v = inputs.get(e.key);
                            String value;
                            if (v instanceof CheckBox) value = ((CheckBox) v).isChecked() ? "1" : "0";
                            else value = ((EditText) v).getText().toString().trim();
                            map.set(e.key, value);
                        }
                        saveCommands();
                        Toast.makeText(GamepadActivity.this, "Guardado", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Restaurar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        map.resetToDefaults();
                        saveCommands();
                        Toast.makeText(GamepadActivity.this, "Valores originales restaurados", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
