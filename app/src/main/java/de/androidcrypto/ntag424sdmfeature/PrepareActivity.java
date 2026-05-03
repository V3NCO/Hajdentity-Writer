package de.androidcrypto.ntag424sdmfeature;

import static net.bplearning.ntag424.CommandResult.PERMISSION_DENIED;
import static net.bplearning.ntag424.constants.Ntag424.CC_FILE_NUMBER;
import static net.bplearning.ntag424.constants.Ntag424.NDEF_FILE_NUMBER;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_EVERYONE;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY0;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY4;

import static de.androidcrypto.ntag424sdmfeature.Constants.APPLICATION_KEY_VERSION_NEW;
import static de.androidcrypto.ntag424sdmfeature.Constants.NDEF_FILE_01_CAPABILITY_CONTAINER_R;

import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;

import net.bplearning.ntag424.DnaCommunicator;
import net.bplearning.ntag424.command.ChangeFileSettings;
import net.bplearning.ntag424.command.ChangeKey;
import net.bplearning.ntag424.command.FileSettings;
import net.bplearning.ntag424.command.GetCardUid;
import net.bplearning.ntag424.command.GetFileSettings;
import net.bplearning.ntag424.command.WriteData;
import net.bplearning.ntag424.constants.Ntag424;
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode;
import net.bplearning.ntag424.encryptionmode.LRPEncryptionMode;
import net.bplearning.ntag424.sdm.NdefTemplateMaster;
import net.bplearning.ntag424.sdm.SDMSettings;

import java.io.IOException;

public class PrepareActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private static final String TAG = PrepareActivity.class.getSimpleName();
    private TextInputEditText etUserIdentifier, output;
    private TextInputEditText etFinalUrlInput, etKey0Input, etKey4Input;

    private DnaCommunicator dnaC = new DnaCommunicator();
    private NfcAdapter mNfcAdapter;
    private IsoDep isoDep;
    private byte[] tagIdByte;
    private String lastSecureUid = null;
    private Button btnCopyUid;

     @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_prepare);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar myToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(myToolbar);

        etUserIdentifier = findViewById(R.id.etUserIdentifier);
        etFinalUrlInput = findViewById(R.id.etFinalUrl);
        etKey0Input = findViewById(R.id.etKey0Input);
        etKey4Input = findViewById(R.id.etKey4Input);
        output = findViewById(R.id.etOutput);
        // Resolve btnCopyUid dynamically in case the layout doesn't define it (avoids compile-time R reference)
        try {
            int copyUidId = getResources().getIdentifier("btnCopyUid", "id", getPackageName());
            if (copyUidId != 0) {
                btnCopyUid = (Button) findViewById(copyUidId);
            } else {
                btnCopyUid = null;
            }
        } catch (Exception ex) {
            btnCopyUid = null;
        }
        if (btnCopyUid != null) {
            btnCopyUid.setEnabled(false);
            btnCopyUid.setOnClickListener(v -> {
                String toCopy = lastSecureUid;
                if (toCopy == null && tagIdByte != null) {
                    toCopy = Utils.bytesToHex(tagIdByte);
                }
                if (toCopy != null) {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("ntag_uid", toCopy);
                        clipboard.setPrimaryClip(clip);
                        writeToUiAppend(output, "UID copied to clipboard: " + toCopy);
                    } catch (Exception e) {
                        writeToUiAppend(output, "Failed to copy UID: " + e.getMessage());
                    }
                } else {
                    writeToUiAppend(output, "No UID available to copy.");
                }
            });
        }

        // Add diagnostic buttons programmatically so you can dump status and try auth attempts manually
        try {
            View root = findViewById(R.id.main);
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                LinearLayout diag = new LinearLayout(this);
                diag.setOrientation(LinearLayout.HORIZONTAL);

                Button btnDump = new Button(this);
                btnDump.setText("Dump Status");
                btnDump.setOnClickListener(b -> {
                    try {
                        if (dnaC != null && dnaC.getLastCommandResult() != null) {
                            int st1 = dnaC.getLastCommandResult().status1;
                            int st2 = dnaC.getLastCommandResult().status2;
                            writeToUiAppend(output, String.format("[DUMP] status1=0x%02X status2=0x%02X", st1, st2));
                            int combined = ((st1 & 0xFF) << 8) | (st2 & 0xFF);
                            writeToUiAppend(output, String.format("[DUMP] combined=0x%04X", combined));
                        } else {
                            writeToUiAppend(output, "[DUMP] No LastCommandResult available.");
                        }
                    } catch (Exception ex) {
                        writeToUiAppend(output, "[DUMP] Error: " + ex.getMessage());
                    }
                });

                Button btnFac = new Button(this);
                btnFac.setText("Factory Auth");
                btnFac.setOnClickListener(b -> {
                    new Thread(() -> {
                        try {
                            if (isoDep == null) {
                                writeToUiAppend(output, "Factory Auth: bring tag close first (isoDep not initialized)");
                                return;
                            }
                            // ensure communicator transceiver is set
                            try {
                                dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
                                dnaC.beginCommunication();
                            } catch (Exception initEx) {
                                // ignore if already initialized, but log
                                writeToUiAppend(output, "Factory Auth: communicator init warning: " + initEx.getMessage());
                            }
                            writeToUiAppend(output, "Factory Auth: attempting AESEncryptionMode.authenticateEV2...");
                            boolean a = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                            writeToUiAppend(output, "Factory Auth AESEncryptionMode returned: " + a);
                            if (!a) {
                                boolean bres = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                                writeToUiAppend(output, "Factory Auth LRPEncryptionMode returned: " + bres);
                            }
                            if (dnaC != null && dnaC.getLastCommandResult() != null) {
                                int st1 = dnaC.getLastCommandResult().status1;
                                int st2 = dnaC.getLastCommandResult().status2;
                                writeToUiAppend(output, String.format("Factory Auth LastCommandResult status1=0x%02X status2=0x%02X", st1, st2));
                            }
                        } catch (Exception ex) {
                            writeToUiAppend(output, "Factory Auth error: " + ex.getMessage());
                        }
                    }).start();
                });

                Button btnTryK0 = new Button(this);
                btnTryK0.setText("Try Key0 Auth");
                btnTryK0.setOnClickListener(b -> {
                    new Thread(() -> {
                        try {
                            if (isoDep == null) {
                                writeToUiAppend(output, "Try Key0 Auth: bring tag close first (isoDep not initialized)");
                                return;
                            }
                            // ensure communicator transceiver is set
                            try {
                                dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
                                dnaC.beginCommunication();
                            } catch (Exception initEx) {
                                writeToUiAppend(output, "Try Key0 Auth: communicator init warning: " + initEx.getMessage());
                            }
                            String providedKey0Hex = etKey0Input.getText() != null ? etKey0Input.getText().toString().trim() : "";
                            if (providedKey0Hex.length() != 32) {
                                writeToUiAppend(output, "Try Key0 Auth: provide a 32-char hex Key0 first.");
                                return;
                            }
                            byte[] providedKey0 = Utils.hexStringToByteArray(providedKey0Hex);
                            writeToUiAppend(output, "Trying provided Key0 with AESEncryptionMode...");
                            boolean a = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, providedKey0);
                            writeToUiAppend(output, "Provided Key0 AESEncryptionMode returned: " + a);
                            if (!a) {
                                boolean bres = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, providedKey0);
                                writeToUiAppend(output, "Provided Key0 LRPEncryptionMode returned: " + bres);
                            }
                            if (dnaC != null && dnaC.getLastCommandResult() != null) {
                                int st1 = dnaC.getLastCommandResult().status1;
                                int st2 = dnaC.getLastCommandResult().status2;
                                writeToUiAppend(output, String.format("Provided Key0 LastCommandResult status1=0x%02X status2=0x%02X", st1, st2));
                            }
                        } catch (Exception ex) {
                            writeToUiAppend(output, "Try Key0 Auth error: " + ex.getMessage());
                        }
                    }).start();
                });

                Button btnReadUid = new Button(this);
                btnReadUid.setText("Read Secure UID");
                btnReadUid.setOnClickListener(b -> {
                    new Thread(() -> {
                        try {
                            if (isoDep == null) {
                                writeToUiAppend(output, "Read Secure UID: bring tag close first (isoDep not initialized)");
                                return;
                            }
                            try {
                                dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
                                dnaC.beginCommunication();
                            } catch (Exception initEx) {
                                writeToUiAppend(output, "Read Secure UID: communicator init warning: " + initEx.getMessage());
                            }
                            writeToUiAppend(output, "Read Secure UID: attempting GetCardUid.run...");
                            try {
                                byte[] realTagUid = GetCardUid.run(dnaC);
                                String uidHex = Utils.bytesToHex(realTagUid);
                                writeToUiAppend(output, "Secure UID: " + uidHex);
                                lastSecureUid = uidHex;
                                runOnUiThread(() -> { if (btnCopyUid != null) btnCopyUid.setEnabled(true); });
                            } catch (Exception e) {
                                writeToUiAppend(output, "GetCardUid failed: " + e.getMessage());
                                if (dnaC != null && dnaC.getLastCommandResult() != null) {
                                    int st1 = dnaC.getLastCommandResult().status1;
                                    int st2 = dnaC.getLastCommandResult().status2;
                                    writeToUiAppend(output, String.format("GetCardUid LastCommandResult status1=0x%02X status2=0x%02X", st1, st2));
                                }
                            }
                        } catch (Exception ex) {
                            writeToUiAppend(output, "Read Secure UID error: " + ex.getMessage());
                        }
                    }).start();
                });

                diag.addView(btnDump);
                diag.addView(btnFac);
                diag.addView(btnTryK0);
                diag.addView(btnReadUid);
                vg.addView(diag);
            }
        } catch (Exception ignored) {}

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
    }

    private void writeToUiAppend(TextView textView, String message) {
        runOnUiThread(() -> {
            String oldString = textView.getText().toString();
            if (TextUtils.isEmpty(oldString) || oldString.equals("Tap a tag to start...")) {
                textView.setText(message);
            } else {
                String newString = message + "\n" + oldString;
                textView.setText(newString);
            }
        });
        // Also log all operation messages to Logcat so adb logcat can capture them
        try {
            Log.d(TAG, message);
        } catch (Exception ignored) {}
    }

    private void vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(50, 10));
        } else {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(50);
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        writeToUiAppend(output, "NFC tag discovered");
        isoDep = IsoDep.get(tag);
        if (isoDep != null) {
            try {
                vibrateShort();
                runOnUiThread(() -> output.setText(""));
                isoDep.connect();
                tagIdByte = tag.getId();
                writeToUiAppend(output, "Tag ID: " + Utils.bytesToHex(tagIdByte));
                // Prompt user for URL and keys, then provision the tag
                runWorker();
            } catch (IOException e) {
                writeToUiAppend(output, "Connection error: " + e.getMessage());
            }
        }
    }

    private void runWorker() {
        // Read values from the persistent UI fields and start provisioning.
        final String finalUrl = etFinalUrlInput.getText() != null ? etFinalUrlInput.getText().toString().trim() : "";
        final String userId = etUserIdentifier.getText() != null ? etUserIdentifier.getText().toString().trim() : "";
        final String key0Hex = etKey0Input.getText() != null ? etKey0Input.getText().toString().trim() : "";
        final String key4Hex = etKey4Input.getText() != null ? etKey4Input.getText().toString().trim() : "";

        if (finalUrl.isEmpty()) {
            writeToUiAppend(output, "Error: Final URL (etFinalUrl) is required.");
            return;
        }
        if (key0Hex.length() != 32 || key4Hex.length() != 32) {
            writeToUiAppend(output, "Error: key0 and key4 must be 32 hex characters (16 bytes).");
            return;
        }

        byte[] key0;
        byte[] key4;
        try {
            key0 = Utils.hexStringToByteArray(key0Hex);
            key4 = Utils.hexStringToByteArray(key4Hex);
        } catch (Exception e) {
            writeToUiAppend(output, "Error: invalid key hex.");
            return;
        }

        Thread worker = new Thread(() -> runProvisionWorker(key0, key4, finalUrl, userId));
        worker.start();
    }

    private void runProvisionWorker(byte[] key0, byte[] key4, String finalUrl, String userId) {
        try {
            dnaC = new DnaCommunicator();
            dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
            dnaC.beginCommunication();

            // 1. Get the real Tag UID (The unique identity of the chip)
            String uidHex;
            boolean gotUid = false;
            try {
                byte[] realTagUid = GetCardUid.run(dnaC);
                uidHex = Utils.bytesToHex(realTagUid);
                writeToUiAppend(output, "UID read: " + uidHex);
                lastSecureUid = uidHex;
                runOnUiThread(() -> {
                    if (btnCopyUid != null) btnCopyUid.setEnabled(true);
                });
                gotUid = true;
            } catch (Exception e) {
                // More verbose diagnostics when GetCardUid fails
                writeToUiAppend(output, "GetCardUid exception: " + e.getMessage());
                try {
                    if (dnaC != null && dnaC.getLastCommandResult() != null) {
                        int st1 = dnaC.getLastCommandResult().status1;
                        int st2 = dnaC.getLastCommandResult().status2;
                        writeToUiAppend(output, String.format("LastCommandResult status1=0x%02X status2=0x%02X", st1, st2));
                        int combined = ((st1 & 0xFF) << 8) | (st2 & 0xFF);
                        writeToUiAppend(output, String.format("Combined status=0x%04X", combined));
                        // Check for the known permission-denied bubble 0x917E (status1=0x91, status2=0x7E)
                        if (combined == 0x917E || st1 == PERMISSION_DENIED || st2 == PERMISSION_DENIED) {
                            writeToUiAppend(output, "GetCardUid returned PERMISSION_DENIED (917E). Trying factory authentication and retry...");
                            boolean auth = false;
                            try {
                                auth = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                                writeToUiAppend(output, "AESEncryptionMode.authenticateEV2 returned: " + auth);
                            } catch (Exception ae) {
                                writeToUiAppend(output, "AESEncryptionMode.authenticateEV2 threw: " + ae.getMessage());
                            }
                            if (!auth) {
                                try {
                                    auth = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                                    writeToUiAppend(output, "LRPEncryptionMode.authenticateLRP returned: " + auth);
                                } catch (Exception le) {
                                    writeToUiAppend(output, "LRPEncryptionMode.authenticateLRP threw: " + le.getMessage());
                                }
                            }
                            if (auth) {
                                try {
                                    byte[] realTagUid2 = GetCardUid.run(dnaC);
                                    uidHex = Utils.bytesToHex(realTagUid2);
                                    writeToUiAppend(output, "UID read after factory auth: " + uidHex);
                                    lastSecureUid = uidHex;
                                    runOnUiThread(() -> {
                                        if (btnCopyUid != null) btnCopyUid.setEnabled(true);
                                    });
                                    gotUid = true;
                                } catch (Exception e2) {
                                    writeToUiAppend(output, "GetCardUid still failed after factory auth: " + e2.getMessage());
                                }
                            } else {
                                writeToUiAppend(output, "Factory authentication failed or not permitted.");
                            }
                        } else {
                            writeToUiAppend(output, "GetCardUid failed with status2 != PERMISSION_DENIED. Message: " + e.getMessage());
                        }
                    } else {
                        writeToUiAppend(output, "GetCardUid failed and no LastCommandResult available: " + e.getMessage());
                    }
                } catch (Exception inner) {
                    writeToUiAppend(output, "Error while handling GetCardUid failure: " + inner.getMessage());
                }
            }

            // If we didn't get the UID yet, attempt to use an operator-provided Key0 (if entered)
            if (!gotUid) {
                try {
                    String providedKey0Hex = etKey0Input.getText() != null ? etKey0Input.getText().toString().trim() : "";
                    if (providedKey0Hex.length() == 32) {
                        writeToUiAppend(output, "Attempting authentication with provided Key0 to read secure UID...");
                        try {
                            byte[] providedKey0 = Utils.hexStringToByteArray(providedKey0Hex);
                            boolean auth2 = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, providedKey0);
                            if (!auth2 && dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
                                auth2 = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, providedKey0);
                            }
                            if (auth2) {
                                try {
                                    byte[] realTagUid3 = GetCardUid.run(dnaC);
                                    uidHex = Utils.bytesToHex(realTagUid3);
                                    writeToUiAppend(output, "UID read after provided Key0 auth: " + uidHex);
                                    lastSecureUid = uidHex;
                                    runOnUiThread(() -> {
                                        if (btnCopyUid != null) btnCopyUid.setEnabled(true);
                                    });
                                    gotUid = true;
                                } catch (Exception e3) {
                                    writeToUiAppend(output, "GetCardUid failed after provided Key0 auth: " + e3.getMessage());
                                }
                            } else {
                                writeToUiAppend(output, "Provided Key0 did not authenticate.");
                            }
                        } catch (Exception ex) {
                            writeToUiAppend(output, "Invalid provided Key0 hex: " + ex.getMessage());
                        }
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            }

            if (!gotUid) {
                // Fallback: show the Android-reported tag id (not authoritative for diversification)
                if (tagIdByte != null) {
                    String androidId = Utils.bytesToHex(tagIdByte);
                    writeToUiAppend(output, "Android Tag ID (fallback): " + androidId);
                    // expose fallback id to the UI and enable copy button so user can quickly copy it
                    lastSecureUid = androidId; // NOTE: this is a fallback and NOT the secure UID used for diversification
                    runOnUiThread(() -> {
                        if (btnCopyUid != null) btnCopyUid.setEnabled(true);
                    });
                    // copy to clipboard to make it easy to paste into your web UI
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("ntag_uid", androidId);
                        clipboard.setPrimaryClip(clip);
                        writeToUiAppend(output, "Android Tag ID copied to clipboard. Use this only for lookup; it's NOT the secure UID used for diversification.");
                    } catch (Exception ce) {
                        writeToUiAppend(output, "Failed to copy Android Tag ID to clipboard: " + ce.getMessage());
                    }
                }
                // Abort provisioning since we cannot get the secure UID
                return;
            }

            // 2. Authenticate with Factory Key
            boolean success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
            if (!success && dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
                success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
            }

            if (!success) {
                writeToUiAppend(output, "Authentication failed. Tag might already be provisioned.");
                return;
            }

            // 3. Provision File 01 (CC)
            WriteData.run(dnaC, CC_FILE_NUMBER, NDEF_FILE_01_CAPABILITY_CONTAINER_R, 0);
            FileSettings fs01 = GetFileSettings.run(dnaC, CC_FILE_NUMBER);
            fs01.readPerm = ACCESS_EVERYONE;
            ChangeFileSettings.run(dnaC, CC_FILE_NUMBER, fs01);

            // 4. Setup SDM and Write URL to File 02
            SDMSettings sdmSettings = new SDMSettings();
            sdmSettings.sdmEnabled = true;
            sdmSettings.sdmMetaReadPerm = ACCESS_KEY0;
            sdmSettings.sdmFileReadPerm = ACCESS_KEY4;
            sdmSettings.sdmOptionUid = true;
            sdmSettings.sdmOptionReadCounter = true;

            NdefTemplateMaster master = new NdefTemplateMaster();
            byte[] ndefRecord = master.generateNdefTemplateFromUrlString(finalUrl, sdmSettings);
            WriteData.run(dnaC, NDEF_FILE_NUMBER, ndefRecord, 0);

            // 5. Update Application Keys (Change Key 4 first, then Key 0)
            ChangeKey.run(dnaC, ACCESS_KEY4, Ntag424.FACTORY_KEY, key4, APPLICATION_KEY_VERSION_NEW);
            writeToUiAppend(output, "Key 4 updated.");

            ChangeKey.run(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY, key0, APPLICATION_KEY_VERSION_NEW);
            writeToUiAppend(output, "Key 0 (Admin) updated.");

            // CRITICAL: Re-authenticate with the NEW Key 0 after changing it
            success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, key0);
            if (!success && dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
                success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, key0);
            }
            if (!success) {
                writeToUiAppend(output, "Re-authentication with new Key 0 failed. Aborting.");
                return;
            }
            writeToUiAppend(output, "Re-authenticated with new Key 0.");

            // 6. Enable SDM on File 02
            FileSettings fs02 = GetFileSettings.run(dnaC, NDEF_FILE_NUMBER);
            fs02.sdmSettings = sdmSettings;
            fs02.readPerm = ACCESS_EVERYONE;
            ChangeFileSettings.run(dnaC, NDEF_FILE_NUMBER, fs02);

            writeToUiAppend(output, "SUCCESS: Tag provisioned for " + userId);
        } catch (Exception e) {
            writeToUiAppend(output, "Error: " + e.getMessage());
            Log.e(TAG, "Worker error", e);
        }
        vibrateShort();
    }

    // Network helper methods removed to ensure the app never connects to the internet.

    @Override
    protected void onResume() {
        super.onResume();
        if (mNfcAdapter != null) {
            Bundle options = new Bundle();
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
            mNfcAdapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, options);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mNfcAdapter != null) mNfcAdapter.disableReaderMode(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_return_home, menu);
        MenuItem mReturnHome = menu.findItem(R.id.action_return_home);
        mReturnHome.setOnMenuItemClickListener(item -> {
            startActivity(new Intent(PrepareActivity.this, MainActivity.class));
            finish();
            return false;
        });
        return super.onCreateOptionsMenu(menu);
    }
}