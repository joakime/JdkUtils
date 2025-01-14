package net.covers1624.jdkutils;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.okhttp.OkHttpDownloadAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Arrays.asList;

/**
 * Created by covers1624 on 25/11/21.
 */
public class InstallationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocatorTest.class);

    public static void main(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();

        OptionSpec<Void> helpOpt = parser.acceptsAll(asList("h", "help"), "Prints this help").forHelp();
        OptionSpec<String> javaVersionOpt = parser.accepts("version", "The java version.")
                .withRequiredArg()
                .defaultsTo("16");
        OptionSpec<String> semverOpt = parser.accepts("semver", "The java semver version.")
                .withRequiredArg();
        OptionSpec<Void> ignoreMacosAArch64 = parser.accepts("ignore-mac-aarch64", "If AArch64 Mac should be treated as X64.");
        OptionSpec<Void> jreOnly = parser.accepts("jre", "If a JRE is all that is required.");
        OptionSet optSet = parser.parse(args);

        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            System.exit(-1);
        }

        JavaVersion javaVersion = JavaVersion.parse(optSet.valueOf(javaVersionOpt));
        JdkInstallationManager jdkInstallationManager = new JdkInstallationManager(
                Paths.get("jdks"),
                new AdoptiumProvisioner(() -> {
                    DownloadAction action = new OkHttpDownloadAction();
                    action.setQuiet(false);
                    action.setDownloadListener(new StatusDownloadListener());
                    return action;
                }),
                optSet.has(ignoreMacosAArch64)
        );
        assert javaVersion != null;
        Path homeDir = jdkInstallationManager.provisionJdk(javaVersion, optSet.valueOf(semverOpt), optSet.has(jreOnly), null);
        LOGGER.info("Provisioned Java home installation: {}", homeDir);

        LOGGER.info("Testing installed JDK..");
        JavaInstall install = JavaLocator.parseInstall(JavaInstall.getJavaExecutable(homeDir, false));
        if (install == null) {
            LOGGER.info("Failed to parse java install.");
        } else {
            LOGGER.info("Version: '{}', Lang version {}, Home '{}', Has Compiler: '{}'", install.implVersion, install.langVersion, install.javaHome, install.hasCompiler);
        }
    }
}
