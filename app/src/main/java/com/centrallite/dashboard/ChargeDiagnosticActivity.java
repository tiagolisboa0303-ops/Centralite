package com.centrallite.dashboard;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ChargeDiagnosticActivity extends Activity {
    private static final int RESTORE_REQUEST = 6202;
    private TextView status;
    private Button adminButton;
    private Button automaticButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        refreshAdminButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAdminButton();
        refreshAutomaticButton();
    }

    private void buildScreen() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(16));
        content.setBackgroundColor(Color.rgb(9, 13, 18));

        TextView title = new TextView(this);
        title.setText("ENERGIA E BLOQUEIO");
        title.setTextColor(Color.rgb(243, 156, 18));
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, params());

        TextView help = new TextView(this);
        help.setText("Faça primeiro o diagnóstico. O teste restaura a carga automaticamente após 15 segundos.");
        help.setTextColor(Color.LTGRAY);
        help.setTextSize(15);
        help.setPadding(0, dp(8), 0, dp(12));
        content.addView(help, params());

        Button diagnostic = button("1. VERIFICAR ROOT E CARREGAMENTO");
        diagnostic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runDiagnostic(); }
        });
        content.addView(diagnostic, params());

        Button test = button("2. TESTAR CORTE POR 15 SEGUNDOS");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { testChargeCut(); }
        });
        content.addView(test, params());

        adminButton = button("3. PERMISSÃO DE BLOQUEIO");
        adminButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggleAdmin(); }
        });
        content.addView(adminButton, params());

        automaticButton = button("4. CONTROLE AUTOMÁTICO DE CARGA");
        automaticButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean enabled = !ChargePrefs.isEnabled(ChargeDiagnosticActivity.this);
                if (enabled) {
                    Toast.makeText(ChargeDiagnosticActivity.this,
                            "Faça o teste de 15 segundos. O controle só será ativado se ele funcionar.",
                            Toast.LENGTH_LONG).show();
                } else {
                    ChargePrefs.setEnabled(ChargeDiagnosticActivity.this, false);
                    new Thread(new Runnable() {
                        @Override public void run() { PowerControl.setInputEnabled(true); }
                    }, "CentralLiteManualChargeOn").start();
                }
                refreshAutomaticButton();
            }
        });
        content.addView(automaticButton, params());

        Button close = button("VOLTAR PARA A CENTRAL");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        content.addView(close, params());

        status = new TextView(this);
        status.setText("Aguardando diagnóstico.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setPadding(dp(8), dp(16), dp(8), dp(20));
        content.addView(status, params());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        refreshAutomaticButton();
    }

    private void runDiagnostic() {
        status.setText("Verificando...");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean root = PowerControl.hasRoot();
                final String node = PowerControl.findControlNode();
                final String value = root && node != null ? PowerControl.readNode(node) : "—";
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        StringBuilder message = new StringBuilder();
                        message.append(root ? "ROOT: disponível" : "ROOT: não disponível");
                        if (node == null) {
                            message.append("\n\nCONTROLADOR: não encontrado");
                        } else {
                            message.append("\n\nCONTROLADOR: ").append(node);
                            message.append("\nVALOR ATUAL: ").append(value);
                            message.append(PowerControl.isFullInputControl(node)
                                    ? "\n\nRESULTADO: pode suspender a entrada USB."
                                    : "\n\nRESULTADO: pode interromper a carga da bateria, mas talvez não elimine todo o consumo externo.");
                        }
                        if (!root) {
                            message.append("\n\nSem root, a Central ainda bloqueará a tela pelo SYNC, porém não conseguirá cortar a carga.");
                        }
                        status.setText(message.toString());
                    }
                });
            }
        }, "CentralLiteDiagnostic").start();
    }

    private void testChargeCut() {
        status.setText("Verificando root antes do teste...");
        new Thread(new Runnable() {
            @Override public void run() {
                if (!PowerControl.hasRoot() || PowerControl.findControlNode() == null) {
                    ChargePrefs.setEnabled(ChargeDiagnosticActivity.this, false);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            status.setText("Não foi possível testar. Não há root ou controlador compatível.");
                            refreshAutomaticButton();
                        }
                    });
                    return;
                }

                // Root permission is already confirmed before the fail-safe countdown begins.
                scheduleRestore();
                final boolean success = PowerControl.setInputEnabled(false);
                ChargePrefs.setEnabled(ChargeDiagnosticActivity.this, success);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        status.setText(success
                                ? "Comando aceito. A carga será liberada novamente em 15 segundos. O controle automático foi ativado."
                                : "Não foi possível cortar. Não há root ou controlador compatível.");
                        refreshAutomaticButton();
                    }
                });
            }
        }, "CentralLiteChargeTest").start();
    }

    private void scheduleRestore() {
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent restore = PendingIntent.getBroadcast(this, RESTORE_REQUEST,
                new Intent(this, ChargeRestoreReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.set(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 15000L, restore);
    }

    private void toggleAdmin() {
        final DevicePolicyManager policy = (DevicePolicyManager)
                getSystemService(Context.DEVICE_POLICY_SERVICE);
        final ComponentName admin = new ComponentName(this, CentralDeviceAdminReceiver.class);
        if (policy != null && policy.isAdminActive(admin)) {
            ChargePrefs.setEnabled(this, false);
            new Thread(new Runnable() {
                @Override public void run() { PowerControl.setInputEnabled(true); }
            }, "CentralLiteAdminRemovedChargeOn").start();
            policy.removeActiveAdmin(admin);
            refreshAdminButton();
            Toast.makeText(this, "Permissão removida. O app poderá ser desinstalado normalmente.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent request = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        request.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        request.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Necessário para apagar a tela após desconectar do SYNC.");
        startActivity(request);
    }

    private void refreshAdminButton() {
        if (adminButton == null) return;
        DevicePolicyManager policy = (DevicePolicyManager)
                getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, CentralDeviceAdminReceiver.class);
        boolean active = policy != null && policy.isAdminActive(admin);
        adminButton.setText(active
                ? "3. BLOQUEIO ATIVO — TOQUE PARA REMOVER"
                : "3. PERMITIR BLOQUEIO DA TELA");
        adminButton.setTextColor(active ? Color.GREEN : Color.WHITE);
    }

    private void refreshAutomaticButton() {
        if (automaticButton == null) return;
        boolean enabled = ChargePrefs.isEnabled(this);
        automaticButton.setText(enabled
                ? "4. CONTROLE DE CARGA: ATIVADO — TOQUE PARA DESLIGAR"
                : "4. CONTROLE DE CARGA: DESATIVADO");
        automaticButton.setTextColor(enabled ? Color.GREEN : Color.WHITE);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(35, 45, 58));
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }
}
