import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Merges several jars into one: classes must not collide, other resources are first-wins,
 * META-INF/services files are concatenated, signatures and module-info are dropped.
 * Usage: java tools/MergeJars.java OUT.jar IN1.jar IN2.jar ...
 */
public final class MergeJars {
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Set<String> seen = new HashSet<>();
        Map<String, StringBuilder> services = new LinkedHashMap<>();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            for (String in : List.of(args).subList(1, args.length)) {
                try (ZipFile z = new ZipFile(in)) {
                    var entries = z.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        String n = e.getName();
                        if (e.isDirectory() || n.equals("module-info.class")) continue;
                        if (n.startsWith("META-INF/") && n.matches(".*\.(SF|DSA|RSA|EC)")) continue;
                        byte[] data = z.getInputStream(e).readAllBytes();
                        if (n.startsWith("META-INF/services/")) {
                            services.computeIfAbsent(n, k -> new StringBuilder())
                                    .append(new String(data, StandardCharsets.UTF_8).strip()).append('\n');
                            continue;
                        }
                        if (!seen.add(n)) {
                            if (n.endsWith(".class")) throw new IllegalStateException("duplicate class " + n + " in " + in);
                            continue;
                        }
                        zip.putNextEntry(new ZipEntry(n));
                        zip.write(data);
                        zip.closeEntry();
                    }
                }
            }
            for (var s : services.entrySet()) {
                zip.putNextEntry(new ZipEntry(s.getKey()));
                zip.write(s.getValue().toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        System.out.println(out + ": " + Files.size(out) + " bytes");
    }
}
