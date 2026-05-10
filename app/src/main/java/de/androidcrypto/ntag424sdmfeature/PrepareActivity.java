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
import android.widget.TextView;

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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PrepareActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private static final String TAG = PrepareActivity.class.getSimpleName();
    private TextInputEditText etProvisionApiUrl, etUserIdentifier, output;

    private DnaCommunicator dnaC = new DnaCommunicator();
    private NfcAdapter mNfcAdapter;
    private IsoDep isoDep;
    private byte[] tagIdByte;

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

        etProvisionApiUrl = findViewById(R.id.etProvisionApiUrl);
        etUserIdentifier = findViewById(R.id.etUserIdentifier);
        output = findViewById(R.id.etOutput);

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
                runWorker();
            } catch (IOException e) {
                writeToUiAppend(output, "Connection error: " + e.getMessage());
            }
        }
    }

    private void runWorker() {
        final String apiUrl = etProvisionApiUrl.getText().toString();
        final String userId = etUserIdentifier.getText().toString();

        if (TextUtils.isEmpty(apiUrl) || TextUtils.isEmpty(userId)) {
            writeToUiAppend(output, "Error: API URL and User ID are required.");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                dnaC = new DnaCommunicator();
                dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
                dnaC.beginCommunication();

                // 1. Get the real Tag UID (The unique identity of the chip)
                // This is where the UID is determined
                byte[] realTagUid = GetCardUid.run(dnaC);
                String uidHex = Utils.bytesToHex(realTagUid);
                writeToUiAppend(output, "UID read: " + uidHex);

                // 2. Fetch Keys and Finalized URL from Backend
                writeToUiAppend(output, "Fetching configuration from server...");
                JSONObject response = fetchKeysFromServer(apiUrl, uidHex, userId);
                if (response == null) {
                    writeToUiAppend(output, "Failed to get response from server.");
                    return;
                }

                byte[] key0 = Utils.hexStringToByteArray(response.getString("key0"));
                byte[] key4 = Utils.hexStringToByteArray(response.getString("key4"));
                
                // Server provides the exact URL to write to the tag
                String finalUrl = response.getString("url");
                writeToUiAppend(output, "URL to write: " + finalUrl);

                // 3. Authenticate with Factory Key
                boolean success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                if (!success && dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                }

                if (!success) {
                    writeToUiAppend(output, "Authentication failed. Tag might already be provisioned.");
                    return;
                }

                // 4. Provision File 01 (CC)
                WriteData.run(dnaC, CC_FILE_NUMBER, NDEF_FILE_01_CAPABILITY_CONTAINER_R, 0);
                FileSettings fs01 = GetFileSettings.run(dnaC, CC_FILE_NUMBER);
                fs01.readPerm = ACCESS_EVERYONE;
                ChangeFileSettings.run(dnaC, CC_FILE_NUMBER, fs01);

                // 5. Setup SDM and Write URL to File 02
                SDMSettings sdmSettings = new SDMSettings();
                sdmSettings.sdmEnabled = true;
                sdmSettings.sdmMetaReadPerm = ACCESS_KEY0; 
                sdmSettings.sdmFileReadPerm = ACCESS_KEY4;
                sdmSettings.sdmOptionUid = true;
                sdmSettings.sdmOptionReadCounter = true;

                NdefTemplateMaster master = new NdefTemplateMaster();
                byte[] ndefRecord = master.generateNdefTemplateFromUrlString(finalUrl, sdmSettings);
                WriteData.run(dnaC, NDEF_FILE_NUMBER, ndefRecord, 0);

                // 6. Update Application Keys (Change Key 4 first, then Key 0)
                ChangeKey.run(dnaC, ACCESS_KEY4, Ntag424.FACTORY_KEY, key4, APPLICATION_KEY_VERSION_NEW);
                writeToUiAppend(output, "Key 4 updated.");
                
                ChangeKey.run(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY, key0, APPLICATION_KEY_VERSION_NEW);
                writeToUiAppend(output, "Key 0 (Admin) updated.");

                // 7. Enable SDM on File 02
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
        });
        worker.start();
    }

    private JSONObject fetchKeysFromServer(String apiUrl, String uid, String userId) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setDoOutput(true);

            JSONObject jsonInput = new JSONObject();
            jsonInput.put("uid", uid);
            jsonInput.put("user_id", userId);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInput.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    return new JSONObject(response.toString());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Network error", e);
        }
        return null;
    }

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
