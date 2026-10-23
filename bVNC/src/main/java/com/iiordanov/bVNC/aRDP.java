/**
 * Copyright (C) 2012 Iordan Iordanov
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.iiordanov.bVNC;

import static com.iiordanov.bVNC.Constants.MAX_DESKTOP_SCALE_PERCENTAGE;
import static com.iiordanov.bVNC.Constants.MIN_DESKTOP_SCALE_PERCENTAGE;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.snackbar.Snackbar;
import com.iiordanov.bVNC.dialogs.IntroTextDialog;
import com.iiordanov.bVNC.tailscale.TsnetManager;
import com.iiordanov.permissions.AudioPermissionGroups;
import com.iiordanov.permissions.AudioPermissionsManager;
import com.morpheusly.common.Utilities;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.remoteClientUi.R;

import java.util.ArrayList;
import java.util.List;

/**
 * aRDP is the Activity for setting up RDP connections.
 */
public class aRDP extends MainConfiguration {
    private Spinner spinnerRdpGeometry;
    private EditText rdpDomain;
    private ToggleButton rdpGatewayEnabled;
    private LinearLayout layoutRdpGatewaySettings;
    private EditText rdpGatewayHostname;
    private EditText rdpGatewayPort;
    private EditText rdpGatewayUsername;
    private EditText rdpGatewayDomain;
    private EditText rdpGatewayPassword;
    private CheckBox checkboxKeepRdpGatewayPassword;
    private MaterialButtonToggleGroup groupRemoteSoundType;
    private CompoundButton checkboxEnableRecording;
    private CompoundButton checkboxConsoleMode;
    private CompoundButton checkboxRedirectSdCard;
    private CompoundButton checkboxRemoteFx;
    private CompoundButton checkboxDesktopBackground;
    private CompoundButton checkboxFontSmoothing;
    private CompoundButton checkboxDesktopComposition;
    private CompoundButton checkboxWindowContents;
    private CompoundButton checkboxMenuAnimation;
    private CompoundButton checkboxVisualStyles;
    private CompoundButton checkboxEnableGfx;
    private CompoundButton checkboxEnableGfxH264;
    private CompoundButton checkboxEnableGlyphCache;
    private CompoundButton checkboxPreferSendingUnicode;
    private Spinner spinnerRdpColor;
    private Spinner spinnerRdpSecurity;
    private List<String> rdpColorArray;
    private SeekBar desktopScaleSeekBar;
    private TextView desktopScaleProgressTextView;

    private LinearLayout layoutTailscaleSettings;
    private TextView tailscaleStatusText;
    private Button buttonTailscaleLogin;
    private Button buttonTailscaleLogout;
    private TextView tailscalePeersLabel;
    private Spinner spinnerTailscalePeers;
    private EditText tailscaleAuthKey;
    private EditText tailscaleHostname;
    private final List<TsnetManager.PeerInfo> currentTailscalePeers = new ArrayList<>();
    private final Handler tailscaleUiHandler = new Handler(Looper.getMainLooper());
    private boolean tailscalePollingActive = false;

    @Override
    public void onCreate(Bundle icicle) {
        layoutID = R.layout.main_rdp;
        super.onCreate(icicle);
        setConnectionTypeSpinnerAdapter(R.array.rdp_connection_type);
        initializeRdpSpecificConnectionParameters();
        initializeTailscaleSettings();
        initializeRdpGatewaySettings();
        initializeRdpColorSpinner();
        initializeRdpResolutionSpinner();
        initializeRdpSecuritySpinner();
        initializeAdvancedSettings();
    }

    @Override
    protected void setVisibilityOfTailscaleWidgets(int visibility) {
        if (layoutTailscaleSettings != null) {
            layoutTailscaleSettings.setVisibility(visibility);
            if (visibility == View.VISIBLE) {
                refreshTailscaleAccountAsync(false, false);
            }
        }
    }

    private void initializeTailscaleSettings() {
        layoutTailscaleSettings = findViewById(R.id.layoutTailscaleSettings);
        tailscaleStatusText = findViewById(R.id.tailscaleStatusText);
        buttonTailscaleLogin = findViewById(R.id.buttonTailscaleLogin);
        buttonTailscaleLogout = findViewById(R.id.buttonTailscaleLogout);
        tailscalePeersLabel = findViewById(R.id.tailscalePeersLabel);
        spinnerTailscalePeers = findViewById(R.id.spinnerTailscalePeers);
        tailscaleAuthKey = findViewById(R.id.tailscaleAuthKey);
        tailscaleHostname = findViewById(R.id.tailscaleHostname);

        TsnetManager ts = TsnetManager.getInstance(this);
        if (tailscaleAuthKey != null) {
            tailscaleAuthKey.setText(ts.getAuthKey());
        }
        if (tailscaleHostname != null) {
            tailscaleHostname.setText(ts.getHostname());
        }

        if (buttonTailscaleLogin != null) {
            buttonTailscaleLogin.setOnClickListener(v -> {
                saveTailscaleFieldsToManager();
                refreshTailscaleAccountAsync(true, true);
            });
        }
        if (buttonTailscaleLogout != null) {
            buttonTailscaleLogout.setOnClickListener(v -> {
                tailscalePollingActive = false;
                new Thread(() -> {
                    try {
                        TsnetManager.getInstance(aRDP.this).logoutAccount();
                    } catch (Exception ignored) {
                    }
                    tailscaleUiHandler.post(() -> {
                        tailscaleStatusText.setText(R.string.tailscale_status_not_logged_in);
                        buttonTailscaleLogout.setVisibility(View.GONE);
                        tailscalePeersLabel.setVisibility(View.GONE);
                        spinnerTailscalePeers.setVisibility(View.GONE);
                    });
                }).start();
            });
        }
        if (spinnerTailscalePeers != null) {
            spinnerTailscalePeers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= 0 && position < currentTailscalePeers.size()) {
                        TsnetManager.PeerInfo peer = currentTailscalePeers.get(position);
                        TsnetManager.getInstance(aRDP.this).setLastPeerIp(peer.ip);
                        TsnetManager.getInstance(aRDP.this).setLastPeerName(peer.name);
                        if (ipText != null && !peer.ip.isEmpty()) {
                            ipText.setText(peer.ip);
                        }
                        if (textNickname != null && (textNickname.getText() == null || textNickname.getText().toString().trim().isEmpty()) && !peer.name.isEmpty()) {
                            textNickname.setText(peer.name);
                        }
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
    }

    private void saveTailscaleFieldsToManager() {
        TsnetManager ts = TsnetManager.getInstance(this);
        if (tailscaleAuthKey != null && tailscaleAuthKey.getText() != null) {
            ts.setAuthKey(tailscaleAuthKey.getText().toString());
        }
        if (tailscaleHostname != null && tailscaleHostname.getText() != null) {
            ts.setHostname(tailscaleHostname.getText().toString());
        }
    }

    @SuppressLint("SetTextI18n")
    private void refreshTailscaleAccountAsync(final boolean begin, final boolean openBrowserIfNeeded) {
        if (tailscaleStatusText == null) {
            return;
        }
        if (begin) {
            tailscaleStatusText.setText("Tailscale: подключение к координатору...");
        }
        new Thread(() -> {
            try {
                final TsnetManager.AccountStatus status = TsnetManager.getInstance(aRDP.this).pollAccountStatus(begin);
                tailscaleUiHandler.post(() -> applyTailscaleAccountStatus(status, openBrowserIfNeeded));
            } catch (final Exception e) {
                tailscaleUiHandler.post(() -> {
                    String authKey = TsnetManager.getInstance(aRDP.this).getAuthKey();
                    if (!authKey.isEmpty()) {
                        tailscaleStatusText.setText("Tailscale: задан Auth Key (" + TsnetManager.getInstance(aRDP.this).getHostname() + ")");
                    } else if (begin) {
                        tailscaleStatusText.setText("Tailscale: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    @SuppressLint("SetTextI18n")
    private void applyTailscaleAccountStatus(TsnetManager.AccountStatus status, boolean openBrowserIfNeeded) {
        if (isFinishing() || tailscaleStatusText == null) {
            return;
        }
        if (status.running) {
            tailscalePollingActive = false;
            String loginInfo = status.login.isEmpty() ? "активен" : status.login;
            tailscaleStatusText.setText("Tailscale: подключен (" + loginInfo + ") — ПК онлайн: " + status.peers.size());
            buttonTailscaleLogout.setVisibility(View.VISIBLE);
            currentTailscalePeers.clear();
            currentTailscalePeers.addAll(status.peers);
            if (!currentTailscalePeers.isEmpty()) {
                tailscalePeersLabel.setVisibility(View.VISIBLE);
                spinnerTailscalePeers.setVisibility(View.VISIBLE);
                List<String> labels = new ArrayList<>();
                String currentIp = ipText != null && ipText.getText() != null ? ipText.getText().toString().trim() : "";
                String lastIp = TsnetManager.getInstance(this).getLastPeerIp();
                int selectedIdx = 0;
                for (int i = 0; i < currentTailscalePeers.size(); i++) {
                    TsnetManager.PeerInfo p = currentTailscalePeers.get(i);
                    labels.add(p.toString());
                    if (!currentIp.isEmpty() && currentIp.equals(p.ip)) {
                        selectedIdx = i;
                    } else if (currentIp.isEmpty() && !lastIp.isEmpty() && lastIp.equals(p.ip)) {
                        selectedIdx = i;
                    }
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.connection_list_entry, labels);
                adapter.setDropDownViewResource(R.layout.connection_list_entry);
                spinnerTailscalePeers.setAdapter(adapter);
                spinnerTailscalePeers.setSelection(selectedIdx);
            } else {
                tailscalePeersLabel.setVisibility(View.GONE);
                spinnerTailscalePeers.setVisibility(View.GONE);
            }
        } else {
            buttonTailscaleLogout.setVisibility(View.GONE);
            tailscalePeersLabel.setVisibility(View.GONE);
            spinnerTailscalePeers.setVisibility(View.GONE);
            String authKey = TsnetManager.getInstance(this).getAuthKey();
            if (!authKey.isEmpty() && !openBrowserIfNeeded) {
                tailscaleStatusText.setText("Tailscale: задан Auth Key (" + TsnetManager.getInstance(this).getHostname() + ")");
                return;
            }
            if (!status.authUrl.isEmpty()) {
                tailscaleStatusText.setText("Tailscale: ожидается вход в браузере...");
                if (openBrowserIfNeeded) {
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(status.authUrl));
                        startActivity(browserIntent);
                    } catch (Exception e) {
                        Snackbar.make(tailscaleStatusText, "URL входа: " + status.authUrl, Snackbar.LENGTH_LONG).show();
                    }
                    startTailscaleLoginPolling();
                }
            } else {
                tailscaleStatusText.setText(R.string.tailscale_status_not_logged_in);
            }
        }
    }

    private void startTailscaleLoginPolling() {
        if (tailscalePollingActive) {
            return;
        }
        tailscalePollingActive = true;
        tailscaleUiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!tailscalePollingActive || isFinishing()) {
                    return;
                }
                refreshTailscaleAccountAsync(false, false);
                if (tailscalePollingActive) {
                    tailscaleUiHandler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }

    private void initializeRdpSpecificConnectionParameters() {
        textUsername = findViewById(R.id.textUsername);
        rdpDomain = findViewById(R.id.rdpDomain);
    }

    private void initializeAdvancedSettings() {
        desktopScaleSeekBar = findViewById(R.id.desktopScaleSeekBar);
        desktopScaleProgressTextView = findViewById(R.id.desktopScaleProgressTextView);
        desktopScaleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar.getProgress() < MIN_DESKTOP_SCALE_PERCENTAGE) {
                    seekBar.setProgress(MIN_DESKTOP_SCALE_PERCENTAGE);
                }
                if (seekBar.getProgress() > MAX_DESKTOP_SCALE_PERCENTAGE) {
                    seekBar.setProgress(MAX_DESKTOP_SCALE_PERCENTAGE);
                }
                int val = (seekBar.getProgress() * (seekBar.getWidth() - 2 * seekBar.getThumbOffset())) / seekBar.getMax();
                desktopScaleProgressTextView.setText(seekBar.getProgress() + "%");
                desktopScaleProgressTextView.setX(seekBar.getX() + val + (float) seekBar.getThumbOffset() / 2);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        groupRemoteSoundType = findViewById(R.id.groupRemoteSoundType);
        groupRemoteSoundType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked && Utils.isFree(aRDP.this) && checkedId != R.id.radioRemoteSoundDisabled) {
                setRemoteSoundTypeFromSelected(Constants.REMOTE_SOUND_DISABLED);
                IntroTextDialog.showIntroTextIfNecessary(aRDP.this, database, true, true);
            }
        });
        checkboxEnableRecording = findViewById(R.id.checkboxEnableRecording);
        checkboxConsoleMode = findViewById(R.id.checkboxConsoleMode);
        checkboxRedirectSdCard = findViewById(R.id.checkboxRedirectSdCard);
        checkboxRemoteFx = findViewById(R.id.checkboxRemoteFx);
        checkboxDesktopBackground = findViewById(R.id.checkboxDesktopBackground);
        checkboxFontSmoothing = findViewById(R.id.checkboxFontSmoothing);
        checkboxDesktopComposition = findViewById(R.id.checkboxDesktopComposition);
        checkboxWindowContents = findViewById(R.id.checkboxWindowContents);
        checkboxMenuAnimation = findViewById(R.id.checkboxMenuAnimation);
        checkboxVisualStyles = findViewById(R.id.checkboxVisualStyles);
        checkboxEnableGfx = findViewById(R.id.checkboxEnableGfx);
        checkboxEnableGfxH264 = findViewById(R.id.checkboxEnableGfxH264);
        checkboxEnableGlyphCache = findViewById(R.id.checkboxEnableGlyphCache);
        checkboxPreferSendingUnicode = findViewById(R.id.checkboxPreferSendingUnicode);
    }

    private void initializeRdpResolutionSpinner() {
        // The geometry type and dimensions boxes.
        spinnerRdpGeometry = findViewById(R.id.spinnerRdpGeometry);
        spinnerRdpGeometry.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                selected.setRdpResType(itemIndex);
                setRemoteWidthAndHeight(RemoteClientLibConstants.RDP_GEOM_SELECT_CUSTOM);
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });
    }

    private void initializeRdpColorSpinner() {
        rdpColorArray = Utilities.Companion.toList(getResources().getStringArray(R.array.rdp_colors));
        spinnerRdpColor = findViewById(R.id.spinnerRdpColor);
        spinnerRdpColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                selected.setRdpColor(Integer.parseInt(rdpColorArray.get(itemIndex)));
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });
    }

    private void initializeRdpSecuritySpinner() {
        spinnerRdpSecurity = findViewById(R.id.spinnerRdpSecurity);
        spinnerRdpSecurity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                selected.setRdpSecurity(itemIndex); // See rdp_security string-array
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });
    }

    private void initializeRdpGatewaySettings() {
        layoutRdpGatewaySettings = findViewById(R.id.layoutRdpGatewaySettings);
        rdpGatewayEnabled = findViewById(R.id.rdpGatewayEnabled);
        rdpGatewayEnabled.setOnClickListener(v -> layoutRdpGatewaySettings.setVisibility(((ToggleButton) v).isChecked() ? View.VISIBLE : View.GONE));
        rdpGatewayHostname = findViewById(R.id.rdpGatewayHostname);
        rdpGatewayPort = findViewById(R.id.rdpGatewayPort);
        rdpGatewayUsername = findViewById(R.id.rdpGatewayUsername);
        rdpGatewayDomain = findViewById(R.id.rdpGatewayDomain);
        rdpGatewayPassword = findViewById(R.id.rdpGatewayPassword);
        checkboxKeepRdpGatewayPassword = findViewById(R.id.checkboxKeepRdpGatewayPassword);
    }

    @SuppressLint("SetTextI18n")
    public void updateViewFromSelected() {
        if (selected == null)
            return;
        super.updateViewFromSelected();
        setVisibilityOfTailscaleWidgets(selected.getConnectionType() == Constants.CONN_TYPE_TAILSCALE ? View.VISIBLE : View.GONE);
        setRdpSpecificSettingsFromSelected();
        setRdpColorSpinnerPositionFromSelected();
        setRdpSecuritySpinnerPositionFromSelected();
        setRdpGeometrySpinnerPositionFromSelected();
        setRemoteWidthAndHeight(RemoteClientLibConstants.RDP_GEOM_SELECT_CUSTOM);
        setRemoteSoundTypeFromSelected(selected.getRemoteSoundType());
        updateAdvancedSettingsViewsFromSelected();
    }

    @SuppressLint("SetTextI18n")
    private void setRdpSpecificSettingsFromSelected() {
        textUsername.setText(selected.getUserName());
        rdpDomain.setText(selected.getRdpDomain());
        boolean isRdpGatewayEnabled = selected.getRdpGatewayEnabled();
        rdpGatewayEnabled.setChecked(isRdpGatewayEnabled);
        layoutRdpGatewaySettings.setVisibility(isRdpGatewayEnabled ? View.VISIBLE : View.GONE);
        rdpGatewayHostname.setText(selected.getRdpGatewayHostname());
        rdpGatewayPort.setText(Integer.toString(selected.getRdpGatewayPort()));
        rdpGatewayUsername.setText(selected.getRdpGatewayUsername());
        rdpGatewayDomain.setText(selected.getRdpGatewayDomain());
        rdpGatewayPassword.setText(selected.getRdpGatewayPassword());
        checkboxKeepRdpGatewayPassword.setChecked(selected.getKeepRdpGatewayPassword());
    }

    private void setRdpGeometrySpinnerPositionFromSelected() {
        spinnerRdpGeometry.setSelection(selected.getRdpResType());
    }

    private void setRdpColorSpinnerPositionFromSelected() {
        spinnerRdpColor.setSelection(rdpColorArray.indexOf(String.valueOf(selected.getRdpColor())));
    }

    private void setRdpSecuritySpinnerPositionFromSelected() {
        spinnerRdpSecurity.setSelection(selected.getRdpSecurity());
    }

    private void updateAdvancedSettingsViewsFromSelected() {
        desktopScaleSeekBar.setProgress(selected.getDesktopScalePercentage());
        checkboxEnableRecording.setChecked(selected.getEnableRecording());
        checkboxConsoleMode.setChecked(selected.getConsoleMode());
        checkboxRedirectSdCard.setChecked(selected.getRedirectSdCard());
        checkboxRemoteFx.setChecked(selected.getRemoteFx());
        checkboxDesktopBackground.setChecked(selected.getDesktopBackground());
        checkboxFontSmoothing.setChecked(selected.getFontSmoothing());
        checkboxDesktopComposition.setChecked(selected.getDesktopComposition());
        checkboxWindowContents.setChecked(selected.getWindowContents());
        checkboxMenuAnimation.setChecked(selected.getMenuAnimation());
        checkboxVisualStyles.setChecked(selected.getVisualStyles());
        checkboxEnableGfx.setChecked(selected.getEnableGfx());
        checkboxEnableGfxH264.setChecked(selected.getEnableGfxH264());
        checkboxEnableGlyphCache.setChecked(selected.getEnableGlyphCache());
        checkboxPreferSendingUnicode.setChecked(selected.getPreferSendingUnicode());
    }

    protected void updateSelectedFromView() {
        if (selected == null) {
            return;
        }
        saveTailscaleFieldsToManager();
        super.updateSelectedFromView();
        updateSelectedRdpSpecificSettingsFromViews();
        updateSelectedRdpResolutionTypeFromRdpGeometrySpinnerPosition();
        updateSelectedAdvancedSettingsFromViews();
    }

    private void updateSelectedRdpSpecificSettingsFromViews() {
        selected.setUserName(textUsername.getText().toString());
        selected.setRdpDomain(rdpDomain.getText().toString());
        selected.setRdpGatewayEnabled(rdpGatewayEnabled.isChecked());
        selected.setRdpGatewayHostname(rdpGatewayHostname.getText().toString());
        try {
            selected.setRdpGatewayPort(Integer.parseInt(rdpGatewayPort.getText().toString()));
        } catch (NumberFormatException nfe) {
            debugLogAndPrintStacktrace(nfe);
        }
        selected.setRdpGatewayUsername(rdpGatewayUsername.getText().toString());
        selected.setRdpGatewayDomain(rdpGatewayDomain.getText().toString());
        selected.setRdpGatewayPassword(rdpGatewayPassword.getText().toString());
        selected.setKeepRdpGatewayPassword(checkboxKeepRdpGatewayPassword.isChecked());
    }

    private void updateSelectedRdpResolutionTypeFromRdpGeometrySpinnerPosition() {
        selected.setRdpResType(spinnerRdpGeometry.getSelectedItemPosition());
    }

    private void updateSelectedAdvancedSettingsFromViews() {
        selected.setDesktopScalePercentage(desktopScaleSeekBar.getProgress());
        setRemoteSoundTypeFromView(groupRemoteSoundType);
        selected.setEnableRecording(checkboxEnableRecording.isChecked());
        selected.setConsoleMode(checkboxConsoleMode.isChecked());
        selected.setRedirectSdCard(checkboxRedirectSdCard.isChecked());
        selected.setRemoteFx(checkboxRemoteFx.isChecked());
        selected.setDesktopBackground(checkboxDesktopBackground.isChecked());
        selected.setFontSmoothing(checkboxFontSmoothing.isChecked());
        selected.setDesktopComposition(checkboxDesktopComposition.isChecked());
        selected.setWindowContents(checkboxWindowContents.isChecked());
        selected.setMenuAnimation(checkboxMenuAnimation.isChecked());
        selected.setVisualStyles(checkboxVisualStyles.isChecked());
        selected.setEnableGfx(checkboxEnableGfx.isChecked());
        selected.setEnableGfxH264(checkboxEnableGfxH264.isChecked());
        selected.setEnableGlyphCache(checkboxEnableGlyphCache.isChecked());
        selected.setPreferSendingUnicode(checkboxPreferSendingUnicode.isChecked());
    }

    /**
     * Automatically linked with android:onClick in the layout.
     */
    public void toggleEnableRecording(View view) {
        CompoundButton b = (CompoundButton) view;
        if (Utils.isFree(this)) {
            IntroTextDialog.showIntroTextIfNecessary(this, database, true, true);
            b.setChecked(false);
        } else {
            AudioPermissionsManager.requestPermissions(this, AudioPermissionGroups.RECORD_AND_MODIFY_AUDIO, true);
        }
        selected.setEnableRecording(b.isChecked());
    }

    /**
     * Automatically linked with android:onClick in the layout.
     */
    public void remoteSoundTypeToggled(View view) {
        if (Utils.isFree(this)) {
            IntroTextDialog.showIntroTextIfNecessary(this, database, true, true);
        }
    }

    /**
     * Sets the remote sound type in the settings from the specified parameter.
     */
    public void setRemoteSoundTypeFromView(View view) {
        MaterialButtonToggleGroup g = (MaterialButtonToggleGroup) view;
        int id = g.getCheckedButtonId();
        int soundType = Constants.REMOTE_SOUND_DISABLED;
        if (id == R.id.radioRemoteSoundOnServer) {
            soundType = Constants.REMOTE_SOUND_ON_SERVER;
        } else if (id == R.id.radioRemoteSoundOnDevice) {
            soundType = Constants.REMOTE_SOUND_ON_DEVICE;
        }
        selected.setRemoteSoundType(soundType);
    }

    public void setRemoteSoundTypeFromSelected(int type) {
        int typeToSet = type;
        if (Utils.isFree(this)) {
            typeToSet = Constants.REMOTE_SOUND_DISABLED;
        }

        int id = switch (typeToSet) {
            case Constants.REMOTE_SOUND_DISABLED -> R.id.radioRemoteSoundDisabled;
            case Constants.REMOTE_SOUND_ON_DEVICE -> R.id.radioRemoteSoundOnDevice;
            case Constants.REMOTE_SOUND_ON_SERVER -> R.id.radioRemoteSoundOnServer;
            default -> 0;
        };
        groupRemoteSoundType.check(id);
    }

    public void save(View item) {
        save(R.string.rdp_server_empty);
    }

    public void save(MenuItem item) {
        save(R.string.rdp_server_empty);
    }
}
