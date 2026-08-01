package jp.cobolinsight.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 資産フォルダから解析対象のソースを発見する。scan・lint・sql-advise・transpile・fix の
 * 各 Runner はこのクラスだけを走査の入口とする。
 *
 * <p>走査は二段構えである。まず従来のフォルダ規約(INPUT_DIR 直下の bms・cobol・copy|copybook・
 * jcl を1階層、種別ごとの固定拡張子)で試し、1件以上拾えたらそれを採る。1件も拾えないときだけ
 * INPUT_DIR 配下を再帰的に走査し、拡張子で種別を決める。判定を「規約ディレクトリの存在」では
 * なく「1件以上拾えたか」に置くのは、空の cobol/ や、Windows のパス比較が大小を無視するために
 * COPY/ だけが規約名に一致する構成で、存在判定だと再帰へ落ちないまま資産を全て落とすためである。
 *
 * <p>従来規約を採った場合、規約の外に残った対象拡張子のファイルは走査対象へ加えず、
 * {@link Result#outsideConvention()} として数え上げる。取り込み対象が黙って欠けることを
 * 呼び出し側が利用者へ伝えられるようにするためで、走査対象そのものは従来と変わらない。
 */
final class SourceDiscovery {

    /** 走査で扱う資産の種別。 */
    enum Kind {
        BMS, COBOL, COPYBOOK, JCL
    }

    /** どちらの規約で走査したか。呼び出し側が利用者への案内を出し分けるために用いる。 */
    enum Mode {
        CONVENTION, RECURSIVE
    }

    /** 発見した1ファイル。relPath は INPUT_DIR からの相対パスで区切りは / へそろえる。 */
    record DiscoveredFile(String relPath, Path absPath, Kind kind) {

        String fileName() {
            return absPath.getFileName().toString();
        }
    }

    /**
     * 走査の結果。outsideConvention は従来規約を採ったときに規約の外へ残った対象ファイルの
     * 相対パス({@link #MAX_OUTSIDE_SAMPLES} 件まで)で、files には含まれない。
     */
    record Result(List<DiscoveredFile> files, Mode mode, boolean truncated,
            List<String> outsideConvention, int outsideConventionCount) {

        Result {
            files = List.copyOf(files);
            outsideConvention = List.copyOf(outsideConvention);
        }

        /** 指定した種別だけを取り出す。Runner ごとに要る種別が違うため呼び出し側で絞る。 */
        List<DiscoveredFile> filesOf(Set<Kind> kinds) {
            return files.stream().filter(f -> kinds.contains(f.kind())).toList();
        }
    }

    /** 再帰走査の深さ上限。INPUT_DIR 直下を1段目と数える。 */
    static final int MAX_DEPTH = 10;

    /**
     * 再帰走査のファイル数上限。SOURCE.id が 1,000,000 未満という採番の不変条件
     * ({@link ScanRunner} のクラス Javadoc)に対して十分小さく、上限に達しても採番を脅かさない。
     */
    static final int MAX_FILES = 5_000;

    /** 規約外として報告する相対パスの件数上限。総数は別に数える。 */
    static final int MAX_OUTSIDE_SAMPLES = 10;

    /** 従来規約のディレクトリ名と種別。copy と copybook はどちらもコピー句を置く。 */
    private static final Map<String, Kind> KIND_BY_DIR = new LinkedHashMap<>();

    /** 従来規約が受け付ける種別ごとの拡張子。 */
    private static final Map<Kind, String> CONVENTION_EXTENSION = new LinkedHashMap<>();

    /** 再帰走査が受け付ける拡張子と種別。 */
    private static final Map<String, Kind> KIND_BY_EXTENSION = new LinkedHashMap<>();

    /** 再帰走査で降りないディレクトリ名。名前が . で始まるものも併せて除く。 */
    private static final Set<String> EXCLUDED_DIRS =
            Set.of("node_modules", "build", "target", "out", "dist");

    static {
        KIND_BY_DIR.put("bms", Kind.BMS);
        KIND_BY_DIR.put("cobol", Kind.COBOL);
        KIND_BY_DIR.put("copy", Kind.COPYBOOK);
        KIND_BY_DIR.put("copybook", Kind.COPYBOOK);
        KIND_BY_DIR.put("jcl", Kind.JCL);

        CONVENTION_EXTENSION.put(Kind.BMS, ".bms");
        CONVENTION_EXTENSION.put(Kind.COBOL, ".cbl");
        CONVENTION_EXTENSION.put(Kind.COPYBOOK, ".cpy");
        CONVENTION_EXTENSION.put(Kind.JCL, ".jcl");

        KIND_BY_EXTENSION.put(".cbl", Kind.COBOL);
        KIND_BY_EXTENSION.put(".cob", Kind.COBOL);
        KIND_BY_EXTENSION.put(".cobol", Kind.COBOL);
        KIND_BY_EXTENSION.put(".cpy", Kind.COPYBOOK);
        KIND_BY_EXTENSION.put(".copy", Kind.COPYBOOK);
        KIND_BY_EXTENSION.put(".jcl", Kind.JCL);
        KIND_BY_EXTENSION.put(".bms", Kind.BMS);
    }

    private SourceDiscovery() {
    }

    /** 資産フォルダを走査する。存在しないフォルダを渡した場合は空の結果を返す。 */
    static Result discover(Path inputDir) {
        List<DiscoveredFile> byConvention = discoverByConvention(inputDir);
        if (!byConvention.isEmpty()) {
            List<String> outside = new ArrayList<>();
            int[] outsideCount = {0};
            collectOutsideConvention(inputDir, outside, outsideCount);
            return new Result(byConvention, Mode.CONVENTION, false, outside, outsideCount[0]);
        }
        RecursiveScan scan = new RecursiveScan(inputDir);
        scan.walk(inputDir, 1);
        List<DiscoveredFile> files = new ArrayList<>(scan.files);
        files.sort(Comparator.comparing(DiscoveredFile::relPath));
        return new Result(files, Mode.RECURSIVE, scan.truncated, List.of(), 0);
    }

    /** 従来規約(規約ディレクトリを1階層、種別ごとの固定拡張子)で走査する。 */
    private static List<DiscoveredFile> discoverByConvention(Path inputDir) {
        List<DiscoveredFile> files = new ArrayList<>();
        for (Map.Entry<String, Kind> entry : KIND_BY_DIR.entrySet()) {
            Path dir = inputDir.resolve(entry.getKey());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            String extension = CONVENTION_EXTENSION.get(entry.getValue());
            try (Stream<Path> children = Files.list(dir)) {
                children.filter(Files::isRegularFile)
                        .filter(p -> hasExtension(p, extension))
                        .forEach(p -> files.add(new DiscoveredFile(
                                entry.getKey() + "/" + p.getFileName(), p, entry.getValue())));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.sort(Comparator.comparing(DiscoveredFile::relPath));
        return files;
    }

    /**
     * 従来規約で拾われなかった対象拡張子のファイルを数える。規約ディレクトリ配下のサブフォルダ
     * (1階層しか見ないため対象外)と、規約名でないディレクトリの双方が該当する。
     */
    private static void collectOutsideConvention(Path inputDir, List<String> samples,
            int[] count) {
        RecursiveScan scan = new RecursiveScan(inputDir);
        scan.walk(inputDir, 1);
        for (DiscoveredFile file : scan.files) {
            if (isCoveredByConvention(file)) {
                continue;
            }
            count[0]++;
            if (samples.size() < MAX_OUTSIDE_SAMPLES) {
                samples.add(file.relPath());
            }
        }
        samples.sort(Comparator.naturalOrder());
    }

    /** 従来規約が拾う位置と拡張子に合致するか。 */
    private static boolean isCoveredByConvention(DiscoveredFile file) {
        int slash = file.relPath().indexOf('/');
        if (slash < 0 || file.relPath().indexOf('/', slash + 1) >= 0) {
            return false;
        }
        Kind dirKind = KIND_BY_DIR.get(file.relPath().substring(0, slash).toLowerCase(Locale.ROOT));
        if (dirKind == null) {
            return false;
        }
        return hasExtension(file.absPath(), CONVENTION_EXTENSION.get(dirKind));
    }

    private static boolean hasExtension(Path file, String extension) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension);
    }

    /** 再帰走査の作業状態。深さとファイル数の上限に達したら打ち切る。 */
    private static final class RecursiveScan {

        private final Path inputDir;
        private final List<DiscoveredFile> files = new ArrayList<>();
        private boolean truncated;

        private RecursiveScan(Path inputDir) {
            this.inputDir = inputDir;
        }

        private void walk(Path dir, int depth) {
            if (depth > MAX_DEPTH) {
                truncated = true;
                return;
            }
            if (!Files.isDirectory(dir)) {
                return;
            }
            List<Path> children;
            try (Stream<Path> stream = Files.list(dir)) {
                children = stream.sorted().toList();
            } catch (IOException e) {
                // 読めないディレクトリで走査全体を止めない。取りこぼしたことだけを伝える。
                truncated = true;
                return;
            }
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    if (!isExcluded(child)) {
                        walk(child, depth + 1);
                    }
                    continue;
                }
                if (!Files.isRegularFile(child)) {
                    continue;
                }
                Kind kind = kindOf(child);
                if (kind == null) {
                    continue;
                }
                if (files.size() >= MAX_FILES) {
                    truncated = true;
                    return;
                }
                files.add(new DiscoveredFile(relativize(child), child, kind));
            }
        }

        private boolean isExcluded(Path dir) {
            String name = dir.getFileName().toString();
            return name.startsWith(".") || EXCLUDED_DIRS.contains(name.toLowerCase(Locale.ROOT));
        }

        private Kind kindOf(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            return dot < 0 ? null : KIND_BY_EXTENSION.get(name.substring(dot));
        }

        private String relativize(Path file) {
            return inputDir.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        }
    }
}
