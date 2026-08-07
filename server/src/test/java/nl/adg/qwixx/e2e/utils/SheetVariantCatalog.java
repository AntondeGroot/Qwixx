package nl.adg.qwixx.e2e.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import nl.adg.qwixx.game.options.GameOption;
import nl.adg.qwixx.game.options.GameOptionCatalog;
import nl.adg.qwixx.game.options.OptionCategory;

/**
 * The sheet variants the docs generators document, in the order the catalog pages render them:
 * every MODE game option (those are the ones that change the sheet), with the Variant option
 * expanded into its two choices. Each variant carries the English label and description the
 * generated markdown headings use.
 */
public final class SheetVariantCatalog {

    private static final Path EN_JSON =
            Path.of(System.getProperty("user.dir")).resolve("../client/public/i18n/en.json").normalize();

    /** One catalog row: the key that tags it in the DOM and names its image, plus its English text. */
    public record Variant(String key, String label, String description) {}

    private SheetVariantCatalog() {}

    public static List<Variant> variants() throws java.io.IOException {
        JsonNode i18n = new ObjectMapper().readTree(EN_JSON.toFile());
        List<Variant> variants = new ArrayList<>();
        for (GameOption option : GameOptionCatalog.all()) {
            if (option.category() != OptionCategory.MODE) continue;
            if ("base".equals(option.key())) {
                variants.add(baseChoice(i18n, "standard", "STANDARD", "gameOption.standardDescription"));
                variants.add(baseChoice(i18n, "longo", "LONGO", "gameOption.longoDescription"));
            } else {
                variants.add(new Variant(
                        option.key(), resolve(i18n, option.labelKey()), resolve(i18n, option.descriptionKey())));
            }
        }
        return variants;
    }

    private static Variant baseChoice(JsonNode i18n, String key, String choice, String descriptionKey) {
        return new Variant(key, resolve(i18n, "gameOption.choice." + choice), resolve(i18n, descriptionKey));
    }

    /** Resolves a dotted i18n key (e.g. "gameOption.baseDescription") against the en.json tree. */
    private static String resolve(JsonNode root, String dottedKey) {
        JsonNode node = root;
        for (String segment : dottedKey.split("\\.")) {
            if (node == null) return "";
            node = node.get(segment);
        }
        return node != null && node.isTextual() ? node.asText() : "";
    }
}
