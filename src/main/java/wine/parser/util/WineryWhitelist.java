package wine.parser.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class WineryWhitelist {

    private static final String RESOURCE_PATH = "/top100_wineries_world.txt";
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
    private static final Pattern PARENTHETICAL_SUFFIX = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    private static final Pattern NON_ALNUM_SPACE = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Прізвища на кшталт "Müller" настільки поширені, що будь-яка виноробня, названа лише
    // ним (напр. "Muller" -- реальний, зовсім не пов'язаний австрійський товар Grüner
    // Veltliner з Silpo), хибно проходить substring-збіг проти "Weingut Egon Müller" з
    // whitelist -- реальний баг, знайдений після додавання цієї виноробні. Для таких слів
    // дозволяємо лише точний повний збіг, без substring-скорочення.
    private static final Set<String> GENERIC_SURNAMES = Set.of("muller");

    private final Set<String> normalizedWineries = new HashSet<>();

    public WineryWhitelist() {
        try (InputStream is = WineryWhitelist.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found: " + RESOURCE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (lineNum <= 2) continue;
                    String[] cells = line.split("\\|");
                    if (cells.length < 3) continue;
                    String winery = cells[2].trim();
                    if (winery.isEmpty()) continue;
                    normalizedWineries.add(normalize(winery));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading winery list: " + e.getMessage(), e);
        }
    }

    public int size() {
        return normalizedWineries.size();
    }

    public boolean matches(String winery) {
        if (winery == null || winery.isBlank()) return false;
        String candidate = normalize(winery);
        if (candidate.isEmpty()) return false;
        if (GENERIC_SURNAMES.contains(candidate)) return false;

        String paddedCandidate = " " + candidate + " ";
        for (String known : normalizedWineries) {
            String paddedKnown = " " + known + " ";
            if (candidate.equals(known) || paddedCandidate.contains(paddedKnown) || paddedKnown.contains(paddedCandidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean sameWinery(String a, String b) {
        if (a == null || b == null) return true;
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return true;

        String paddedA = " " + na + " ";
        String paddedB = " " + nb + " ";
        return na.equals(nb) || paddedA.contains(paddedB) || paddedB.contains(paddedA);
    }

    private static String normalize(String value) {
        String stripped = PARENTHETICAL_SUFFIX.matcher(value.trim()).replaceAll("");
        String normalized = Normalizer.normalize(stripped, Normalizer.Form.NFD);
        normalized = DIACRITICS.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase();
        normalized = NON_ALNUM_SPACE.matcher(normalized).replaceAll(" ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }
}
