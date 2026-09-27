/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package com.max2idea.android.limbo.main;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import com.max2idea.android.limbo.files.FileUtils;
import com.max2idea.android.limbo.machine.QuickSetup;
import com.max2idea.android.limbo.toast.ToastUtils;

/**
 * The "easy mode" front door: one screen, a handful of plain-language choices,
 * then one tap to create and boot the machine. Everything advanced stays
 * available in Limbo's normal screen.
 */
public class QuickStartDialog {

    public static final int PICK_FILE_REQUEST_CODE = 3001;

    /** Called on the UI thread once the machine exists and should be started. */
    public interface Listener {
        void onQuickStartMachineCreated(String machineName);
    }

    private final Activity activity;
    private final Listener listener;

    private AlertDialog dialog;
    private EditText nameView;
    private Spinner presetView;
    private TextView presetDescription;
    private RadioButton installMode;
    private Button pickFileButton;
    private TextView pickedFileView;
    private TextView pickHint;
    private LinearLayout diskSizeGroup;
    private Spinner diskSizeView;

    private String pickedFile;
    private boolean nameEditedByUser;

    public QuickStartDialog(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void show() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(8), pad, 0);

        addText(root, "Pick what you want to run and the file to boot. "
                + "Settings are chosen for you, and you can still change them later "
                + "on the main screen.", false);

        // What will you run?
        addText(root, "What will you run?", true);
        presetView = new Spinner(activity);
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, QuickSetup.presetLabels());
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetView.setAdapter(presetAdapter);
        root.addView(presetView);
        presetDescription = addText(root, "", false);
        presetDescription.setAlpha(0.7f);

        // Name
        addText(root, "Name", true);
        nameView = new EditText(activity);
        nameView.setSingleLine(true);
        nameView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus)
                    nameEditedByUser = true;
            }
        });
        root.addView(nameView);

        // Start from
        addText(root, "Start from", true);
        RadioGroup modeGroup = new RadioGroup(activity);
        installMode = new RadioButton(activity);
        installMode.setId(View.generateViewId());
        installMode.setText("Installer disc (.iso) + a new empty disk");
        RadioButton existingMode = new RadioButton(activity);
        existingMode.setId(View.generateViewId());
        existingMode.setText("A ready-made disk image (.img, .qcow2, .vhd, .vmdk)");
        modeGroup.addView(installMode);
        modeGroup.addView(existingMode);
        installMode.setChecked(true);
        root.addView(modeGroup);

        pickFileButton = new Button(activity);
        root.addView(pickFileButton);
        pickedFileView = addText(root, "No file chosen", false);
        pickHint = addText(root, "Tip: files must be in your phone's storage. "
                + "If a file is rejected, open the menu in the file picker, choose your phone, "
                + "and find it there instead of in the Downloads shortcut.", false);
        pickHint.setAlpha(0.6f);
        pickHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        // Disk size (install mode only)
        diskSizeGroup = new LinearLayout(activity);
        diskSizeGroup.setOrientation(LinearLayout.VERTICAL);
        addText(diskSizeGroup, "New disk size", true);
        diskSizeView = new Spinner(activity);
        String[] sizes = new String[QuickSetup.DISK_SIZES_GB.length];
        for (int i = 0; i < sizes.length; i++)
            sizes[i] = QuickSetup.DISK_SIZES_GB[i] + " GB (only uses space as it fills up)";
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, sizes);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        diskSizeView.setAdapter(sizeAdapter);
        diskSizeGroup.addView(diskSizeView);
        root.addView(diskSizeGroup);

        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                pickedFile = null;
                updateModeViews();
            }
        });
        presetView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                QuickSetup.Preset preset = getPreset();
                presetDescription.setText(preset.description);
                diskSizeView.setSelection(preset.defaultDiskIndex);
                if (!nameEditedByUser)
                    suggestName(preset);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        pickFileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });
        updateModeViews();

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Quick Start")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Create & Start", null)
                .create();
        dialog.show();
        // Set the click handler after show() so invalid input doesn't close the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCreateClicked();
            }
        });
    }

    private QuickSetup.Preset getPreset() {
        return QuickSetup.Preset.values()[presetView.getSelectedItemPosition()];
    }

    private void suggestName(final QuickSetup.Preset preset) {
        final String base = preset.label.split(" / ")[0].split(" \\(")[0];
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String name = QuickSetup.uniqueName(base);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!nameEditedByUser)
                            nameView.setText(name);
                    }
                });
            }
        }).start();
    }

    private void updateModeViews() {
        boolean install = installMode.isChecked();
        pickFileButton.setText(install ? "Choose installer disc (.iso)…" : "Choose disk image…");
        diskSizeGroup.setVisibility(install ? View.VISIBLE : View.GONE);
        pickedFileView.setText(pickedFile == null ? "No file chosen" : displayName(pickedFile));
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!installMode.isChecked()) // the guest writes to its hard disk
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) {
            // Open in phone storage's Download folder: Limbo can only use files
            // from the phone-storage provider, not the "Downloads" shortcut.
            Uri downloads = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Download");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads);
        }
        try {
            activity.startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
        } catch (Exception ex) {
            ToastUtils.toastLong(activity, "No file picker available: " + ex.getMessage());
        }
    }

    /** Forward from the Activity's onActivityResult. */
    public void onFilePicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null)
            return;
        // Validates the provider, shows Limbo's own message if unsupported, and keeps
        // permission to the file across reboots.
        String file = FileUtils.getFileUriFromIntent(activity, data, !installMode.isChecked());
        if (file == null)
            return;
        pickedFile = file;
        updateModeViews();
        if (!nameEditedByUser && !installMode.isChecked()) {
            String name = displayName(file);
            int dot = name.lastIndexOf('.');
            if (dot > 0)
                name = name.substring(0, dot);
            nameView.setText(QuickSetup.sanitizeName(name));
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    private void onCreateClicked() {
        final String name = QuickSetup.sanitizeName(nameView.getText().toString());
        if (name.isEmpty()) {
            ToastUtils.toastShort(activity, "Give your machine a name");
            return;
        }
        if (pickedFile == null) {
            ToastUtils.toastShort(activity, installMode.isChecked()
                    ? "Choose the installer disc (.iso) first" : "Choose the disk image first");
            return;
        }
        final QuickSetup.Preset preset = getPreset();
        final boolean install = installMode.isChecked();
        final int sizeGb = QuickSetup.DISK_SIZES_GB[diskSizeView.getSelectedItemPosition()];
        final String file = pickedFile;

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error;
                if (install) {
                    String disk = QuickSetup.createDisk(activity, name, sizeGb);
                    error = disk == null ? "Could not create the virtual disk"
                            : QuickSetup.createMachine(activity, name, preset, file, disk);
                } else {
                    error = QuickSetup.createMachine(activity, name, preset, null, file);
                }
                final String finalError = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalError != null) {
                            ToastUtils.toastLong(activity, finalError);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            return;
                        }
                        dialog.dismiss();
                        listener.onQuickStartMachineCreated(name);
                    }
                });
            }
        }).start();
    }

    private String displayName(String file) {
        try {
            if (file.startsWith("content://")) {
                DocumentFile doc = DocumentFile.fromSingleUri(activity, Uri.parse(file));
                if (doc != null && doc.getName() != null)
                    return doc.getName();
            }
        } catch (Exception ignored) {
        }
        int slash = file.lastIndexOf('/');
        return slash >= 0 ? file.substring(slash + 1) : file;
    }

    private TextView addText(LinearLayout parent, String text, boolean heading) {
        TextView view = new TextView(activity);
        view.setText(text);
        if (heading) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
            view.setPadding(0, dp(14), 0, dp(2));
        } else {
            view.setPadding(0, dp(2), 0, dp(2));
        }
        parent.addView(view);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
