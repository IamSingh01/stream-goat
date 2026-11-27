package com.lagradost.cloudstream3.linux;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Starts the app, with the installed update patch when there is one (Settings → Updates and backup).
 *
 * A patch is RoarStream's own jars (app, cs-library, android-shim) from a newer build, kept in
 * {@code <data folder>/update}. It's used only when its signature checks out with the key the installed app was
 * built with, its jars are the ones it signed, and it was made for this installation (a newly installed version
 * replaces it). A patch that didn't get the app to its window is dropped on the next start, so a bad one can't
 * keep the app from opening. Written in Java with nothing but the JDK: it runs before anything else is loaded.
 */
public final class Boot {
    static final String MAIN = "com.lagradost.cloudstream3.linux.MainKt";

    public static void main(String[] args) throws Throwable {
        ClassLoader loader = null;
        // The app's window (as MainKt decides); commands from a terminal run the installed version.
        boolean gui = args.length == 0 || args[0].equals("gui") || args[0].startsWith("cloudstreamrepo://") || args[0].startsWith("https://cs.repo");
        try {
            if (gui) loader = patched();
        } catch (Throwable t) {
            System.err.println("update: not used (" + t + ")");
        }
        if (loader == null) loader = Boot.class.getClassLoader();
        else Thread.currentThread().setContextClassLoader(loader);
        try {
            loader.loadClass(MAIN).getMethod("main", String[].class).invoke(null, (Object) args);
            // Ran to the end (a command, or the window was closed): the patch works.
            started();
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Same rules as Os.defaultDataDir / AppDirs.data. */
    static File dataDir() {
        String set = System.getProperty("cloudstream.home");
        if (set == null) set = System.getenv("CLOUDSTREAM_HOME");
        if (set != null) return new File(set);
        String os = System.getProperty("os.name", "");
        String home = System.getProperty("user.home");
        if (os.startsWith("Mac")) {
            File base = new File(home, "Library/Application Support");
            return migrate(new File(base, "RoarStream"), new File(base, "Stream Goat"));
        }
        if (os.regionMatches(true, 0, "Windows", 0, 7)) {
            String appData = System.getenv("APPDATA");
            File base = new File(appData != null ? appData : home + "\\AppData\\Roaming");
            File current = new File(base, "RoarStream");
            File previous = new File(base, "Stream Goat");
            File older = new File(base, "CloudStream");
            return migrate(current, previous.isDirectory() ? previous : older);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        File base = new File(xdg != null ? xdg : home + "/.local/share");
        return migrate(new File(base, "RoarStream"), new File(base, "cloudstream-linux"));
    }

    /** Keep legacy user data usable if the first-launch rename cannot be completed. */
    private static File migrate(File current, File legacy) {
        if (!current.exists() && legacy.isDirectory() && !legacy.renameTo(current)) return legacy;
        return current;
    }

    static File updateDir() { return new File(dataDir(), "update"); }

    /** A newly downloaded patch waits here until the next start (Windows can't replace jars that are in use). */
    public static File nextDir() { return new File(dataDir(), "update.next"); }

    /** The installed app.jar: its size and date identify the installation a patch was made for. */
    static File installedAppJar() {
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            File f = new File(entry);
            if (f.getName().equals("app.jar")) return f;
        }
        return null;
    }

    static String installation() {
        File jar = installedAppJar();
        return jar == null ? null : jar.length() + ":" + jar.lastModified();
    }

    private static ClassLoader patched() throws Exception {
        File dir = updateDir();
        File next = nextDir();
        if (new File(next, "patch.properties").isFile()) {
            deleteTree(dir);
            if (!next.renameTo(dir)) System.err.println("update: couldn't move the new update into place");
        }
        File props = new File(dir, "patch.properties");
        if (!props.isFile()) return null;
        File starting = new File(dir, ".starting");
        if (starting.exists()) {
            System.err.println("update: the last start with the update didn't open the app; using the installed version");
            deleteTree(dir);
            return null;
        }
        String made = read(new File(dir, "installation.txt")).trim();
        if (!made.equals(installation())) {
            // Another version was installed since: the patch belongs to the old one.
            deleteTree(dir);
            return null;
        }
        byte[] text = Files.readAllBytes(props.toPath());
        if (!verify(text, Files.readAllBytes(new File(dir, "patch.sig").toPath()))) {
            System.err.println("update: signature doesn't match; ignored");
            deleteTree(dir);
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = new java.io.ByteArrayInputStream(text)) { p.load(in); }
        List<URL> urls = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("jar.")) continue;
            String name = key.substring(4);
            File jar = new File(dir, name);
            if (!jar.isFile() || !sha256(jar).equalsIgnoreCase(p.getProperty(key))) {
                System.err.println("update: " + name + " is damaged; ignored");
                deleteTree(dir);
                return null;
            }
            urls.add(jar.toURI().toURL());
            replaced.add(name);
        }
        if (urls.isEmpty()) return null;
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            File f = new File(entry);
            if (!replaced.contains(f.getName())) urls.add(f.toURI().toURL());
        }
        Files.write(starting.toPath(), new byte[0]);
        System.err.println("update: starting " + p.getProperty("version") + " (" + p.getProperty("build") + ")");
        System.setProperty("roarstream.patch", p.getProperty("version", "") + " " + p.getProperty("build", ""));
        // Nothing is shared with the installed copies: every class (JavaFX, VLC, the app) comes from this loader.
        return new URLClassLoader("roarstream-update", urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /** Checks [sig] over [data] with the certificate the installed app was built with (none: patches are off). */
    public static boolean verify(byte[] data, byte[] sig) {
        try (InputStream cert = Boot.class.getResourceAsStream("/roarstream-update.pem")) {
            if (cert == null) return false;
            PublicKey key = CertificateFactory.getInstance("X.509").generateCertificate(cert).getPublicKey();
            Signature s = Signature.getInstance(key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA");
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** True when this build can check updates' signatures (built by the project's GitHub builds). */
    public static boolean canVerify() {
        return Boot.class.getResource("/roarstream-update.pem") != null;
    }

    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String read(File f) throws Exception {
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), "UTF-8") : "";
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    /** The app reached its window: the patch works, keep it. */
    public static void started() {
        new File(updateDir(), ".starting").delete();
    }
}
