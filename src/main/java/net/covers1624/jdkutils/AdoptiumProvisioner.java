/*
 * This file is part of JdkUtils and is Licensed under the MIT License.
 *
 * Copyright (c) 2018-2021 covers1624 <https://github.com/covers1624>
 */
package net.covers1624.jdkutils;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import net.covers1624.quack.annotation.Requires;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.HttpResponseException;
import net.covers1624.quack.platform.Architecture;
import net.covers1624.quack.platform.OperatingSystem;
import net.covers1624.quack.util.HashUtils;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import static net.covers1624.quack.collection.ColUtils.iterable;

/**
 * A {@link JdkInstallationManager.JdkProvisioner} capable of provisioning
 * JDK's from https://adoptium.net
 * <p>
 * Created by covers1624 on 13/11/21.
 */

@Requires ("org.slf4j:slf4j-api")
@Requires ("com.google.code.gson")
@Requires ("com.google.guava:guava")
@Requires ("org.apache.commons:commons-lang3")
@Requires ("org.apache.commons:commons-compress")
public class AdoptiumProvisioner implements JdkInstallationManager.JdkProvisioner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdoptiumProvisioner.class);
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<AdoptiumRelease>>() { }.getType();

    private static final String ADOPTIUM_URL = "https://api.adoptium.net";
    private static final OperatingSystem OS = OperatingSystem.current();

    private final Supplier<DownloadAction> downloadActionSupplier;

    public AdoptiumProvisioner(Supplier<DownloadAction> downloadActionSupplier) {
        this.downloadActionSupplier = downloadActionSupplier;
    }

    @Override
    @SuppressWarnings ("UnstableApiUsage")
    public Pair<String, Path> provisionJdk(Path baseFolder, JavaVersion version, boolean ignoreMacosAArch64) throws IOException {
        LOGGER.info("Attempting to provision Adoptium JDK for {}.", version);
        List<AdoptiumRelease> releases = getReleases(version, ignoreMacosAArch64);
        if (releases.isEmpty()) throw new FileNotFoundException("Adoptium does not have any releases for " + version);
        AdoptiumRelease release = releases.get(0);
        if (release.binaries.isEmpty()) throw new FileNotFoundException("Adoptium returned a release, but no binaries? " + version);
        if (release.binaries.size() != 1) {
            LOGGER.warn("Adoptium returned more than one binary! Api change? Using first!");
        }

        AdoptiumRelease.Binary binary = release.binaries.get(0);
        AdoptiumRelease.Package pkg = binary._package;
        LOGGER.info("Found release '{}', Download '{}'", release.version_data.semver, pkg.link);

        Path tempFile = baseFolder.resolve(pkg.name);
        tempFile.toFile().deleteOnExit();
        DownloadAction action = downloadActionSupplier.get();
        action.setUrl(pkg.link);
        action.setDest(tempFile);
        action.execute();

        long size = Files.size(tempFile);
        HashCode hash = HashUtils.hash(Hashing.sha256(), tempFile);
        if (size != pkg.size) {
            throw new IOException("Invalid Adoptium download - Size incorrect. Expected: " + pkg.size + ", Got: " + size);
        }
        if (!HashUtils.equals(hash, pkg.checksum)) {
            throw new IOException("Invalid Adoptium download - SHA256 Hash incorrect. Expected: " + pkg.checksum + ", Got: " + hash);
        }

        Path extractedFolder = extract(baseFolder, tempFile);

        return Pair.of(release.version_data.semver, extractedFolder);
    }

    private List<AdoptiumRelease> getReleases(JavaVersion version, boolean ignoreMacosAArch64) throws IOException {
        DownloadAction action = downloadActionSupplier.get();
        Architecture architecture = Architecture.current();
        if (OS.isMacos() && architecture == Architecture.AARCH64 && ignoreMacosAArch64) {
            LOGGER.info("Forcing x64 JDK for macOS AArch64.");
            architecture = Architecture.X64;
        }
        StringWriter sw = new StringWriter();
        action.setUrl(makeURL(version, architecture));
        action.setDest(sw);
        try {
            action.execute();
        } catch (HttpResponseException ex) {
            if (ex.code != 404 || !OS.isMacos() || architecture != Architecture.AARCH64) {
                throw ex;
            }

            LOGGER.warn("Failed to find AArch64 macOS jdk for java {}. Trying x64.", version);
            // Try again, but let's get an ADM64 build because Rosetta exists.
            return getReleases(version, true);
        }
        return AdoptiumRelease.parseReleases(sw.toString());
    }

    private static String makeURL(JavaVersion version, Architecture architecture) {
        String platform;
        if (OS.isWindows()) {
            platform = "windows";
        } else if (OS.isLinux()) {
            platform = "linux";
        } else if (OS.isMacos()) {
            platform = "mac";
        } else {
            throw new UnsupportedOperationException("Unsupported operating system.");
        }
        return ADOPTIUM_URL
                + "/v3/assets/feature_releases/"
                + version.shortString
                + "/ga"
                + "?project=jdk"
                + "&image_type=jdk"
                + "&vendor=eclipse"
                + "&jvm_impl=hotspot"
                + "&heap_size=normal"
                + "&architecture=" + architecture.name().toLowerCase(Locale.ROOT)
                + "&os=" + platform;
    }

    private static Path extract(Path jdksDir, Path jdkArchive) throws IOException {
        LOGGER.info("Extracting Adoptium archive '{}' into '{}' ", jdkArchive, jdksDir);
        Path jdkDir = jdksDir.resolve(getBasePath(jdkArchive));
        try (ArchiveInputStream is = createStream(jdkArchive)) {
            ArchiveEntry entry;
            while ((entry = is.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path file = jdksDir.resolve(entry.getName()).toAbsolutePath();
                Files.createDirectories(file.getParent());
                try (OutputStream os = Files.newOutputStream(file)) {
                    IOUtils.copy(is, os);
                }
            }
        }

        if (OS.isMacos() || OS.isLinux()) {
            makeExecutable(JavaInstall.getBinDirectory(jdkDir));
        }
        return jdkDir;
    }

    private static String getBasePath(Path jdkArchive) throws IOException {
        try (ArchiveInputStream is = createStream(jdkArchive)) {
            ArchiveEntry entry;
            while ((entry = is.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    return entry.getName();
                }
            }
        }
        throw new RuntimeException("Unable to find base path for archive. " + jdkArchive);
    }

    private static ArchiveInputStream createStream(Path jdkArchive) throws IOException {
        String fileName = jdkArchive.getFileName().toString();
        if (fileName.endsWith(".tar.gz")) {
            return new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(jdkArchive)));
        }
        if (fileName.endsWith(".zip")) {
            return new ZipArchiveInputStream(Files.newInputStream(jdkArchive));
        }
        throw new UnsupportedOperationException("Unable to determine archive format of file: " + fileName);
    }

    private static void makeExecutable(Path binFolder) throws IOException {
        for (Path path : iterable(Files.list(binFolder))) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxr-x"));
        }
    }

    public static class AdoptiumRelease {

        public static List<AdoptiumRelease> parseReleases(String json) throws IOException, JsonParseException {
            return JsonUtils.parse(GSON, new StringReader(json), LIST_TYPE);
        }

        public List<Binary> binaries = new ArrayList<>();
        public String release_name;
        public VersionData version_data;

        public static class Binary {

            @SerializedName ("package")
            public Package _package;
        }

        public static class Package {

            public String checksum;
            public String link;
            public String name;
            public int size;
        }

        public static class VersionData {

            public String semver;
        }
    }
}