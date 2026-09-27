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
package com.max2idea.android.limbo.machine;

import android.app.ActivityManager;
import android.content.Context;

import com.max2idea.android.limbo.files.FileInstaller;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboApplication;

import java.io.File;

/**
 * Quick Start: creates a ready-to-run machine from a small set of choices
 * (what kind of OS, which file to boot, how big a disk) so users never have to
 * touch the full settings screen. Every value here is one the regular Limbo UI
 * can also produce, so a Quick Start machine can still be tweaked afterwards in
 * the normal screen.
 *
 * Lives in the machine package because Machine's setters and the machine
 * database are package-private.
 */
public final class QuickSetup {

    /** Virtual disk sizes that have a matching template under assets/hdtemplates. */
    public static final int[] DISK_SIZES_GB = {2, 4, 10, 20};

    /**
     * Tuned presets for the x86 emulator. Choices favor what each guest OS
     * supports out of the box (no extra drivers needed during install).
     */
    public enum Preset {
        WINDOWS_7("Windows 7 / Vista (incl. lite builds)",
                "2 GB RAM, multi-core, Intel e1000 network and HD Audio (both built into Windows 7), "
                        + "touch-friendly pointer.",
                "Default", 4, 2048, "std", "hda", "User", "e1000", "usb-tablet", false, true, 2),
        WINDOWS_XP("Windows XP / 2000",
                "1 GB RAM, Realtek network and AC97 sound (built into XP), touch-friendly pointer.",
                "Default", 2, 1024, "std", "ac97", "User", "rtl8139", "usb-tablet", false, true, 1),
        WINDOWS_9X("Windows 98 / ME / 95",
                "256 MB RAM (98 is unstable with more), single core, Cirrus graphics, "
                        + "SoundBlaster 16, classic mouse.",
                "pentium2", 1, 256, "cirrus", "sb16", "User", "ne2k_pci", "ps2", true, false, 0),
        DOS("DOS / FreeDOS",
                "64 MB RAM, single core, SoundBlaster 16, no network.",
                "pentium", 1, 64, "cirrus", "sb16", "None", "Default", "ps2", true, false, 0),
        LINUX("Linux (lightweight distros)",
                "2 GB RAM, multi-core, e1000 network, HD Audio, touch-friendly pointer.",
                "Default", 4, 2048, "std", "hda", "User", "e1000", "usb-tablet", false, true, 2);

        public final String label;
        public final String description;
        final String cpu;
        final int maxCores;
        final int memoryMb;
        final String vga;
        final String soundCard;
        final String network;
        final String networkCard;
        final String mouse;
        /** Windows NT-family guests crash without the CPU timestamp counter, so keep it on for them. */
        final boolean disableTsc;
        final boolean multiThreaded;
        /** Index into DISK_SIZES_GB suggested for a fresh install. */
        public final int defaultDiskIndex;

        Preset(String label, String description, String cpu, int maxCores, int memoryMb,
               String vga, String soundCard, String network, String networkCard, String mouse,
               boolean disableTsc, boolean multiThreaded, int defaultDiskIndex) {
            this.label = label;
            this.description = description;
            this.cpu = cpu;
            this.maxCores = maxCores;
            this.memoryMb = memoryMb;
            this.vga = vga;
            this.soundCard = soundCard;
            this.network = network;
            this.networkCard = networkCard;
            this.mouse = mouse;
            this.disableTsc = disableTsc;
            this.multiThreaded = multiThreaded;
            this.defaultDiskIndex = defaultDiskIndex;
        }
    }

    private QuickSetup() {
    }

    public static String[] presetLabels() {
        Preset[] presets = Preset.values();
        String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++)
            labels[i] = presets[i].label;
        return labels;
    }

    /** Returns a name that isn't taken yet, e.g. "Windows 7", "Windows 7 (2)". */
    public static String uniqueName(String base) {
        String clean = sanitizeName(base);
        if (clean.isEmpty())
            clean = "My VM";
        IMachineDatabase db = MachineOpenHelper.getInstance();
        String name = clean;
        int n = 2;
        while (db.getMachine(name) != null) {
            name = clean + " (" + n + ")";
            n++;
        }
        return name;
    }

    /** Keeps names safe to use as a folder name for the machine's saved state. */
    public static String sanitizeName(String name) {
        if (name == null)
            return "";
        return name.replaceAll("[^A-Za-z0-9 ._()-]", "").trim();
    }

    /** Folder for Quick Start disks: app-specific storage, no permission prompt needed. */
    public static File getDisksDir(Context context) {
        File base = context.getExternalFilesDir("disks");
        if (base == null) // external storage unavailable, fall back to internal
            base = new File(context.getFilesDir(), "disks");
        if (!base.exists())
            base.mkdirs();
        return base;
    }

    /**
     * Creates a new growable qcow2 disk from one of Limbo's bundled templates.
     * The file starts tiny and only grows as the guest writes data.
     *
     * @return absolute path of the new disk, or null on failure
     */
    public static String createDisk(Context context, String machineName, int sizeGb) {
        String template = "hd" + sizeGb + "g.qcow2";
        String fileName = sanitizeName(machineName).replace(' ', '_')
                + "-" + System.currentTimeMillis() + ".qcow2";
        return FileInstaller.installImageTemplateToExternalStorage(context, template,
                getDisksDir(context).getAbsolutePath(), "hdtemplates", fileName);
    }

    /**
     * Creates and saves the machine. Must not be called on the UI thread.
     *
     * @param cdPath   installer disc (.iso) to insert, or null
     * @param hdaPath  hard disk image (new or existing), or null
     * @return null on success, otherwise a message suitable for the user
     */
    public static String createMachine(Context context, String name, Preset preset,
                                       String cdPath, String hdaPath) {
        IMachineDatabase db = MachineOpenHelper.getInstance();
        if (db.getMachine(name) != null)
            return "A machine named \"" + name + "\" already exists.";

        Machine machine = new Machine(name, true);

        boolean host64 = LimboApplication.isHost64Bit();
        boolean multiThreaded = preset.multiThreaded && host64 && Config.enableMTTCG;
        int cores = 1;
        if (multiThreaded) {
            // Leave half the phone's cores for Android and the display.
            int available = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            cores = Math.max(1, Math.min(preset.maxCores, available));
        }

        machine.setCpu(preset.cpu);
        machine.setCpuNum(cores);
        machine.setEnableMTTCG(multiThreaded ? 1 : 0);
        machine.setMemory(safeMemoryMb(context, preset.memoryMb, host64));
        machine.setMachineType("pc");
        machine.setDisableTSC(preset.disableTsc ? 1 : 0);
        machine.setVga(preset.vga);
        machine.setSoundCard(preset.soundCard);
        machine.setNetwork(preset.network);
        machine.setNetworkCard(preset.networkCard);
        machine.setMouse(preset.mouse);
        machine.setEnableVNC(0); // built-in display, no separate VNC app needed
        // "Default" boot order tries the hard disk first, then the CD. A blank
        // new disk isn't bootable, so the installer CD boots; once the OS is
        // installed the disk boots on its own - no setting to flip afterwards.
        machine.setBootDevice("Default");

        if (hdaPath != null && !hdaPath.isEmpty()) {
            machine.setHdaImagePath(hdaPath);
            MachineFilePaths.insertRecentFilePath(Machine.FileType.HDA, hdaPath);
        }
        if (cdPath != null && !cdPath.isEmpty()) {
            machine.setEnableCDROM(true);
            machine.setCdImagePath(cdPath);
            MachineFilePaths.insertRecentFilePath(Machine.FileType.CDROM, cdPath);
        }

        if (db.insertMachine(machine) < 0)
            return "Could not save the machine.";
        return null;
    }

    /** Never give the guest more than a quarter of the phone's RAM (and max 1 GB on 32-bit phones). */
    private static int safeMemoryMb(Context context, int wantedMb, boolean host64) {
        int limit = host64 ? wantedMb : Math.min(wantedMb, 1024);
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long quarterMb = info.totalMem / (4L * 1024 * 1024);
            if (quarterMb > 0)
                limit = (int) Math.min(limit, quarterMb);
        } catch (Exception ignored) {
        }
        return Math.max(64, limit);
    }
}
