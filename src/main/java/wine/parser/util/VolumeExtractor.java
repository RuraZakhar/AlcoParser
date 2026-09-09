package wine.parser.util;

import wine.parser.model.WineProduct;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VolumeExtractor {

    private static final Pattern VOLUME_PATTERN = Pattern.compile("(?i)([0-9.,]+)\\s*(мл|ml|л|l)");

    private VolumeExtractor() {}

    public static void extractVolumeFromString(WineProduct wine, String text) {
        if (text == null) return;
        Matcher volMatcher = VOLUME_PATTERN.matcher(text);
        if (volMatcher.find()) {
            try {
                double v = Double.parseDouble(volMatcher.group(1).replace(",", "."));
                if (volMatcher.group(2).toLowerCase().contains("м")) {
                    v = v / 1000.0;
                }
                wine.setVolume(v);
            } catch (NumberFormatException ignored) {}
        }
    }
}
