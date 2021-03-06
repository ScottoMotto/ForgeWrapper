package io.github.zekerzhayard.forgewrapper.converter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zekerzhayard.forgewrapper.installer.Download;

public class Converter {
    public static void convert(Path installerPath, Path targetDir, String cursepack) throws Exception {
        if (cursepack != null) {
            installerPath = getForgeInstallerFromCursePack(cursepack);
        }

        JsonObject installer = getJsonFromZip(installerPath, "version.json");
        List<String> arguments = getAdditionalArgs(installer);
        String mcVersion = arguments.get(arguments.indexOf("--fml.mcVersion") + 1);
        String forgeVersion = arguments.get(arguments.indexOf("--fml.forgeVersion") + 1);
        String forgeFullVersion = "forge-" + mcVersion + "-" + forgeVersion;
        String instanceName = cursepack == null ? forgeFullVersion : installerPath.toFile().getName().replace("-installer.jar", "");
        StringBuilder wrapperVersion = new StringBuilder();

        JsonObject pack = convertPackJson(mcVersion);
        JsonObject patches = convertPatchesJson(installer, mcVersion, forgeVersion, wrapperVersion, cursepack);

        Files.createDirectories(targetDir);

        // Copy mmc-pack.json and instance.cfg to <instance> folder.
        Path instancePath = targetDir.resolve(instanceName);
        Files.createDirectories(instancePath);
        Files.copy(new ByteArrayInputStream(pack.toString().getBytes(StandardCharsets.UTF_8)), instancePath.resolve("mmc-pack.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(new ByteArrayInputStream(("InstanceType=OneSix\nname=" + instanceName).getBytes(StandardCharsets.UTF_8)), instancePath.resolve("instance.cfg"), StandardCopyOption.REPLACE_EXISTING);

        // Copy ForgeWrapper to <instance>/libraries folder.
        Path librariesPath = instancePath.resolve("libraries");
        Files.createDirectories(librariesPath);
        Files.copy(Paths.get(Converter.class.getProtectionDomain().getCodeSource().getLocation().toURI()), librariesPath.resolve(wrapperVersion.toString()), StandardCopyOption.REPLACE_EXISTING);

        // Copy net.minecraftforge.json to <instance>/patches folder.
        Path patchesPath = instancePath.resolve("patches");
        Files.createDirectories(patchesPath);
        Files.copy(new ByteArrayInputStream(patches.toString().getBytes(StandardCharsets.UTF_8)), patchesPath.resolve("net.minecraftforge.json"), StandardCopyOption.REPLACE_EXISTING);

        // Copy forge installer to <instance>/.minecraft/.forgewrapper folder.
        Path forgeWrapperPath = instancePath.resolve(".minecraft").resolve(".forgewrapper");
        Files.createDirectories(forgeWrapperPath);
        Files.copy(installerPath, forgeWrapperPath.resolve(forgeFullVersion + "-installer.jar"), StandardCopyOption.REPLACE_EXISTING);

        // Extract all curse pack entries to <instance>/.minecraft folder.
        if (cursepack != null) {
            ZipFile zip = new ZipFile(cursepack);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path targetFolder = forgeWrapperPath.getParent().resolve(entry.getName());
                Files.createDirectories(targetFolder.getParent());
                Files.copy(zip.getInputStream(entry), targetFolder, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static List<String> getAdditionalArgs(Path installerPath) {
        JsonObject installer = getJsonFromZip(installerPath, "version.json");
        return getAdditionalArgs(installer);
    }

    public static List<String> getAdditionalArgs(JsonObject installer) {
        List<String> args = new ArrayList<>();
        getElement(installer.getAsJsonObject("arguments"), "game").getAsJsonArray().iterator().forEachRemaining(je -> args.add(je.getAsString()));
        return args;
    }

    public static JsonObject getJsonFromZip(Path path, String json) {
        try {
            ZipFile zf = new ZipFile(path.toFile());
            ZipEntry versionFile = zf.getEntry(json);
            if (versionFile == null) {
                throw new RuntimeException("The zip file is invalid!");
            }
            InputStreamReader isr = new InputStreamReader(zf.getInputStream(versionFile), StandardCharsets.UTF_8);
            return new JsonParser().parse(isr).getAsJsonObject();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path getForgeInstallerFromCursePack(String cursepack) throws Exception {
        JsonObject manifest = getJsonFromZip(Paths.get(cursepack), "manifest.json");
        JsonObject minecraft = getElement(manifest, "minecraft").getAsJsonObject();
        String mcVersion = getElement(minecraft, "version").getAsString();
        String forgeVersion = null;
        for (JsonElement element : getElement(minecraft, "modLoaders").getAsJsonArray()) {
            String id = getElement(element.getAsJsonObject(), "id").getAsString();
            if (id.startsWith("forge-")) {
                forgeVersion = id.replace("forge-", "");
                break;
            }
        }
        if (forgeVersion == null) {
            throw new RuntimeException("The curse pack is invalid!");
        }
        String packName = getElement(manifest, "name").getAsString();
        String packVersion = getElement(manifest, "version").getAsString();
        Path installer = Paths.get(System.getProperty("java.io.tmpdir", "."), String.format("%s-%s-installer.jar", packName, packVersion));
        Download.download(String.format("https://files.minecraftforge.net/maven/net/minecraftforge/forge/%s-%s/forge-%s-%s-installer.jar", mcVersion, forgeVersion, mcVersion, forgeVersion), installer.toString());
        return installer;
    }

    // Convert mmc-pack.json:
    //   - Replace Minecraft version
    private static JsonObject convertPackJson(String mcVersion) {
        JsonObject pack = new JsonParser().parse(new InputStreamReader(Converter.class.getResourceAsStream("/mmc-pack.json"))).getAsJsonObject();

        for (JsonElement component : getElement(pack, "components").getAsJsonArray()) {
            JsonObject componentObject = component.getAsJsonObject();
            JsonElement version = getElement(componentObject, "version");
            if (!version.isJsonNull() && getElement(componentObject, "uid").getAsString().equals("net.minecraft")) {
                componentObject.addProperty("version", mcVersion);
            }
        }
        return pack;
    }

    // Convert patches/net.minecraftforge.json:
    //   - Add libraries
    //   - Add forge-launcher url
    //   - Replace Minecraft & Forge versions
    private static JsonObject convertPatchesJson(JsonObject installer, String mcVersion, String forgeVersion, StringBuilder wrapperVersion, String cursepack) {
        JsonObject patches = new JsonParser().parse(new InputStreamReader(Converter.class.getResourceAsStream("/patches/net.minecraftforge.json"))).getAsJsonObject();
        JsonArray libraries = getElement(patches, "libraries").getAsJsonArray();

        for (JsonElement lib : libraries) {
            String name = getElement(lib.getAsJsonObject(), "name").getAsString();
            if (name.startsWith("io.github.zekerzhayard:ForgeWrapper:")) {
                wrapperVersion.append(getElement(lib.getAsJsonObject(), "MMC-filename").getAsString());
            }
        }
        if (cursepack != null) {
            JsonObject cursepacklocator = new JsonObject();
            cursepacklocator.addProperty("name", "cpw.mods.forge:cursepacklocator:1.2.0");
            cursepacklocator.addProperty("url", "https://files.minecraftforge.net/maven/");
            libraries.add(cursepacklocator);
        }
        for (JsonElement lib : getElement(installer ,"libraries").getAsJsonArray()) {
            JsonObject artifact = getElement(getElement(lib.getAsJsonObject(), "downloads").getAsJsonObject(), "artifact").getAsJsonObject();
            String path = getElement(artifact, "path").getAsString();
            if (path.equals(String.format("net/minecraftforge/forge/%s-%s/forge-%s-%s.jar", mcVersion, forgeVersion, mcVersion, forgeVersion))) {
                artifact.getAsJsonObject().addProperty("url", "https://files.minecraftforge.net/maven/" + path.replace(".jar", "-launcher.jar"));
            }
            libraries.add(lib);
        }

        patches.addProperty("version", forgeVersion);
        for (JsonElement require : getElement(patches, "requires").getAsJsonArray()) {
            JsonObject requireObject = require.getAsJsonObject();
            if (getElement(requireObject, "uid").getAsString().equals("net.minecraft")) {
                requireObject.addProperty("equals", mcVersion);
            }
        }
        return patches;
    }

    private static JsonElement getElement(JsonObject object, String property) {
        Optional<Map.Entry<String, JsonElement>> first = object.entrySet().stream().filter(e -> e.getKey().equals(property)).findFirst();
        if (first.isPresent()) {
            return first.get().getValue();
        }
        return JsonNull.INSTANCE;
    }
}
