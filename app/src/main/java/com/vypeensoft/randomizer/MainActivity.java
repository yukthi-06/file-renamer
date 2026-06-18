package com.vypeensoft.randomizer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import android.view.MenuItem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG              = "FileRenamer";
    private static final String XYZ_SUFFIX       = ".xyz";
    private static final int    REQ_LEGACY_PERM  = 100;

    /**
     * Well-known config file location users can edit with any file manager or PC.
     * Format: one line containing the absolute folder path, e.g.
     *   /sdcard/DCIM/Camera
     */
    private static final String CONFIG_FILE_PATH =
            Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/filerenamer.config";

    // UI components
    private MaterialButton            btnSelectFolder;
    private MaterialButton            btnReverse;
    private MaterialButton            btnCheckSum;
    private TextView                  tvStatus;
    private LinearLayout              statusContainer;
    private ImageView                 statusIcon;
    private LinearProgressIndicator   progressIndicator;
    private DrawerLayout              drawerLayout;
    private NavigationView            navigationView;

    // Logger
    private FileWriter                logWriter;
    private boolean                   isLoggerInitialized = false;

    // Action types
    private enum ActionType {
        RANDOMIZE, REVERSE, CHECKSUM
    }

    // State to preserve intent across permission requests
    private ActionType pendingAction = ActionType.RANDOMIZE;

    // Background thread executor
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // -------------------------------------------------------------------------
    // Launcher: system "All Files Access" settings screen (API 30+)
    // -------------------------------------------------------------------------
    private final ActivityResultLauncher<Intent> manageStorageLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // User returned from settings — check again
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                && Environment.isExternalStorageManager()) {
                            readConfigAndStart(pendingAction);
                        } else {
                            showStatus(
                                    "\"All Files Access\" permission is required to read "
                                            + CONFIG_FILE_PATH + ".\n"
                                            + "Grant it in Settings and tap the button again.",
                                    StatusType.ERROR);
                        }
                    });

    // -------------------------------------------------------------------------
    // Launcher: SAF folder picker (fallback)
    // -------------------------------------------------------------------------
    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            if (result.getResultCode() == RESULT_OK
                                    && result.getData() != null) {
                                Uri treeUri = result.getData().getData();
                                if (treeUri != null) {
                                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                                    getContentResolver()
                                            .takePersistableUriPermission(treeUri, flags);
                                    startAction(Collections.singletonList(DocumentFile.fromTreeUri(MainActivity.this, treeUri)), pendingAction, null);
                                }
                            }
                        }
                    });

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logToFile(TAG, "MainActivity.onCreate: App starting up.");

        // Setup Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        btnSelectFolder   = findViewById(R.id.btnSelectFolder);
        btnReverse        = findViewById(R.id.btnReverse);
        btnCheckSum       = findViewById(R.id.btnCheckSum);
        tvStatus          = findViewById(R.id.tvStatus);
        statusContainer   = findViewById(R.id.statusContainer);
        statusIcon        = findViewById(R.id.statusIcon);
        progressIndicator = findViewById(R.id.progressIndicator);
        drawerLayout      = findViewById(R.id.drawer_layout);
        navigationView    = findViewById(R.id.nav_view);

        // Setup Drawer Toggle
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar, R.string.nav_open, R.string.nav_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // Setup Navigation View
        navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                Intent intent = null;

                if (id == R.id.nav_settings) {
                    intent = new Intent(MainActivity.this, SettingsActivity.class);
                } else if (id == R.id.nav_help) {
                    intent = new Intent(MainActivity.this, HelpActivity.class);
                } else if (id == R.id.nav_about) {
                    intent = new Intent(MainActivity.this, AboutActivity.class);
                }

                if (intent != null) {
                    startActivity(intent);
                }

                drawerLayout.closeDrawers();
                return true;
            }
        });

        btnSelectFolder.setOnClickListener(v -> onSelectFolderClicked());
        btnReverse.setOnClickListener(v -> onReverseClicked());
        btnCheckSum.setOnClickListener(v -> onCheckSumClicked());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
        if (logWriter != null) {
            try {
                logToFile(TAG, "MainActivity.onDestroy: Closing logger.");
                logWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing log writer", e); // Use Log.e as logToFile might fail
            }
        }
    }

    // -------------------------------------------------------------------------
    // Entry point: permission gating → read config → rename
    // -------------------------------------------------------------------------

    /**
     * Called on launch (and again when the button is tapped).
     * Routes through permission checks before reading the config file.
     */
    private void checkPermissionsAndReadConfig(ActionType actionType) {
        logToFile(TAG, "Checking permissions for action: " + actionType);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: need "All Files Access"
            if (!Environment.isExternalStorageManager()) {
                showStatus(
                        "\"All Files Access\" permission is needed to read\n"
                                + CONFIG_FILE_PATH + ".\n\n"
                                + "Tap the button to grant it in Settings.",
                        StatusType.INFO);
                // Don't auto-redirect; wait for the user to tap the button
                // so they understand why the settings screen opens.
                return;
            }
        } else {
            // API 21–29: need READ/WRITE_EXTERNAL_STORAGE
            boolean readGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            boolean writeGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!readGranted || !writeGranted) {
                // Will continue in onRequestPermissionsResult
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        REQ_LEGACY_PERM);
                return;
            }
        }

        readConfigAndStart(actionType);
    }

    /** Read the config file and kick off action. */
    private void readConfigAndStart(ActionType actionType) {
        initializeLogger();
        logToFile(TAG, "Reading config file: " + CONFIG_FILE_PATH);

        File configFile = new File(CONFIG_FILE_PATH);

        if (!configFile.exists()) {
            try {
                // Default to DCIM/Camera
                File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                File cameraDir = new File(dcimDir, "Camera");
                String defaultPath = cameraDir.getAbsolutePath();

                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(defaultPath);
                }
                logToFile(TAG, "Created default config file with path: " + defaultPath);
            } catch (IOException e) {
                logToFile(TAG, "Failed to create default config file: " + e.getMessage());
                showStatus(
                        "Config file not found and could not be created automatically.\n\n"
                                + "Please create it manually at:\n" + CONFIG_FILE_PATH,
                        StatusType.ERROR);
                return;
            }
        }

        List<DocumentFile> targetDirs = new ArrayList<>();
        List<String> missingPaths = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                File targetDir = new File(line);
                if (targetDir.exists() && targetDir.isDirectory()) {
                    targetDirs.add(DocumentFile.fromFile(targetDir));
                    logToFile(TAG, "Found configured directory: " + line);
                } else {
                    missingPaths.add(line);
                    logToFile(TAG, "Configured path not found or not a directory: " + line);
                }
            }
        } catch (IOException e) {
            logToFile(TAG, "Failed to read config file: " + e.getMessage());
            showStatus("Could not read " + CONFIG_FILE_PATH + ":\n" + e.getMessage(),
                    StatusType.ERROR);
            return;
        }

        if (targetDirs.isEmpty()) {
            if (missingPaths.isEmpty()) {
                showStatus("Config file is empty.\n\nAdd one or more target folder paths, each on a new line.",
                        StatusType.ERROR);
            } else {
                showStatus("No valid folders found in config.\n\nFolders not found:\n" + String.join("\n", missingPaths),
                        StatusType.ERROR);
            }
            return;
        }

        // All good — start action across all found folders
        startAction(targetDirs, actionType, missingPaths);
    }

    // -------------------------------------------------------------------------
    // Button click
    // -------------------------------------------------------------------------

    private void onSelectFolderClicked() {
        logToFile(TAG, "Randomize button clicked.");
        pendingAction = ActionType.RANDOMIZE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Open the system "All Files Access" settings page
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                manageStorageLauncher.launch(intent);
                return;
            }
        }
        // Permission is already granted (or below API 30) — read config and action
        checkPermissionsAndReadConfig(ActionType.RANDOMIZE);
    }

    private void onReverseClicked() {
        logToFile(TAG, "Reverse button clicked.");
        pendingAction = ActionType.REVERSE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Open the system "All Files Access" settings page
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                manageStorageLauncher.launch(intent);
                return;
            }
        }
        // Permission is already granted (or below API 30) — read config and action
        checkPermissionsAndReadConfig(ActionType.REVERSE);
    }

    private void onCheckSumClicked() {
        logToFile(TAG, "Checksum button clicked.");
        pendingAction = ActionType.CHECKSUM;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Open the system "All Files Access" settings page
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                manageStorageLauncher.launch(intent);
                return;
            }
        }
        // Permission is already granted (or below API 30) — read config and action
        checkPermissionsAndReadConfig(ActionType.CHECKSUM);
    }

    // -------------------------------------------------------------------------
    // Runtime permission result (API < 30)
    // -------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_PERM) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                readConfigAndStart(pendingAction);
            } else {
                showStatus("Storage permission denied. Cannot read " + CONFIG_FILE_PATH,
                        StatusType.ERROR);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Action logic — runs on a background thread via ExecutorService
    // -------------------------------------------------------------------------

    /** Process multiple directories for the specified action. */
    private void startAction(List<DocumentFile> directories, ActionType actionType, List<String> missingPaths) {
        runOnUiThread(() -> {
            btnSelectFolder.setEnabled(false);
            btnReverse.setEnabled(false);
            btnCheckSum.setEnabled(false);
            progressIndicator.setVisibility(View.VISIBLE);
            statusContainer.setVisibility(View.GONE);

            String label = "Processing…";
            if (actionType == ActionType.RANDOMIZE) label = "Randomizing files…";
            else if (actionType == ActionType.REVERSE) label = "Reversing files…";
            else if (actionType == ActionType.CHECKSUM) label = "Generating checksums…";

            String statusMessage = label + " in " + directories.size() + " folder(s)";
            StatusType statusType = StatusType.INFO;

            if (missingPaths != null && !missingPaths.isEmpty()) {
                String warning = "\n\nWarning: " + missingPaths.size() + " configured folder(s) were not found. They may be on a removable SD card, which is not supported when reading from the config file.";
                statusMessage += warning;
                logToFile(
                        TAG,
                        "Folders not found from config: "
                                + String.join(", ", missingPaths)
                );
                statusType = StatusType.WARNING;
            }

            showStatus(statusMessage, statusType);
        });

        executor.execute(() -> {
            ProcessStats stats = new ProcessStats();

            try {
                for (DocumentFile directory : directories) {
                    processDirectoryRecursively(directory, actionType, stats);
                }

                // Build result message
                String message;
                StatusType type;

                if (actionType == ActionType.CHECKSUM) {
                    if (stats.failCount > 0) {
                        message = "Some Checksum Errors";
                        type = (stats.successCount > 0) ? StatusType.WARNING : StatusType.ERROR;
                    } else if (stats.successCount > 0) {
                        message = "Checksum Generation Success";
                        type = StatusType.SUCCESS;
                    } else {
                        message = "No New Files To Checksum";
                        type = StatusType.INFO;
                    }
                } else {
                    boolean isReverse = (actionType == ActionType.REVERSE);
                    if (stats.failCount > 0) {
                        message = isReverse ? "Some Reversal Error" : "Some Randomization Error";
                        type = (stats.successCount > 0) ? StatusType.WARNING : StatusType.ERROR;
                    } else if (stats.successCount > 0) {
                        message = isReverse ? "Reversal Success" : "Randomization Success";
                        type = StatusType.SUCCESS;
                    } else {
                        message = isReverse ? "No Files To Reverse" : "No Files To Randomize";
                        type = StatusType.INFO;
                    }
                }

                postStatus(message, type);
                logToFile(TAG, "Action " + actionType + " finished. Success: " + stats.successCount + ", Fail: " + stats.failCount + ", Skip: " + stats.skipCount);

            } catch (Exception e) {
                logToFile(TAG, "Unexpected error during " + actionType + ": " + e.getMessage());
                postStatus("An unexpected error occurred: " + e.getMessage(), StatusType.ERROR);
            }
        });
    }

    private static class ProcessStats {
        int successCount = 0;
        int failCount    = 0;
        int skipCount    = 0;
    }

    private void processDirectoryRecursively(DocumentFile directory, ActionType actionType, ProcessStats stats) {
        if (directory == null || !directory.isDirectory()) return;

        DocumentFile[] files = directory.listFiles();
        if (files == null || files.length == 0) return;

        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                processDirectoryRecursively(file, actionType, stats);
            } else if (file.isFile()) {
                processFile(directory, file, files, actionType, stats);
            }
        }
    }

    private void processFile(DocumentFile directory, DocumentFile file, DocumentFile[] siblings, ActionType actionType, ProcessStats stats) {
        String originalName = file.getName();
        if (originalName == null) {
            stats.failCount++;
            return;
        }

        if (actionType == ActionType.CHECKSUM) {
            // Checksum logic
            if (originalName.toLowerCase().endsWith(".md5")) {
                stats.skipCount++;
                return;
            }

            // Determine the base name for the MD5 file (strip .xyz if present)
            String baseNameForMd5 = originalName;
            if (originalName.endsWith(XYZ_SUFFIX)) {
                baseNameForMd5 = originalName.substring(0, originalName.length() - XYZ_SUFFIX.length());
            }

            // Check if .md5 already exists
            String md5FileName = baseNameForMd5 + ".md5";
            boolean md5Exists = false;
            for (DocumentFile sibling : siblings) {
                if (md5FileName.equalsIgnoreCase(sibling.getName())) {
                    md5Exists = true;
                    break;
                }
            }

            if (md5Exists) {
                logToFile(TAG, "MD5 already exists for: " + baseNameForMd5);
                stats.skipCount++;
                return;
            }

            // Generate MD5
            try {
                String md5 = calculateMD5(file);
                DocumentFile md5File = directory.createFile("*/*", md5FileName);
                if (md5File != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(md5File.getUri())) {
                        if (out != null) {
                            String content = md5 + " " + baseNameForMd5;
                            out.write(content.getBytes());
                            stats.successCount++;
                            logToFile(TAG, "Generated MD5 for: " + originalName);
                        } else {
                            stats.failCount++;
                        }
                    }
                } else {
                    stats.failCount++;
                }
            } catch (Exception e) {
                logToFile(TAG, "Failed to generate MD5 for " + originalName + ": " + e.getMessage());
                stats.failCount++;
            }
        } else {
            // Renaming logic (Randomize / Reverse)
            boolean isReverse = (actionType == ActionType.REVERSE);
            String newName;
            if (isReverse) {
                if (originalName.endsWith(XYZ_SUFFIX)) {
                    newName = originalName.substring(0, originalName.length() - XYZ_SUFFIX.length());
                } else {
                    stats.skipCount++;
                    return;
                }
            } else {
                if (originalName.endsWith(XYZ_SUFFIX) || originalName.toLowerCase().endsWith(".md5")) {
                    stats.skipCount++;
                    return;
                }
                newName = originalName + XYZ_SUFFIX;
            }

            boolean renamed = file.renameTo(newName);
            if (renamed) {
                stats.successCount++;
                logToFile(TAG, "Renamed '" + originalName + "' to '" + newName + "'");
            } else {
                stats.failCount++;
                logToFile(TAG, "Failed to rename '" + originalName + "' to '" + newName + "'");
            }
        }
    }

    private String calculateMD5(DocumentFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream is = getContentResolver().openInputStream(file.getUri())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : md5sum) {
                String hex = Integer.toHexString(0xFF & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Logger
    // -------------------------------------------------------------------------

    private void initializeLogger() {
        if (isLoggerInitialized) return;

        // We should have permissions at this point
        try {
            String logDirPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Vypeensoft/Randomizer/log";
            File logDir = new File(logDirPath);
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    Log.e(TAG, "Failed to create log directory.");
                    return;
                }
            }

            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd.HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            String logFileName = "log_" + timestamp + ".txt";
            File logFile = new File(logDir, logFileName);

            logWriter = new FileWriter(logFile, true); // Append mode
            isLoggerInitialized = true;
            logToFile(TAG, "Logger initialized. Log file: " + logFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize file logger", e);
        }
    }

    private void logToFile(String tag, String message) {
        // Always log to logcat for debugging in Android Studio
        Log.d(tag, message);

        // Also log to file if writer is ready
        if (logWriter != null) {
            try {
                String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
                logWriter.write(timestamp + " [" + tag + "] " + message + "\n");
                logWriter.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log file", e);
            }
        }
    }
    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void postStatus(String message, StatusType type) {
        runOnUiThread(() -> {
            progressIndicator.setVisibility(View.GONE);
            btnSelectFolder.setEnabled(true);
            btnReverse.setEnabled(true);
            btnCheckSum.setEnabled(true);
            showStatus(message, type);
        });
    }

    private void showStatus(String message, StatusType type) {
        tvStatus.setText(message);
        statusContainer.setVisibility(View.VISIBLE);

        int iconRes;
        int tintColor;

        switch (type) {
            case SUCCESS:
                iconRes   = R.drawable.ic_check_circle;
                tintColor = ContextCompat.getColor(this, R.color.status_success);
                break;
            case WARNING:
                iconRes   = R.drawable.ic_warning;
                tintColor = ContextCompat.getColor(this, R.color.status_warning);
                break;
            case ERROR:
                iconRes   = R.drawable.ic_error;
                tintColor = ContextCompat.getColor(this, R.color.status_error);
                break;
            default: // INFO
                iconRes   = R.drawable.ic_info;
                tintColor = ContextCompat.getColor(this, R.color.primary);
                break;
        }

        statusIcon.setImageResource(iconRes);
        statusIcon.setColorFilter(tintColor);
        tvStatus.setTextColor(tintColor);
    }

    // -------------------------------------------------------------------------
    // Status type enum
    // -------------------------------------------------------------------------

    private enum StatusType {
        SUCCESS, WARNING, ERROR, INFO
    }
}
