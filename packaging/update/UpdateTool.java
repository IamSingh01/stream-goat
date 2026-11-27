import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * Desktop update patches, made by the GitHub builds (run with `java packaging/update/UpdateTool.java ...`).
 *
 *   cert <keystore> <storepass> <alias> <out.pem>
 *       The signing key's certificate, built into the app so it can check patches (Boot.verify).
 *   make <libdir> <outdir> <os> <version> <build> <keystore> <storepass> <alias> <keypass>
 *       outdir/roarstream-update-<os>.zip: RoarStream's own jars, patch.properties (version, build, the jars'
 *       SHA-256 and the SHA-256 of every other jar it needs unchanged) and patch.sig, its signature;
 *       outdir/roarstream-update-<os>.properties: the same properties, for checking without the download.
 *
 * The key is the Android signing key (GitHub secrets), so no new secret is needed and nobody else can sign one.
 */
public class UpdateTool {
    static final Set<String> OURS = Set.of("app.jar", "cs-library-jvm.jar", "android-shim.jar");

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "cert" -> {
                KeyStore ks = load(a[1], a[2]);
                Certificate cert = ks.getCertificate(a[3]);
                String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
                Files.writeString(Path.of(a[4]), pem);
            }
            case "make" -> make(Path.of(a[1]), Path.of(a[2]), a[3], a[4], a[5], load(a[6], a[7]), a[8], a[9]);
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static KeyStore load(String file, String pass) throws Exception {
        for (String type : List.of("PKCS12", "JKS")) {
            try (InputStream in = Files.newInputStream(Path.of(file))) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, pass.toCharArray());
                return ks;
            } catch (Exception ignored) { }
        }
        throw new IllegalStateException("can't read the keystore");
    }

    static void make(Path lib, Path out, String os, String version, String build, KeyStore ks, String alias, String keyPass)
            throws Exception {
        Files.createDirectories(out);
        StringBuilder props = new StringBuilder();
        props.append("version=").append(version).append('\n');
        props.append("build=").append(build).append('\n');
        props.append("os=").append(os).append('\n');
        List<Path> jars;
        try (var s = Files.list(lib)) { jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            props.append(OURS.contains(name) ? "jar." : "requires.").append(name).append('=').append(sha256(jar)).append('\n');
        }
        byte[] text = props.toString().getBytes("UTF-8");
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass.toCharArray());
        Signature s = Signature.getInstance(key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA");
        s.initSign(key);
        s.update(text);
        byte[] sig = s.sign();
        Files.write(out.resolve("roarstream-update-" + os + ".properties"), text);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out.resolve("roarstream-update-" + os + ".zip")))) {
            put(zip, "patch.properties", text);
            put(zip, "patch.sig", sig);
            for (Path jar : jars) if (OURS.contains(jar.getFileName().toString())) put(zip, jar.getFileName().toString(), Files.readAllBytes(jar));
        }
    }

    static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    static String sha256(Path file) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
