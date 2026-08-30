package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.source.AssetKind;

import org.eclipse.lsp.cobol.lsp.DialectItemDTO;
import org.eclipse.lsp.cobol.lsp.jrpc.CobolLanguageClient;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Language client for library embedding. Resolves a copybook on a first-match basis in search-path
 * order. Diagnostic notification, message display, and configuration retrieval are calls meant for
 * editing features and are not needed for analysis, so they return empty responses.
 */
final class SearchPathClient implements CobolLanguageClient {

    /**
     * Extensions to search. Since a COPY statement writes only the member name, no-extension is
     * tried first, followed by the extensions {@link AssetKind#COPYBOOK} declares, in lowercase
     * then uppercase order. Copying the canonical extension list here separately means that if
     * only one of the two is extended, copybook resolution via COPY statements would be left behind.
     */
    private static final List<String> COPYBOOK_EXTENSIONS = copybookExtensions();

    private static List<String> copybookExtensions() {
        List<String> extensions = new ArrayList<>();
        extensions.add("");
        for (String extension : AssetKind.COPYBOOK.extensions()) {
            extensions.add(extension);
            extensions.add(extension.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(extensions);
    }

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
