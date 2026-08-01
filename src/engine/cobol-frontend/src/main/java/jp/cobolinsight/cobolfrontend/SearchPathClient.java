package jp.cobolinsight.cobolfrontend;

import org.eclipse.lsp.cobol.lsp.DialectItemDTO;
import org.eclipse.lsp.cobol.lsp.jrpc.CobolLanguageClient;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ライブラリ組込み用の言語クライアント。コピー句を探索パス順の先勝ちで解決する。診断通知・
 * メッセージ表示・設定取得は編集機能のための呼出しであり、解析には要らないため空応答を返す。
 */
final class SearchPathClient implements CobolLanguageClient {

    /** 探索する拡張子。COPY 文はメンバ名だけを書くため、拡張子なしと .cpy をこの順で試す。 */
    private static final List<String> COPYBOOK_EXTENSIONS = List.of("", ".cpy", ".CPY");

    private volatile List<Path> searchPaths = List.of();

    void setSearchPaths(List<Path> searchPaths) {
        this.searchPaths = List.copyOf(searchPaths);
    }

    @Override
    public CompletableFuture<String> resolveCopybookUri(String cobolFileUri, String copybookName,
            String dialectType) {
        for (Path searchPath : searchPaths) {
            for (String extension : COPYBOOK_EXTENSIONS) {
                Path candidate = searchPath.resolve(copybookName + extension);
                if (Files.exists(candidate)) {
                    return CompletableFuture.completedFuture(candidate.toUri().toString());
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<DialectItemDTO>> availableDialects() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
    }

    @Override
    public void showMessage(MessageParams messageParams) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
            ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
    }
}
