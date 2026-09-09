package beer.parser.util;

import beer.parser.model.BeerProduct;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Domain-specific name matcher, shaped like rum.parser.util.RumNameMatcher
// (parser-conventions.md §7: similarity/matching -> a RumNameMatcher-like class).
// Kept as its own class rather than merged into one shared matcher: the noise-word
// list and the matching algorithm itself (plain Jaccard word-overlap here, vs.
// Jaccard+Levenshtein+numeric-conflict-guard in RumNameMatcher) are genuinely
// different between the two domains -- unifying them would change beer.parser's
// similarity scores, which is out of scope for this refactor (see report).
public class BeerNameMatcher {

    // Applied sequentially, not combined into one alternation pattern: combining them
    // would change matching behavior, since "пастеризоване" is a substring of
    // "непастеризоване" and removing it first (as this order does) leaves a stray
    // "не" fragment rather than the "непастеризоване" pattern ever getting a match.
    // Preserved as-is from the original code rather than "fixed", since correcting it
    // is a behavior change outside this refactor's scope.
    private static final Pattern P_PYVO = Pattern.compile("пиво");
    private static final Pattern P_SVITLE = Pattern.compile("світле");
    private static final Pattern P_TEMNE = Pattern.compile("темне");
    private static final Pattern P_NAPIVTEMNE = Pattern.compile("напівтемне");
    private static final Pattern P_NEFILTROVANE = Pattern.compile("нефільтроване");
    private static final Pattern P_FILTROVANE = Pattern.compile("фільтроване");
    private static final Pattern P_PASTERYZOVANE = Pattern.compile("пастеризоване");
    private static final Pattern P_NEPASTERYZOVANE = Pattern.compile("непастеризоване");
    private static final Pattern P_ZB = Pattern.compile("з/б");
    private static final Pattern P_ROZLYVNE = Pattern.compile("розливне");
    private static final Pattern P_PLYASHKA = Pattern.compile("пляшка");
    private static final Pattern P_BANKA = Pattern.compile("банка");
    private static final Pattern P_UNIT_SUFFIX = Pattern.compile("\\d+[.,]?\\d*\\s*(ml|мл|l|л|%|°)");
    private static final Pattern P_NON_ALNUM = Pattern.compile("[^a-zа-яіїєґ0-9]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}");
    // Кирилиця мусить лишитись (як і в P_NON_ALNUM вище) -- бренди на кшталт "Ципа"/"Правда"
    // не мають дощенту зникати при нормалізації.
    private static final Pattern NON_ALNUM_SPACE = Pattern.compile("[^a-zа-яіїєґ0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Той самий клас проблеми, що з прізвищем "Müller" у wine.parser: короткий загальний
    // брендовий лейбл ("Імпортне пиво" -- фактично Silpo/Flasker так позначили кілька різних
    // імпортних пивоварень замість реального бренду, підтверджено при аудиті) не повинен
    // вважатись "відомим" брендом для конфлікт-перевірки -- інакше два геть різні імпортні
    // пива хибно вважались би однією броварнею.
    private static final Set<String> GENERIC_BRANDS = Set.of("імпортне пиво");

    public static double similarity(String name1, String name2) {
        String cleaned1 = clean(name1);
        String cleaned2 = clean(name2);

        // Перевіряємо конфлікт чисел ПІСЛЯ clean() -- об'єм/ABV% (digit+unit) вже вирізано
        // патерном вище, тож лишаються лише "голі" числа на кшталт року видання/партії
        // ("Formula of the Autumn {2024}" проти "{2025}", "Malle Quadrupel [3/2026]") --
        // саме вони й означають РІЗНЕ пиво, а не round-off різницю в ABV.
        if (hasConflictingNumbers(cleaned1, cleaned2)) {
            return 0.0;
        }

        Set<String> words1 = tokenize(cleaned1);
        Set<String> words2 = tokenize(cleaned2);

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        int intersection = 0;
        for (String w : words1) {
            if (words2.contains(w)) {
                intersection++;
            }
        }

        int union = words1.size() + words2.size() - intersection;
        return (double) intersection / union;
    }

    public static BeerProduct findBestFuzzyMatch(BeerProduct incoming, Collection<BeerProduct> candidates, double threshold) {
        BeerProduct best = null;
        double bestScore = 0.0;

        for (BeerProduct candidate : candidates) {
            double score = similarity(candidate.getCleanName(), incoming.getCleanName());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return (best != null && bestScore >= threshold) ? best : null;
    }

    private static Set<String> tokenize(String cleaned) {
        Set<String> words = new HashSet<>();
        for (String w : cleaned.split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return words;
    }

    private static String clean(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toLowerCase();
        s = P_PYVO.matcher(s).replaceAll("");
        s = P_SVITLE.matcher(s).replaceAll("");
        s = P_TEMNE.matcher(s).replaceAll("");
        s = P_NAPIVTEMNE.matcher(s).replaceAll("");
        s = P_NEFILTROVANE.matcher(s).replaceAll("");
        s = P_FILTROVANE.matcher(s).replaceAll("");
        s = P_PASTERYZOVANE.matcher(s).replaceAll("");
        s = P_NEPASTERYZOVANE.matcher(s).replaceAll("");
        s = P_ZB.matcher(s).replaceAll("");
        s = P_ROZLYVNE.matcher(s).replaceAll("");
        s = P_PLYASHKA.matcher(s).replaceAll("");
        s = P_BANKA.matcher(s).replaceAll("");
        s = P_UNIT_SUFFIX.matcher(s).replaceAll("");
        s = P_NON_ALNUM.matcher(s).replaceAll(" ");
        return s.trim();
    }

    private static boolean hasConflictingNumbers(String cleaned1, String cleaned2) {
        Set<String> nums1 = extractNumbers(cleaned1);
        Set<String> nums2 = extractNumbers(cleaned2);
        if (nums1.isEmpty() || nums2.isEmpty()) return false;
        return Collections.disjoint(nums1, nums2);
    }

    private static Set<String> extractNumbers(String s) {
        Set<String> nums = new HashSet<>();
        Matcher m = DIGIT_PATTERN.matcher(s);
        while (m.find()) nums.add(m.group());
        return nums;
    }

    /**
     * Той самий guard, що й WineryWhitelist.sameWinery у wine.parser: якщо в обох пив вже
     * відомий (не null, не generic) бренд і вони явно різні, злиття блокується одразу, ще до
     * того, як BeerNameMatcher.similarity побачить пару слів. Без цього короткий бренд легко
     * програє довшому спільному стилю/дескриптору (той самий клас бага, що й
     * "Freemark Abbey" -> "Duckhorn" у вині).
     */
    public static boolean sameBrand(String brand1, String brand2) {
        if (brand1 == null || brand2 == null) return true;
        String a = normalizeBrand(brand1);
        String b = normalizeBrand(brand2);
        if (a.isEmpty() || b.isEmpty()) return true;
        if (GENERIC_BRANDS.contains(a) || GENERIC_BRANDS.contains(b)) return true;

        String paddedA = " " + a + " ";
        String paddedB = " " + b + " ";
        return a.equals(b) || paddedA.contains(paddedB) || paddedB.contains(paddedA);
    }

    private static String normalizeBrand(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = DIACRITICS_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase();
        normalized = NON_ALNUM_SPACE.matcher(normalized).replaceAll(" ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }
}
