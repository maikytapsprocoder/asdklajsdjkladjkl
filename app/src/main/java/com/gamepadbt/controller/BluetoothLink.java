package com.gamepadbt.controller;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conexión Bluetooth clásica (perfil serie SPP / RFCOMM) con un módulo tipo HC-05/HC-06.
 * Es el mismo mecanismo que usan las apps de "terminal Bluetooth" para Arduino.
 *
 * - Conecta en un hilo aparte (nunca en el hilo de la pantalla).
 * - Si la conexión se cae intenta reconectar sola hasta 3 veces.
 * - Todas las llamadas al Listener llegan en el hilo principal.
 */
public final class BluetoothLink {

    public enum State { CONNECTING, CONNECTED, RETRYING, FAILED, CLOSED }

    public interface Listener {
        void onState(State state, String detail);
        void onReceived(String text);
    }

    /** UUID estándar del perfil serie (SPP). Lo usan HC-05, HC-06 y casi todos los módulos. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int MAX_ATTEMPTS = 3;

    private final BluetoothAdapter adapter;
    private final BluetoothDevice device;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private volatile boolean closed;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile int generation;

    public BluetoothLink(BluetoothAdapter adapter, BluetoothDevice device, Listener listener) {
        this.adapter = adapter;
        this.device = device;
        this.listener = listener;
    }

    /** Empieza (o reinicia) la conexión. */
    public synchronized void connect() {
        if (closed) return;
        final int gen = ++generation;
        closeSocketQuietly();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { runLoop(gen); }
        }, "bt-link");
        t.setDaemon(true);
        t.start();
    }

    /** Manda texto al robot. Se puede llamar desde cualquier hilo. */
    public void send(final String text) {
        if (closed || text == null || text.isEmpty()) return;
        final byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.execute(new Runnable() {
            @Override public void run() {
                OutputStream o = out;
                if (o == null) return;
                try {
                    o.write(data);
                    o.flush();
                } catch (IOException e) {
                    // El hilo de lectura se entera al cerrarse el socket y reconecta.
                    closeSocketQuietly();
                }
            }
        });
    }

    public boolean isConnected() {
        return out != null && !closed;
    }

    /**
     * Cierra la conexión DESPUÉS de mandar lo que ya estaba en cola (por ejemplo el "parar"
     * final al salir de la pantalla).
     */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        writer.execute(new Runnable() {
            @Override public void run() { closeSocketQuietly(); }
        });
        writer.shutdown();
        // Red de seguridad: si una escritura se queda trabada, cerrar igual al cabo de 1 s.
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                sleep(1000);
                closeSocketQuietly();
            }
        }, "bt-close");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------------

    private void runLoop(int gen) {
        int lossCount = 0;
        while (!closed && gen == generation) {
            post(lossCount == 0 ? State.CONNECTING : State.RETRYING,
                    lossCount == 0 ? "Conectando…" : "Conexión perdida, reconectando…", gen);

            String error = tryConnect(gen);
            if (closed || gen != generation) return;
            if (error != null) {
                post(State.FAILED, error, gen);
                return;
            }

            post(State.CONNECTED, "Conectado", gen);
            readLoop(gen);                       // bloquea hasta que se corta
            if (closed || gen != generation) return;

            lossCount++;
            if (lossCount > MAX_ATTEMPTS) {
                post(State.FAILED, "Se perdió la conexión y no se pudo recuperar.", gen);
                return;
            }
            sleep(500L * lossCount);
        }
    }

    /** Devuelve null si conectó, o el mensaje de error. */
    private String tryConnect(int gen) {
        String lastError = "No se pudo conectar. ¿El robot está encendido y cerca?";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (closed || gen != generation) return "Cancelado";
            BluetoothSocket s = null;
            try {
                cancelDiscoveryIfAllowed(); // una búsqueda en curso frena la conexión
                if (attempt == 1) {
                    s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                } else if (attempt == 2) {
                    s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                } else {
                    s = createChannel1Socket();
                    if (s == null) s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                }
                socket = s;
                s.connect();
                if (closed || gen != generation) {
                    closeSocket(s);
                    return "Cancelado";
                }
                out = s.getOutputStream();
                return null;
            } catch (SecurityException e) {
                closeSocket(s);
                return "Falta el permiso de Bluetooth. Concédelo en los ajustes de la app.";
            } catch (IOException e) {
                closeSocket(s);
                sleep(400L * attempt);
            }
        }
        return lastError;
    }

    /** Cancelar búsqueda pide BLUETOOTH_SCAN en Android 12+; no lo pedimos, así que es opcional. */
    private void cancelDiscoveryIfAllowed() {
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // sin permiso de búsqueda: seguimos igual
        }
    }

    /** Truco clásico para algunos HC-05 que no anuncian bien su servicio: canal 1 directo. */
    private BluetoothSocket createChannel1Socket() {
        try {
            return (BluetoothSocket) device.getClass()
                    .getMethod("createRfcommSocket", int.class)
                    .invoke(device, 1);
        } catch (Exception e) {
            return null;
        }
    }

    private void readLoop(int gen) {
        BluetoothSocket s = socket;
        if (s == null) return;
        try {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[256];
            while (!closed && gen == generation) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n > 0) {
                    final String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                    main.post(new Runnable() {
                        @Override public void run() { if (!closed) listener.onReceived(text); }
                    });
                }
            }
        } catch (IOException ignored) {
            // socket cerrado o enlace caído: salimos y runLoop decide si reconecta
        } finally {
            closeSocket(s);
        }
    }

    /** Cierra un socket concreto y limpia los campos solo si todavía apuntan a él. */
    private synchronized void closeSocket(BluetoothSocket s) {
        if (s == null) return;
        if (socket == s) {
            socket = null;
            out = null;
        }
        try { s.close(); } catch (IOException ignored) { }
    }

    private synchronized void closeSocketQuietly() {
        closeSocket(socket);
    }

    private void post(final State state, final String detail, final int gen) {
        main.post(new Runnable() {
            @Override public void run() {
                if (gen == generation || state == State.CLOSED) listener.onState(state, detail);
            }
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
