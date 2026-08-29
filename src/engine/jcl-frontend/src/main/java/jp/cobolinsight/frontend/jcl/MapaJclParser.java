package jp.cobolinsight.frontend.jcl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawDd;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawInclude;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawItem;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawProc;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawStep;
import jp.cobolinsight.frontend.jcl.gen.JCLLexer;
import jp.cobolinsight.frontend.jcl.gen.JCLParser;

/**
 * {@link JclParser} over the MAPA grammars, driving {@link JclModelListener}.
 *
 * <p>The source is parsed as it stands, so every line number is a line of the file the statement
 * was written in. A step of an expanded PROC keeps the line of its own EXEC statement inside the
 * PROC (for a catalogued PROC, in the PROC's file, which the step's position then names); the line
 * of the invoking EXEC is carried by the PROC call step itself, which precedes it in the model.
 *
 * <p>PROC steps are flattened onto the job's step list: the call step (execKind=PROC) first, then
 * the expanded steps named "call step name.PROC step name". Only the first job of a file is
 * returned.
 */
public final class MapaJclParser implements JclParser {

    /** &amp;NAME, with the optional dot that ends the name rather than belonging to the value. */
    private static final Pattern SYMBOL = Pattern.compile("&([A-Z0-9@#$]{1,8})\\.?");

    /** Guards against a PROC that invokes itself, directly or through another. */
    private static final int MAX_PROC_DEPTH = 8;

    /** Extensions tried when looking for a catalogued PROC or an INCLUDE member. */
    private static final List<String> MEMBER_SUFFIXES = List.of("", ".jcl", ".JCL", ".proc", ".PROC");

    @Override
    public ParseOutcome<JclJobModel> parse(DecodedSource source, List<Path> procedureLibraryPaths) {
        try {
            JclModelListener job = walk(source.text(), source.path());
            if (job.jobName() == null) {
                throw new JclParseException(source.path() + " contains no job card");
            }
            Expansion expansion = new Expansion(procedureLibraryPaths);
            List<JclStep> steps = expansion.expandItems(job.items(), "", job.sourceFile(),
                    new LinkedHashMap<>(job.symbols()), job.procs(), 0);
            return ParseOutcome.success(new JclJobModel(job.jobName(), source.path(),
                    Optional.ofNullable(job.jobCondition()), steps));
        } catch (RuntimeException e) {
            return ParseOutcome.failure(Finding.parseFailure(
                    SourcePosition.fileStart(source.path()),
                    source.path() + " の JCL パースに失敗した: " + e.getMessage()));
        }
    }

    /** Lexes, parses and walks one JCL source. Any parser syntax error fails the whole source. */
    private static JclModelListener walk(String text, String path) {
        JCLLexer lexer = new JCLLexer(CharStreams.fromString(text, path));
        // Lexer errors are recovered from by the lexer itself and MAPA's grammars raise them on
        // input the parser then handles; only parser errors mean the source was not understood.
        lexer.removeErrorListeners();
        JCLParser parser = new JCLParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String message, RecognitionException e) {
                throw new JclParseException(path + ":" + line + ":" + charPositionInLine + " "
                        + message);
            }
        });
        JclModelListener listener = new JclModelListener(path);
        ParseTreeWalker.DEFAULT.walk(listener, parser.startRule());
        listener.finish();
        return listener;
    }

    /**
     * Replaces {@code &NAME} and {@code &NAME.} with the value the scope holds. A symbol the scope
     * does not know is left as written, and {@code &&NAME} is a deferred system symbol, not ours.
     */
    static String resolveSymbols(String text, Map<String, String> scope) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = SYMBOL.matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (matcher.find()) {
            if (matcher.start() > 0 && text.charAt(matcher.start() - 1) == '&') {
                continue;
            }
            String value = scope.get(matcher.group(1));
            if (value == null) {
                continue;
            }
            out.append(text, copied, matcher.start()).append(value);
            copied = matcher.end();
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** Expands the job into steps, reading catalogued PROCs and INCLUDE members on the way. */
    private static final class Expansion {

        private final List<Path> libraries;
        private final Map<String, JclModelListener> loaded = new LinkedHashMap<>();

        Expansion(List<Path> libraries) {
            this.libraries = libraries;
        }

        List<JclStep> expandItems(List<RawItem> items, String namePrefix, String file,
                Map<String, String> scope, Map<String, RawProc> procs, int depth) {
            List<JclStep> out = new ArrayList<>();
            for (RawItem item : items) {
                if (item instanceof RawStep step) {
                    expandStep(out, step, namePrefix, file, scope, procs, depth);
                } else if (item instanceof RawInclude include && depth < MAX_PROC_DEPTH) {
                    JclModelListener member = load(resolveSymbols(include.member(), scope));
                    if (member != null) {
                        scope.putAll(member.symbols());
                        Map<String, RawProc> visible = merge(procs, member.procs());
                        out.addAll(expandItems(member.items(), namePrefix, member.sourceFile(),
                                scope, visible, depth + 1));
                    }
                }
            }
            return out;
        }

        private void expandStep(List<JclStep> out, RawStep step, String namePrefix, String file,
                Map<String, String> scope, Map<String, RawProc> procs, int depth) {
            String name = namePrefix.isEmpty() ? step.name() : namePrefix + "." + step.name();
            String target = resolveSymbols(step.target(), scope);
            out.add(new JclStep(name, step.execPgm() ? JclExecKind.PGM : JclExecKind.PROC, target,
                    Optional.ofNullable(step.condition()), ddStatements(step, file, scope),
                    position(file, step.line())));
            if (step.execPgm() || depth >= MAX_PROC_DEPTH) {
                return;
            }
            RawProc proc = procs.get(target);
            Map<String, RawProc> visible = procs;
            if (proc == null) {
                JclModelListener source = load(target);
                if (source == null) {
                    return;
                }
                proc = source.procNamed(target);
                if (proc == null) {
                    return;
                }
                visible = merge(procs, source.procs());
                scope = merge(scope, source.symbols());
            }
            Map<String, String> inner = new LinkedHashMap<>(scope);
            proc.defaults().forEach((key, value) -> inner.put(key, resolveSymbols(value, inner)));
            Map<String, String> caller = scope;
            step.procArguments()
                    .forEach((key, value) -> inner.put(key, resolveSymbols(value, caller)));
            out.addAll(expandItems(proc.items(), name, proc.sourceFile(), inner, visible,
                    depth + 1));
        }

        private static List<JclDdStatement> ddStatements(RawStep step, String file,
                Map<String, String> scope) {
            List<JclDdStatement> out = new ArrayList<>();
            for (RawDd dd : step.ddStatements()) {
                out.add(new JclDdStatement(resolveSymbols(dd.ddName(), scope),
                        Optional.ofNullable(resolveSymbols(dd.datasetName(), scope)),
                        position(file, dd.line())));
            }
            return out;
        }

        /** Reads a catalogued PROC or an INCLUDE member from the procedure libraries. */
        private JclModelListener load(String member) {
            if (loaded.containsKey(member)) {
                return loaded.get(member);
            }
            JclModelListener parsed = null;
            for (Path library : libraries) {
                for (String suffix : MEMBER_SUFFIXES) {
                    Path candidate = library.resolve(member + suffix);
                    if (!Files.isRegularFile(candidate)) {
                        continue;
                    }
                    try {
                        parsed = walk(Files.readString(candidate, StandardCharsets.UTF_8),
                                candidate.toString());
                    } catch (IOException | RuntimeException e) {
                        // An unreadable member leaves the call unexpanded rather than failing the
                        // whole job: the steps written in the job itself are still worth having.
                        parsed = null;
                    }
                    break;
                }
                if (parsed != null) {
                    break;
                }
            }
            loaded.put(member, parsed);
            return parsed;
        }

        private static <V> Map<String, V> merge(Map<String, V> base, Map<String, V> added) {
            Map<String, V> out = new LinkedHashMap<>(base);
            out.putAll(added);
            return out;
        }

        private static SourcePosition position(String file, int line) {
            return new SourcePosition(file, Math.max(1, line), 1,
                    SourcePosition.UNKNOWN_BYTE_OFFSET);
        }
    }
}
