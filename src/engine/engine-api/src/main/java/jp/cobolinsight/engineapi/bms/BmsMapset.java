package jp.cobolinsight.engineapi.bms;

import java.util.List;
import java.util.Optional;

/** DFHMSDが定義するマップセット。 */
public record BmsMapset(String name, String sourceFile, List<BmsMap> maps) {

    public BmsMapset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        maps = List.copyOf(maps);
    }

    public Optional<BmsMap> map(String mapName) {
        return maps.stream().filter(m -> m.name().equals(mapName)).findFirst();
    }
}
