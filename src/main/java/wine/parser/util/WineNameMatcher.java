package wine.parser.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WineNameMatcher {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}");

    private static final Map<String, String> WINE_TERMS_UA_TO_EN = new LinkedHashMap<>();
    static {
        WINE_TERMS_UA_TO_EN.put("напівсухе", "demi sec");
        WINE_TERMS_UA_TO_EN.put("напівсолодке", "semisweet");
        WINE_TERMS_UA_TO_EN.put("сухе", "dry");
        WINE_TERMS_UA_TO_EN.put("солодке", "sweet");
        WINE_TERMS_UA_TO_EN.put("червоне", "red");
        WINE_TERMS_UA_TO_EN.put("біле", "white");
        WINE_TERMS_UA_TO_EN.put("рожеве", "rose");
        WINE_TERMS_UA_TO_EN.put("ігристе", "sparkling");
        WINE_TERMS_UA_TO_EN.put("шампанське", "champagne");
        WINE_TERMS_UA_TO_EN.put("брют", "brut");
        WINE_TERMS_UA_TO_EN.put("rouge", "red");
        WINE_TERMS_UA_TO_EN.put("blanc", "white");
    }

    private static final Pattern PACKAGING_NOISE_PATTERN =
            Pattern.compile("коробці|коробка|коробку|пакованні|пакування|футлярі|тубусі|кейсі");

    private WineNameMatcher() {}

    public static double calculateSimilarity(String name1, String name2) {
        if (hasConflictingYears(name1, name2)) {
            return 0.0;
        }

        String clean1 = removeGarbageWords(name1);
        String clean2 = removeGarbageWords(name2);

        Set<String> words1 = new HashSet<>(Arrays.asList(clean1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(clean2.split("\\s+")));

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        if (words1.contains("rose") != words2.contains("rose")) {
            return 0.0;
        }

        boolean red1 = words1.contains("red"), white1 = words1.contains("white");
        boolean red2 = words2.contains("red"), white2 = words2.contains("white");
        if ((red1 && white2) || (white1 && red2)) {
            return 0.0;
        }

        int intersection = 0;
        for (String w : words1) {
            if (words2.contains(w)) intersection++;
        }

        int union = words1.size() + words2.size() - intersection;
        return (double) intersection / union;
    }

    public static String removeGarbageWords(String name) {
        if (name == null) return "";
        String result = stripDiacritics(name.toLowerCase()).replaceAll("вино", "");
        result = PACKAGING_NOISE_PATTERN.matcher(result).replaceAll("");

        for (Map.Entry<String, String> term : WINE_TERMS_UA_TO_EN.entrySet()) {
            result = result.replaceAll(term.getKey(), term.getValue());
        }

        return result
                .replaceAll("\\d+[.,]?\\d*\\s*(ml|мл|l|л|%|°)", "")
                .replaceAll("[^a-zа-яіїєґ0-9]", " ")
                .trim();
    }

    private static boolean hasConflictingYears(String name1, String name2) {
        Set<String> years1 = extractYears(name1);
        Set<String> years2 = extractYears(name2);
        if (years1.isEmpty() || years2.isEmpty()) return false;
        return Collections.disjoint(years1, years2);
    }

    private static Set<String> extractYears(String text) {
        Set<String> years = new HashSet<>();
        if (text == null) return years;
        Matcher m = YEAR_PATTERN.matcher(text);
        while (m.find()) years.add(m.group());
        return years;
    }

    private static String stripDiacritics(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        return DIACRITICS_PATTERN.matcher(normalized).replaceAll("");
    }
}
