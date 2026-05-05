package de.androidcrypto.ntag424sdmfeature;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private Button menu1Prepare, menu3EncryptedSun, menu5Unset;
    private Button menu6NdefReader;
    private Button menu9TagOverview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar myToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(myToolbar);

        menu1Prepare = findViewById(R.id.btnMenu1Prepare);
        menu3EncryptedSun = findViewById(R.id.btnMenu3EncryptedSun);
        menu5Unset = findViewById(R.id.btnMenu5Unset);
        menu6NdefReader = findViewById(R.id.btnMenu6NdefReader);
        menu9TagOverview = findViewById(R.id.btnMenu9TagInformation);

        menu1Prepare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "menu1PrepareSun");
                Intent intent = new Intent(MainActivity.this, PrepareActivity.class);
                startActivity(intent);
            }
        });

        menu3EncryptedSun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "menu3EncryptedSun");
                Intent intent = new Intent(MainActivity.this, EncryptedSunActivity.class);
                startActivity(intent);
            }
        });

        menu5Unset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "menu5UnsetSun");
                Intent intent = new Intent(MainActivity.this, UnsetActivity.class);
                startActivity(intent);
            }
        });

        menu6NdefReader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "menu6NdefReader");
                Intent intent = new Intent(MainActivity.this, NdefReaderActivity.class);
                startActivity(intent);
            }
        });

        menu9TagOverview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "menu9TagOverview");
                Intent intent = new Intent(MainActivity.this, TagOverviewActivity.class);
                startActivity(intent);
            }
        });
    }

}