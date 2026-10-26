package com.gamepadbt.controller;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Primera pantalla: lista los dispositivos Bluetooth YA EMPAREJADOS (el HC-05 del robot)
 * y abre el mando al tocar uno.
 */
public final class DeviceActivity extends Activity {

    private static final int REQ_PERMISSION = 10;
    private static final int REQ_ENABLE_BT = 11;

    private BluetoothAdapter adapter;
    private TextView info;
    private Button enableButton;
    private ListView listView;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;
    private boolean permissionAsked; // evita el bucle pedir-permiso -> onResume -> pedir-permiso

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101216);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Mando Bluetooth");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        info = new TextView(this);
        info.setTextColor(0xFFC9CED8);
        info.setTextSize(14);
        info.setPadding(0, dp(8), 0, dp(8));
        root.addView(info);

        enableButton = new Button(this);
        enableButton.setText("Activar Bluetooth");
        enableButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestEnableBluetooth(); }
        });
        root.addView(enableButton);

        listAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                BluetoothDevice d = devices.get(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText(safeName(d));
                ((TextView) v.findViewById(android.R.id.text2)).setText(d.getAddress());
                return v;
            }
        };
        listView = new ListView(this);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                openGamepad(devices.get(pos));
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button refresh = new Button(this);
        refresh.setText("Actualizar lista");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                permissionAsked = false;
                refreshDevices();
            }
        });
        Button pair = new Button(this);
        pair.setText("Emparejar nuevo");
        pair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });
        row.addView(refresh);
        row.addView(pair);
        root.addView(row);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 31) return true; // en Android 11 o menor se concede al instalar
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshDevices() {
        devices.clear();
        listAdapter.clear();
        enableButton.setVisibility(View.GONE);

        if (adapter == null) {
            info.setText("Este dispositivo no tiene Bluetooth.");
            return;
        }
        if (!hasPermission()) {
            info.setText("Hace falta el permiso de Bluetooth para ver y conectar dispositivos.\n"
                    + "Toca \"Actualizar lista\" para pedirlo de nuevo.");
            if (!permissionAsked) {
                permissionAsked = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMISSION);
            }
            return;
        }
        if (!adapter.isEnabled()) {
            info.setText("El Bluetooth está apagado.");
            enableButton.setVisibility(View.VISIBLE);
            return;
        }

        try {
            devices.addAll(adapter.getBondedDevices());
        } catch (SecurityException e) {
            info.setText("Permiso de Bluetooth denegado.");
            return;
        }
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override public int compare(BluetoothDevice a, BluetoothDevice b) {
                return safeName(a).compareToIgnoreCase(safeName(b));
            }
        });
        for (int i = 0; i < devices.size(); i++) listAdapter.add(String.valueOf(i));
        listAdapter.notifyDataSetChanged();

        if (devices.isEmpty()) {
            info.setText("No hay dispositivos emparejados.\n\n"
                    + "1. Enciende el robot.\n"
                    + "2. Toca \"Emparejar nuevo\" y elige el módulo (HC-05, normalmente se llama \"HC-05\").\n"
                    + "3. Si pide PIN, prueba 1234 o 0000.\n"
                    + "4. Vuelve acá.");
        } else {
            info.setText("Toca el módulo del robot para abrir el mando:");
        }
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private void requestEnableBluetooth() {
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
        } catch (SecurityException e) {
            info.setText("Permiso de Bluetooth denegado.");
        }
    }

    private void openGamepad(BluetoothDevice d) {
        Intent i = new Intent(this, GamepadActivity.class);
        i.putExtra(GamepadActivity.EXTRA_ADDRESS, d.getAddress());
        i.putExtra(GamepadActivity.EXTRA_NAME, safeName(d));
        startActivity(i);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshDevices();
            } else {
                info.setText("Sin el permiso de Bluetooth la app no puede funcionar. "
                        + "Concédelo en Ajustes > Aplicaciones > Mando Bluetooth > Permisos.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) refreshDevices();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
