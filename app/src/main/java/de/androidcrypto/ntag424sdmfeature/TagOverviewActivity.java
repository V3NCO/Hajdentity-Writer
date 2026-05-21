package de.androidcrypto.ntag424sdmfeature;

import static net.bplearning.ntag424.CommandResult.PERMISSION_DENIED;
import static net.bplearning.ntag424.constants.Ntag424.CC_FILE_NUMBER;
import static net.bplearning.ntag424.constants.Ntag424.DATA_FILE_NUMBER;
import static net.bplearning.ntag424.constants.Ntag424.NDEF_FILE_NUMBER;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_EVERYONE;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY0;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY1;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY2;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY3;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY4;

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
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.bplearning.ntag424.DnaCommunicator;
import net.bplearning.ntag424.command.FileSettings;
import net.bplearning.ntag424.command.GetCardUid;
import net.bplearning.ntag424.command.GetFileSettings;
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode;
import net.bplearning.ntag424.encryptionmode.LRPEncryptionMode;
import net.bplearning.ntag424.exception.ProtocolException;

import java.io.IOException;

public class TagOverviewActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private static final String TAG = TagOverviewActivity.class.getSimpleName();
    private com.google.android.material.textfield.TextInputEditText output;
    private DnaCommunicator dnaC = new DnaCommunicator();
    private NfcAdapter mNfcAdapter;
    private IsoDep isoDep;
    private EditText o_key0input, o_key3input, o_key4input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_tag_overview);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar myToolbar = findViewById(R.id.main_toolbar);
        o_key0input = findViewById(R.id.o_key0input);
        o_key3input = findViewById(R.id.o_key3input);
        o_key4input = findViewById(R.id.o_key4input);
        setSupportActionBar(myToolbar);

        output = findViewById(R.id.etOutput);
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
    }

    /**
     * section for UI handling
     */

    private void writeToUiAppend(TextView textView, String message) {
        runOnUiThread(() -> {
            String oldString = textView.getText().toString();
            if (TextUtils.isEmpty(oldString)) {
                textView.setText(message);
            } else {
                String newString = message + "\n" + oldString;
                textView.setText(newString);
                System.out.println(message);
            }
        });
    }

    private void vibrateShort() {
        // Make a Sound
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(50, 10));
        } else {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(50);
        }
    }

    /**
     * NFC tag handling section
     * These methods are running in another thread when a card is discovered and
     * cannot direct interact with the UI Thread.
     * Use `runOnUiThread` method to change the UI from these methods
     */

    @Override
    public void onTagDiscovered(Tag tag) {

        writeToUiAppend(output, "NFC tag discovered");

        isoDep = null;
        try {
            isoDep = IsoDep.get(tag);
            if (isoDep != null) {
                // Make a Vibration
                vibrateShort();

                runOnUiThread(() -> output.setText(""));

                isoDep.connect();
                if (!isoDep.isConnected()) {
                    writeToUiAppend(output, "Could not connect to the tag, aborted");
                    isoDep.close();
                    return;
                }

                // get tag ID
                byte[] tagIdByte = tag.getId();
                writeToUiAppend(output, "Tag ID: " + Utils.bytesToHex(tagIdByte));
                Log.d(TAG, "tag id: " + Utils.bytesToHex(tagIdByte));
                writeToUiAppend(output, "NFC tag connected");

                runWorker();
            }

        } catch (IOException e) {
            writeToUiAppend(output, "ERROR: IOException " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            writeToUiAppend(output, "ERROR: Exception " + e.getMessage());
            e.printStackTrace();
        }

    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mNfcAdapter != null) {

            Bundle options = new Bundle();
            // Work around for some broken Nfc firmware implementations that poll the card too fast
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);

            // Enable ReaderMode for NFC A card type and disable platform sounds
            // the option NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK is set
            // so the reader won't try to get a NDEF message
            mNfcAdapter.enableReaderMode(this,
                    this,
                    NfcAdapter.FLAG_READER_NFC_A |
                            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK |
                            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    options);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mNfcAdapter != null) {
            mNfcAdapter.disableReaderMode(this);
        }
    }

    private void runWorker() {
        Log.d(TAG, "Tag Overview Activity Worker");
        Thread worker = new Thread(() -> {
            boolean success;
            try {
                dnaC = new DnaCommunicator();
                byte[] key0 = Utils.hexStringToByteArray(o_key0input.getText().toString());
                byte[] key3 = Utils.hexStringToByteArray(o_key3input.getText().toString());
                byte[] key4 = Utils.hexStringToByteArray(o_key4input.getText().toString());
                try {
                    dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
                } catch (NullPointerException npe) {
                    writeToUiAppend(output, "Please tap a tag before running any tests, aborted");
                    return;
                }
                dnaC.setLogger((info) -> Log.d(TAG, "Communicator: " + info));
                dnaC.beginCommunication();

                /*
                 * These steps are running - this activity tries to get an overview about the tag
                 *
                 * assuming that all keys are 'default' keys filled with 16 00h values
                 * 1) Authenticate with Application Key 00h in AES mode
                 * 2) If the authentication in AES mode fails try to authenticate in LRP mode
                 * 3) Write a URL template to file 02 with Uid and/or Counter plus CMAC
                 * 4) Get existing file settings for file 02
                 * 5) Save the modified file settings back to the tag
                 */

                writeToUiAppend(output, Constants.DOUBLE_DIVIDER);
                // authentication
                boolean isLrpAuthenticationMode = false;

                writeToUiAppend(output, "Authentication with FACTORY ACCESS_KEY 0");
                // what happens when we choose the wrong authentication scheme ?
                success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, key0);
                if (success) {
                    writeToUiAppend(output, "AES Authentication SUCCESS");
                } else {
                    // if the returnCode is '919d' = permission denied the tag is in LRP mode authentication
                    if (dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
                        // try to run the LRP authentication
                        success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, key0);
                        if (success) {
                            writeToUiAppend(output, "LRP Authentication SUCCESS");
                            isLrpAuthenticationMode = true;
                        } else {
                            writeToUiAppend(output, "LRP Authentication FAILURE");
                            writeToUiAppend(output, "returnCode is " + Utils.byteToHex(dnaC.getLastCommandResult().status2));
                            writeToUiAppend(output, "Authentication not possible, Operation aborted");
                            return;
                        }
                    } else {
                        // any other error, print the error code and return
                        writeToUiAppend(output, "AES Authentication FAILURE");
                        writeToUiAppend(output, "returnCode is " + Utils.byteToHex(dnaC.getLastCommandResult().status2));
                        return;
                    }
                }

                writeToUiAppend(output, Constants.SINGLE_DIVIDER);
                // check all other application keys (1..4) if they are FACTORY or CUSTOM
                // 0 = no auth, 1 = FACTORY key SUCCESS, 2 = CUSTOM key SUCCESS, 3 = diversified key SUCCESS, 4 = UNKNOWN key, failure
                if (!isLrpAuthenticationMode) {
                    // app key 1
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY1, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 1 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY1, Constants.APPLICATION_KEY_1);
                        if (success) {
                            writeToUiAppend(output, "App Key 1 is CUSTOM key");
                        } else {
                            writeToUiAppend(output, "App Key 1 has UNKNOWN key");
                        }
                    }
                    // app key 2
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY2, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 2 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY2, Constants.APPLICATION_KEY_2);
                        if (success) {
                            writeToUiAppend(output, "App Key 2 is CUSTOM key");
                        } else {
                            writeToUiAppend(output, "App Key 2 has UNKNOWN key");
                        }
                    }
                    // app key 3
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY3, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 3 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY3, key3);
                        if (success) {
                            writeToUiAppend(output, "App Key 3 IS the inputted key 3");
                        } else {
                            writeToUiAppend(output, "App Key 3 IS NOT the inputted key 3");
                        }
                    }
                    // app key 4
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY4, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 4 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY4, key4);
                        if (success) {
                            writeToUiAppend(output, "App Key 4 IS the inputted key 4");
                        } else {
                            writeToUiAppend(output, "App Key 4 IS NOT the inputted key 4");
                        }
                    }
                } else {
                    // app key 1
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY1, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 1 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY1, Constants.APPLICATION_KEY_1);
                        if (success) {
                            writeToUiAppend(output, "App Key 1 is CUSTOM key");
                        } else {
                            writeToUiAppend(output, "App Key 1 has UNKNOWN key");
                        }
                    }
                    // app key 2
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY2, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 2 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY2, Constants.APPLICATION_KEY_2);
                        if (success) {
                            writeToUiAppend(output, "App Key 2 is CUSTOM key");
                        } else {
                            writeToUiAppend(output, "App Key 2 has UNKNOWN key");
                        }
                    }
                    // app key 3
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY3, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 3 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY3, key3);
                        if (success) {
                            writeToUiAppend(output, "App Key 3 IS the inputted key 3");
                        } else {
                            writeToUiAppend(output, "App Key 3 IS NOT the inputted key 3");
                        }
                    }
                    // app key 4
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY4, key0);
                    if (success) {
                        writeToUiAppend(output, "App Key 4 is FACTORY key");
                    } else {
                        // try to authenticate with custom key
                        success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY4, key4);
                        if (success) {
                            writeToUiAppend(output, "App Key 4 IS the inputted key 4");
                        } else {
                            writeToUiAppend(output, "App Key 4 IS NOT the inputted key 4");
                        }
                    }
                }
                writeToUiAppend(output, Constants.DOUBLE_DIVIDER);

                if (!isLrpAuthenticationMode) {
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, key0);
                } else {
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, key0);
                }
                if (!success) {
                    writeToUiAppend(output, "Error on Authentication with ACCESS KEY 0, aborted");
                    return;
                }

                byte[] realTagUid;
                try {
                    realTagUid = GetCardUid.run(dnaC);
                    Log.d(TAG, Utils.printData("real Tag UID", realTagUid));
                    writeToUiAppend(output, "real Tag UID: " + Utils.bytesToHex(realTagUid));
                } catch (ProtocolException e) {
                    writeToUiAppend(output, "Could not read the real Tag UID, aborted");
                    writeToUiAppend(output, "returnCode is " + Utils.byteToHex(dnaC.getLastCommandResult().status2));
                    return;
                }
                writeToUiAppend(output, Constants.DOUBLE_DIVIDER);

                // silent authenticate with Access Key 0, should work
                if (!isLrpAuthenticationMode) {
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, key0);
                } else {
                    success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, key0);
                }
                if (!success) {
                    writeToUiAppend(output, "Error on Authentication with ACCESS KEY 0, aborted");
                    return;
                }
                int lastAuthKeyNumber = 0;

                // get the file settings
                writeToUiAppend(output, "Get the File Settings");
                FileSettings fileSettings01;
                try {
                    fileSettings01 = GetFileSettings.run(dnaC, CC_FILE_NUMBER);
                } catch (Exception e) {
                    Log.e(TAG, "getFileSettings File 01 Exception: " + e.getMessage());
                    writeToUiAppend(output, "getFileSettings File 01 Exception: " + e.getMessage());
                    return;
                }
                writeToUiAppend(output, DnacFileSettingsDumper.run(CC_FILE_NUMBER, fileSettings01));
                writeToUiAppend(output, Constants.SINGLE_DIVIDER);

                FileSettings fileSettings02;
                try {
                    fileSettings02 = GetFileSettings.run(dnaC, NDEF_FILE_NUMBER);
                } catch (Exception e) {
                    Log.e(TAG, "getFileSettings File 02 Exception: " + e.getMessage());
                    writeToUiAppend(output, "getFileSettings File 02 Exception: " + e.getMessage());
                    return;
                }
                writeToUiAppend(output, DnacFileSettingsDumper.run(NDEF_FILE_NUMBER, fileSettings02));
                writeToUiAppend(output, Constants.SINGLE_DIVIDER);

                FileSettings fileSettings03;
                try {
                    fileSettings03 = GetFileSettings.run(dnaC, DATA_FILE_NUMBER);
                } catch (Exception e) {
                    Log.e(TAG, "getFileSettings File 03 Exception: " + e.getMessage());
                    writeToUiAppend(output, "getFileSettings File 03 Exception: " + e.getMessage());
                    return;
                }
                writeToUiAppend(output, DnacFileSettingsDumper.run(DATA_FILE_NUMBER, fileSettings03));
                writeToUiAppend(output, Constants.DOUBLE_DIVIDER);

                // read the content of each file
                // check which key in required to read the file
                int file01RAccess = fileSettings01.readPerm;
                if (file01RAccess != ACCESS_EVERYONE) {
                    // authenticate with file01RAccess key
                    if (file01RAccess != lastAuthKeyNumber) {
                        // the requested key is different from the last auth key
                        // did we had a successful authentication with this key ? with FACTORY or CUSTOM key ?

                        if (!isLrpAuthenticationMode) {
                            success = AESEncryptionMode.authenticateEV2(dnaC, file01RAccess, key0);
                        } else {
                            success = LRPEncryptionMode.authenticateLRP(dnaC, file01RAccess, key0);
                        }
                        if (!success) {
                            writeToUiAppend(output, "Error on Authentication with key " + file01RAccess  + ", aborted");
                            return;
                        }
                        lastAuthKeyNumber = file01RAccess;
                    }
                }

                // check which key in required to read the file
                int file02RAccess = fileSettings02.readPerm;
                if (file02RAccess != ACCESS_EVERYONE) {
                    // authenticate with file02RAccess key
                    if (file02RAccess != lastAuthKeyNumber) {
                        // the requested key is different from the last auth key
                        // did we had a successful authentication with this key ? with FACTORY or CUSTOM key ?

                        if (!isLrpAuthenticationMode) {
                            success = AESEncryptionMode.authenticateEV2(dnaC, file02RAccess, key0);
                        } else {
                            success = LRPEncryptionMode.authenticateLRP(dnaC, file02RAccess, key0);
                        }
                        if (!success) {
                            writeToUiAppend(output, "Error on Authentication with key " + file02RAccess  + ", aborted");
                            return;
                        }
                        lastAuthKeyNumber = file02RAccess;
                    }
                }

                // check which key in required to read the file
                int file03RAccess = fileSettings03.readPerm;
                if (file03RAccess != ACCESS_EVERYONE) {
                    // authenticate with file03RAccess key
                    if (file03RAccess != lastAuthKeyNumber) {
                        // the requested key is different from the last auth key
                        // did we had a successful authentication with this key ? with FACTORY or CUSTOM key ?

                        if (!isLrpAuthenticationMode) {
                            success = AESEncryptionMode.authenticateEV2(dnaC, file03RAccess, key0);
                        } else {
                            success = LRPEncryptionMode.authenticateLRP(dnaC, file03RAccess, key0);
                        }
                        if (!success) {
                            writeToUiAppend(output, "Error on Authentication with key " + file03RAccess  + ", aborted");
                            return;
                        }
                    }
                }

            } catch (IOException e) {
                Log.e(TAG, "Exception: " + e.getMessage());
                writeToUiAppend(output, "Exception: " + e.getMessage());
            }
            writeToUiAppend(output, "== FINISHED ==");
            vibrateShort();
        });
        worker.start();
    }

    /**
     * section for options menu
     */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_return_home, menu);

        MenuItem mReturnHome = menu.findItem(R.id.action_return_home);
        mReturnHome.setOnMenuItemClickListener(item -> {
            Intent intent = new Intent(TagOverviewActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
            return false;
        });

        return super.onCreateOptionsMenu(menu);
    }
}