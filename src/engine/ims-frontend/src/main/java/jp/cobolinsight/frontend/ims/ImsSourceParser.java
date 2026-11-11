package jp.cobolinsight.frontend.ims;

import jp.cobolinsight.core.ims.ImsDbd;
import jp.cobolinsight.core.ims.ImsPsb;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads DBDGEN and PSBGEN source. One member may hold several generations one after another, each
 * closed by its own DBDGEN or PSBGEN statement, so both readers return a list.
 *
 * <p>A database or a PSB whose closing statement names it nowhere is named after the member, which
 * is the name the generation is link-edited under.
 */
public final class ImsSourceParser {

    /** The DBDs of one member, in source order. */
    public List<ImsDbd> parseDbd(DecodedSource source) {
        List<ImsDbd> dbds = new ArrayList<>();
        String name = null;
        String access = "";
        int line = 1;
        List<ImsDbd.Segment> segments = new ArrayList<>();
        String segmentName = null;
        Optional<String> segmentParent = Optional.empty();
        Optional<Integer> segmentBytes = Optional.empty();
        int segmentLine = 1;
        List<String> fields = new ArrayList<>();
        for (MacroStatements.Statement statement : MacroStatements.read(source.text())) {
            switch (statement.operation()) {
                case "DBD" -> {
                    name = statement.value("NAME");
                    access = String.join(",", statement.list("ACCESS"));
                    line = statement.line();
                }
                case "SEGM" -> {
                    if (segmentName != null) {
                        segments.add(new ImsDbd.Segment(segmentName, segmentParent, segmentBytes,
                                fields, at(source, segmentLine)));
                    }
                    segmentName = statement.value("NAME");
                    segmentLine = statement.line();
                    fields = new ArrayList<>();
                    // PARENT=0 is the root; PARENT=((NAME,SNGL),…) names the physical parent first.
                    segmentParent = statement.list("PARENT").stream().findFirst()
                            .map(parent -> parent.replace("(", "").replace(")", ""))
                            .map(parent -> parent.split(",")[0].strip())
                            .filter(parent -> !parent.isEmpty() && !parent.equals("0"));
                    segmentBytes = statement.list("BYTES").stream().findFirst()
                            .filter(bytes -> bytes.matches("\\d+")).map(Integer::parseInt);
                }
                case "FIELD" -> {
                    // NAME=(CUSTNO,SEQ,U): the first item is the field name.
                    statement.list("NAME").stream().findFirst().ifPresent(fields::add);
                }
                case "DBDGEN" -> {
                    if (segmentName != null) {
                        segments.add(new ImsDbd.Segment(segmentName, segmentParent, segmentBytes,
                                fields, at(source, segmentLine)));
                    }
                    dbds.add(new ImsDbd(name == null ? memberNameOf(source.path()) : name,
                            access, segments, source.path(), at(source, line)));
                    name = null;
                    access = "";
                    segments = new ArrayList<>();
                    segmentName = null;
                    fields = new ArrayList<>();
                }
                default -> {
                }
            }
        }
        if (name != null) {
            // A source cut off before DBDGEN still states the database it describes.
            if (segmentName != null) {
                segments.add(new ImsDbd.Segment(segmentName, segmentParent, segmentBytes, fields,
                        at(source, segmentLine)));
            }
            dbds.add(new ImsDbd(name, access, segments, source.path(), at(source, line)));
        }
        return dbds;
    }

    /** The PSBs of one member, in source order. */
    public List<ImsPsb> parsePsb(DecodedSource source) {
        List<ImsPsb> psbs = new ArrayList<>();
        List<ImsPsb.Pcb> pcbs = new ArrayList<>();
        String type = null;
        Optional<String> dbdName = Optional.empty();
        Optional<String> procopt = Optional.empty();
        int pcbLine = 1;
        int firstLine = 0;
        List<ImsPsb.Senseg> segments = new ArrayList<>();
        for (MacroStatements.Statement statement : MacroStatements.read(source.text())) {
            switch (statement.operation()) {
                case "PCB" -> {
                    if (type != null) {
                        pcbs.add(new ImsPsb.Pcb(type, dbdName, procopt, segments,
                                at(source, pcbLine)));
                    }
                    if (firstLine == 0) {
                        firstLine = statement.line();
                    }
                    type = Optional.ofNullable(statement.value("TYPE")).orElse("DB");
                    // DBDNAME= is the usual spelling; NAME= is accepted for it on a DB PCB, and
                    // on a TP PCB names a destination instead.
                    dbdName = type.equals("TP") ? Optional.empty()
                            : Optional.ofNullable(statement.value("DBDNAME"))
                                    .or(() -> Optional.ofNullable(statement.value("NAME")));
                    procopt = Optional.ofNullable(statement.value("PROCOPT"));
                    pcbLine = statement.line();
                    segments = new ArrayList<>();
                }
                case "SENSEG" -> {
                    String name = statement.value("NAME");
                    if (name != null) {
                        segments.add(new ImsPsb.Senseg(name,
                                Optional.ofNullable(statement.value("PARENT"))
                                        .filter(parent -> !parent.equals("0")),
                                Optional.ofNullable(statement.value("PROCOPT")),
                                at(source, statement.line())));
                    }
                }
                case "PSBGEN" -> {
                    if (type != null) {
                        pcbs.add(new ImsPsb.Pcb(type, dbdName, procopt, segments,
                                at(source, pcbLine)));
                    }
                    String name = statement.value("PSBNAME");
                    psbs.add(new ImsPsb(name == null ? memberNameOf(source.path()) : name,
                            Optional.ofNullable(statement.value("LANG")).orElse(""), pcbs,
                            source.path(), at(source, firstLine == 0 ? statement.line()
                                    : firstLine)));
                    pcbs = new ArrayList<>();
                    type = null;
                    segments = new ArrayList<>();
                    firstLine = 0;
                }
                default -> {
                }
            }
        }
        if (type != null) {
            pcbs.add(new ImsPsb.Pcb(type, dbdName, procopt, segments, at(source, pcbLine)));
            psbs.add(new ImsPsb(memberNameOf(source.path()), "", pcbs, source.path(),
                    at(source, firstLine == 0 ? 1 : firstLine)));
        }
        return psbs;
    }

    private static SourcePosition at(DecodedSource source, int line) {
        return new SourcePosition(source.path(), Math.max(1, line), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    private static String memberNameOf(String path) {
        String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        int dot = file.lastIndexOf('.');
        return (dot <= 0 ? file : file.substring(0, dot)).toUpperCase(Locale.ROOT);
    }
}
