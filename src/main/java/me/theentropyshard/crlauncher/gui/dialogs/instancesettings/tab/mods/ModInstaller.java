/*
 * CRLauncher - https://github.com/CRLauncher/CRLauncher
 * Copyright (C) 2024 CRLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.theentropyshard.crlauncher.gui.dialogs.instancesettings.tab.mods;

import me.theentropyshard.crlauncher.CRLauncher;
import me.theentropyshard.crlauncher.Language;
import me.theentropyshard.crlauncher.Settings;
import me.theentropyshard.crlauncher.cosmic.mods.Mod;
import me.theentropyshard.crlauncher.cosmic.mods.ModLoader;
import me.theentropyshard.crlauncher.cosmic.mods.cosmicquilt.QuiltMod;
import me.theentropyshard.crlauncher.cosmic.mods.fabric.FabricMod;
import me.theentropyshard.crlauncher.cosmic.mods.puzzle.PuzzleMod;
import me.theentropyshard.crlauncher.gui.dialogs.instancesettings.tab.mods.jar.JarModsTableModel;
import me.theentropyshard.crlauncher.gui.utils.MessageBox;
import me.theentropyshard.crlauncher.gui.utils.Worker;
import me.theentropyshard.crlauncher.instance.Instance;
import me.theentropyshard.crlauncher.logging.Log;
import me.theentropyshard.crlauncher.utils.FileUtils;
import me.theentropyshard.crlauncher.utils.ListUtils;
import me.theentropyshard.crlauncher.utils.StreamUtils;
import me.theentropyshard.crlauncher.utils.ZipUtils;
import me.theentropyshard.crlauncher.utils.json.Json;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class ModInstaller {
    public static void pickMod(Instance instance, ModsTableModel tableModel) {
        ModLoader loader = instance.getModLoader();

        if (loader == ModLoader.VANILLA) {
            ModInstaller.pickDataMod(instance, tableModel);
        } else {
            ModInstaller.pickJavaMod(instance, tableModel);
        }
    }

    public static void pickJarMod(Instance instance, JarModsTableModel tableModel) {
        UIManager.put("FileChooser.readOnly", Boolean.TRUE);

        Language language = CRLauncher.getInstance().getLanguage();

        new Worker<Mod, Void>("picking jar mod") {
            @Override
            protected Mod work() throws IOException {
                JFileChooser fileChooser = new JFileChooser();

                String archives = language.getString("gui.general.archives");
                fileChooser.setFileFilter(new FileNameExtensionFilter(archives + " (*.zip, *.jar)", "zip", "jar"));

                Settings settings = CRLauncher.getInstance().getSettings();
                if (settings.lastDir != null && !settings.lastDir.isEmpty()) {
                    fileChooser.setCurrentDirectory(new File(settings.lastDir));
                }

                if (fileChooser.showOpenDialog(CRLauncher.frame) != JFileChooser.APPROVE_OPTION) {
                    return null;
                }

                File selectedFile = fileChooser.getSelectedFile();

                if (selectedFile == null) {
                    return null;
                }

                settings.lastDir = fileChooser.getCurrentDirectory().getAbsolutePath();

                List<Mod> jarMods = instance.getJarMods();

                Path jarModPath = selectedFile.toPath().toAbsolutePath().normalize();
                String fileName = jarModPath.getFileName().toString();

                Mod jarMod = new Mod();
                jarMod.setActive(true);
                jarMod.setName(fileName);
                jarMod.setFileName(fileName);

                if (ListUtils.search(jarMods, m -> m.getName().equals(jarMod.getName())) != null) {
                    MessageBox.showErrorMessage(CRLauncher.frame,
                        language.getString("messages.gui.mods.modAddedName")
                            .replace("$$MOD_NAME$$", jarMod.getName()));

                    return null;
                }

                Files.copy(jarModPath, instance.getJarModPath(jarMod));
                jarMods.add(jarMod);

                return jarMod;
            }

            @Override
            protected void done() {
                UIManager.put("FileChooser.readOnly", Boolean.FALSE);

                Mod jarMod;

                try {
                    jarMod = this.get();
                } catch (InterruptedException | ExecutionException ex) {
                    Log.error(ex);

                    return;
                }

                if (jarMod == null) {
                    return;
                }

                tableModel.addMod(jarMod);
            }
        }.execute();
    }

    public static void pickDataMod(Instance instance, ModsTableModel tableModel) {
        UIManager.put("FileChooser.readOnly", Boolean.TRUE);

        Language language = CRLauncher.getInstance().getLanguage();

        new Worker<Mod, Void>("picking data mod") {
            @Override
            protected Mod work() throws Exception {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                fileChooser.setFileFilter(new FileNameExtensionFilter(
                    language.getString("gui.general.archives") + " (*.zip)", "zip"));

                Settings settings = CRLauncher.getInstance().getSettings();

                if (settings.lastDir != null && !settings.lastDir.isEmpty()) {
                    fileChooser.setCurrentDirectory(new File(settings.lastDir));
                }

                if (fileChooser.showOpenDialog(CRLauncher.frame) != JFileChooser.APPROVE_OPTION) {
                    return null;
                }

                File selectedFile = fileChooser.getSelectedFile();

                if (selectedFile == null) {
                    return null;
                }

                settings.lastDir = fileChooser.getCurrentDirectory().getAbsolutePath();
                Path dataModPath = selectedFile.toPath();

                Path dataModsDir = instance.getModsDir(ModLoader.VANILLA);

                Mod mod = new Mod();
                mod.setActive(true);

                if (Files.isDirectory(dataModPath)) {
                    Path dest = dataModsDir.resolve(dataModPath.getFileName());
                    FileUtils.delete(dest);
                    FileUtils.copyDirectory(dataModPath, dest);

                    mod.setFileName(dest.getFileName().toString());
                    mod.setName(dest.getFileName().toString());
                } else {
                    String topLevelDirectory = null;
                    Path dest = null;

                    try (ZipFile file = new ZipFile(dataModPath.toFile())) {
                        topLevelDirectory = ZipUtils.findTopLevelDirectory(file.getFileHeaders());
                        dest = dataModsDir.resolve(topLevelDirectory);
                        FileUtils.delete(dest);
                        file.extractAll(dataModsDir.toString());
                    } catch (Exception e) {
                        Log.error("Could not extract file " + dataModPath + " to dir: " + dest, e);
                    }

                    if (topLevelDirectory == null || dest == null) {
                        return null;
                    }

                    mod.setFileName(dest.getFileName().toString());
                    mod.setName(topLevelDirectory.replace("/", ""));
                }

                List<Mod> dataMods = instance.getDataMods();

                if (ListUtils.search(dataMods, m -> m.getName().equals(mod.getName())) != null) {
                    MessageBox.showErrorMessage(CRLauncher.frame,
                        language.getString("messages.gui.mods.modAddedName")
                            .replace("$$MOD_NAME$$", mod.getName()));

                    return null;
                }

                dataMods.add(mod);

                return mod;
            }

            @Override
            protected void done() {
                UIManager.put("FileChooser.readOnly", Boolean.FALSE);

                Mod dataMod;

                try {
                    dataMod = this.get();
                } catch (InterruptedException | ExecutionException ex) {
                    Log.error(ex);

                    return;
                }

                if (dataMod == null) {
                    return;
                }

                tableModel.addMod(dataMod);
            }
        }.execute();
    }

    public static void pickJavaMod(Instance instance, ModsTableModel tableModel) {
        ModLoader loader = instance.getModLoader();

        if (loader == ModLoader.VANILLA) {
            return;
        }

        new Worker<Void, Void>("picking " + loader.getName() + " mod") {
            @Override
            protected Void work() throws Exception {
                UIManager.put("FileChooser.readOnly", Boolean.TRUE);

                Language language = CRLauncher.getInstance().getLanguage();
                JFileChooser fileChooser = new JFileChooser();
                String archives = language.getString("gui.general.archives");
                fileChooser.setFileFilter(new FileNameExtensionFilter(archives + " (*.zip, *.jar)", "zip", "jar"));

                Settings settings = CRLauncher.getInstance().getSettings();
                if (settings.lastDir != null && !settings.lastDir.isEmpty()) {
                    fileChooser.setCurrentDirectory(new File(settings.lastDir));
                }

                int option = fileChooser.showOpenDialog(CRLauncher.frame);

                if (option != JFileChooser.APPROVE_OPTION) {
                    return null;
                }

                File selectedFile = fileChooser.getSelectedFile();
                if (selectedFile == null) {
                    return null;
                }

                settings.lastDir = fileChooser.getCurrentDirectory().getAbsolutePath();

                List<Mod> mods = instance.getMods(loader);

                Path jarModPath = selectedFile.toPath().toAbsolutePath().normalize();

                Mod mod;
                try (ZipFile file = new ZipFile(jarModPath.toFile())) {
                    String modInfoFile = ModInstaller.getModInfoFile(loader);

                    if (modInfoFile == null) {
                        return null;
                    }

                    FileHeader fileHeader = file.getFileHeader(modInfoFile);

                    if (fileHeader == null) {
                        Log.warn(jarModPath + " does not contain '" + modInfoFile + "'");

                        mod = new Mod(UUID.randomUUID().toString(), jarModPath.getFileName().toString(), "<unknown>", "<unknown>");
                        mod.setFileName(jarModPath.getFileName().toString());
                        mod.setActive(true);

                        if (mods.stream().anyMatch(m -> m.getName().equals(mod.getName()))) {
                            MessageBox.showErrorMessage(CRLauncher.frame,
                                language.getString("messages.gui.mods.modAddedName")
                                    .replace("$$MOD_NAME$$", mod.getName()));

                            return null;
                        }
                    } else {
                        String json = StreamUtils.readToString(file.getInputStream(fileHeader));

                        if (loader == ModLoader.FABRIC) {
                            mod = Json.parse(json, FabricMod.class).toMod();
                        } else if (loader == ModLoader.QUILT) {
                            mod = Json.parse(json, QuiltMod.class).toMod();
                        } else if (loader == ModLoader.PUZZLE) {
                            mod = Json.parse(json, PuzzleMod.class).toMod();
                        } else {
                            mod = null;
                        }

                        if (mod == null) {
                            return null;
                        }

                        if (mods.stream().anyMatch(m -> m.getId().equals(mod.getId()))) {
                            MessageBox.showErrorMessage(CRLauncher.frame,
                                language.getString("messages.gui.mods.modAddedId")
                                    .replace("$$MOD_ID$$", mod.getId()));

                            return null;
                        }

                        mod.setFileName(jarModPath.getFileName().toString());
                        mod.setActive(true);
                    }

                    mods.add(mod);
                    tableModel.addMod(mod);
                } catch (Exception e) {
                    Log.error("Unexpected error", e);

                    return null;
                }

                Path modsDir = instance.getModsDir(loader);
                FileUtils.createDirectoryIfNotExists(modsDir);

                Path modPathInFolder = modsDir.resolve(jarModPath.getFileName());
                mod.setFileName(modPathInFolder.getFileName().toString());

                if (Files.exists(modPathInFolder)) {
                    FileUtils.delete(modPathInFolder);
                }
                Files.copy(jarModPath, modPathInFolder);

                UIManager.put("FileChooser.readOnly", Boolean.FALSE);

                return null;
            }
        }.execute();
    }

    public static String getModInfoFile(ModLoader loader) {
        return switch (loader) {
            case VANILLA -> null;
            case FABRIC -> "fabric.mod.json";
            case QUILT -> "quilt.mod.json";
            case PUZZLE -> "puzzle.mod.json";
        };
    }
}
