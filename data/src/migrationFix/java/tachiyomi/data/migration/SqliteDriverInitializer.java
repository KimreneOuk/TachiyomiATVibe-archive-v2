package tachiyomi.data.migration;

import app.cash.sqldelight.core.SqlDelightDatabaseProperties;
import app.cash.sqldelight.gradle.DriverInitializer;
import java.io.File;
import java.util.Map;
import java.util.Properties;

// Workaround for gradle/gradle#22661 where worker isolation drops Windows temp env vars.
// Leaves non-Windows platforms untouched and validates directory writability explicitly.
public class SqliteDriverInitializer implements DriverInitializer {
    @Override
    public void execute(SqlDelightDatabaseProperties properties, Properties driverProperties) {
        String osName = System.getProperty("os.name");
        String currentTmpDir = System.getProperty("java.io.tmpdir");
        String userHome = System.getProperty("user.home");
        File targetDir = resolveSafeTempDirectory(osName, currentTmpDir, System.getenv(), userHome);
        if (targetDir != null) {
            System.setProperty("org.sqlite.tmpdir", targetDir.getAbsolutePath());
            System.setProperty("java.io.tmpdir", targetDir.getAbsolutePath());
        }
    }

    static File resolveSafeTempDirectory(
        String osName,
        String currentTmpDir,
        Map<String, String> env,
        String userHome
    ) {
        if (osName == null || !osName.toLowerCase().contains("win")) {
            return null;
        }

        String winDir = env != null ? env.get("SystemRoot") : null;
        if (winDir == null || winDir.isEmpty()) {
            winDir = env != null ? env.get("windir") : null;
        }
        if (winDir == null || winDir.isEmpty()) {
            winDir = "C:\\WINDOWS";
        }
        File systemDir = new File(winDir);

        if (currentTmpDir != null && !currentTmpDir.isEmpty()) {
            File current = new File(currentTmpDir);
            if (current.exists() && current.canWrite() && !isSamePath(current, systemDir)) {
                return null;
            }
        }

        String[] candidates = new String[] {
            env != null ? env.get("TEMP") : null,
            env != null ? env.get("TMP") : null,
            env != null && env.get("LOCALAPPDATA") != null
                ? env.get("LOCALAPPDATA") + File.separator + "Temp"
                : null,
            userHome != null
                ? userHome + File.separator + "AppData" + File.separator + "Local" + File.separator + "Temp"
                : null
        };

        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            File dir = new File(candidate.trim());
            if (!dir.exists() && !dir.mkdirs()) {
                continue;
            }
            if (dir.isDirectory() && dir.canWrite()) {
                return dir;
            }
        }

        throw new IllegalStateException(
            "SqliteDriverInitializer: Failed to resolve a writable temporary directory on Windows."
        );
    }

    private static boolean isSamePath(File a, File b) {
        try {
            return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
        }
    }
}
