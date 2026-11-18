package tachiyomi.data.migration;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// Validates platform-selective tempdir resolution and fallback behavior.
public class SqliteDriverInitializerTest {
    public static void main(String[] args) throws Exception {
        testNonWindowsPlatform();
        testWindowsWithValidWritableTemp();
        testWindowsWithSystemDirAndTempSet();
        testWindowsWithNoTempFallback();
        testWindowsUnwritableThrows();
        System.out.println("[SqliteDriverInitializerTest] All 5 test cases PASSED successfully.");
    }

    private static void testNonWindowsPlatform() {
        File linuxResult = SqliteDriverInitializer.resolveSafeTempDirectory(
            "Linux",
            "/tmp",
            Collections.emptyMap(),
            "/home/user"
        );
        if (linuxResult != null) {
            throw new AssertionError("Expected null on Linux, got: " + linuxResult);
        }

        File macResult = SqliteDriverInitializer.resolveSafeTempDirectory(
            "Mac OS X",
            "/var/folders/xx/test/T",
            Collections.emptyMap(),
            "/Users/user"
        );
        if (macResult != null) {
            throw new AssertionError("Expected null on macOS, got: " + macResult);
        }
    }

    private static void testWindowsWithValidWritableTemp() throws Exception {
        File tempDir = File.createTempFile("valid_temp", "");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            Map<String, String> env = new HashMap<>();
            env.put("SystemRoot", "C:\\WINDOWS");

            File result = SqliteDriverInitializer.resolveSafeTempDirectory(
                "Windows 11",
                tempDir.getAbsolutePath(),
                env,
                "mock_home"
            );
            if (result != null) {
                throw new AssertionError("Expected null when Windows tempdir is already writable, got: " + result);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testWindowsWithSystemDirAndTempSet() throws Exception {
        File tempDir = File.createTempFile("fallback_temp", "");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            Map<String, String> env = new HashMap<>();
            env.put("SystemRoot", "C:\\WINDOWS");
            env.put("TEMP", tempDir.getAbsolutePath());

            File result = SqliteDriverInitializer.resolveSafeTempDirectory(
                "Windows 11",
                "C:\\WINDOWS",
                env,
                "mock_home"
            );
            if (result == null || !result.exists() || !result.canWrite()) {
                throw new AssertionError("Expected writable fallback from TEMP, got: " + result);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testWindowsWithNoTempFallback() throws Exception {
        File isolatedHome = File.createTempFile("isolated_home", "");
        isolatedHome.delete();
        isolatedHome.mkdirs();
        try {
            Map<String, String> env = new HashMap<>();
            env.put("SystemRoot", "C:\\WINDOWS");

            File result = SqliteDriverInitializer.resolveSafeTempDirectory(
                "Windows 11",
                "C:\\WINDOWS",
                env,
                isolatedHome.getAbsolutePath()
            );
            if (result == null || !result.exists() || !result.canWrite()) {
                throw new AssertionError("Expected fallback to userHome/AppData/Local/Temp, got: " + result);
            }
            File expectedDir = new File(
                isolatedHome,
                "AppData" + File.separator + "Local" + File.separator + "Temp"
            );
            if (!result.getCanonicalPath().equalsIgnoreCase(expectedDir.getCanonicalPath())) {
                throw new AssertionError("Expected " + expectedDir + ", got: " + result);
            }
        } finally {
            deleteRecursively(isolatedHome);
        }
    }

    private static void testWindowsUnwritableThrows() throws Exception {
        File blockedAncestor = File.createTempFile("blocked_ancestor", ".tmp");
        try {
            Map<String, String> env = new HashMap<>();
            env.put("SystemRoot", "C:\\WINDOWS");
            env.put("TEMP", blockedAncestor.getAbsolutePath() + File.separator + "temp");
            env.put("TMP", blockedAncestor.getAbsolutePath() + File.separator + "tmp");
            env.put("LOCALAPPDATA", blockedAncestor.getAbsolutePath() + File.separator + "local");

            boolean threw = false;
            try {
                SqliteDriverInitializer.resolveSafeTempDirectory(
                    "Windows 11",
                    "C:\\WINDOWS",
                    env,
                    blockedAncestor.getAbsolutePath() + File.separator + "home"
                );
            } catch (IllegalStateException e) {
                threw = true;
            }
            if (!threw) {
                throw new AssertionError("Expected IllegalStateException when no candidate is writable");
            }
        } finally {
            deleteRecursively(blockedAncestor);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
