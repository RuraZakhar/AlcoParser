package wine.parser;

import wine.parser.model.WineProduct;
import wine.parser.parsers.OkwineParser;
import wine.parser.parsers.SilpoParser;
import wine.parser.parsers.VivinoDatasetParser;
import wine.parser.parsers.WineParser;
import wine.parser.util.WineNameMatcher;
import wine.parser.util.WineryWhitelist;
import common.parser.util.JsonExporter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {

    private static final double SIMILARITY_THRESHOLD = 0.60;

    public static void main(String[] args) {
        System.out.println("=== Starting wine parser ===");

        List<WineProduct> existingCache = loadExistingWines("top_wines.json");
        System.out.println(">>> Loaded from cache (top_wines.json): " + existingCache.size() + " entries.");

        List<WineParser> parsers = Arrays.asList(
                new VivinoDatasetParser(),
                new SilpoParser(),
                new OkwineParser()
        );

        List<WineProduct> collectedWines = new ArrayList<>(existingCache);

        for (WineParser parser : parsers) {
            String parserName = parser.getClass().getSimpleName();
            System.out.println("\n>>> Collecting data via: " + parserName + "...");

            List<WineProduct> parsedWines = parser.parse(existingCache);

            for (WineProduct wine : parsedWines) {
                mergeOrAdd(collectedWines, wine);
            }
        }

        System.out.println(">>> Collected unique entries (before filtering): " + collectedWines.size());

        int overridesApplied = applyManualVivinoMatches(collectedWines);
        System.out.println(">>> Applied manual Silpo/OKWine <-> Vivino matches: " + overridesApplied);

        WineryWhitelist wineryWhitelist = new WineryWhitelist();
        System.out.println(">>> Loaded winery whitelist: " + wineryWhitelist.size() + " entries.");

        List<WineProduct> filteredWines = filterByVivinoRatingAndWinery(collectedWines, wineryWhitelist);
        System.out.println(">>> Remaining after filtering (Vivino rating + top-100 winery): " + filteredWines.size());

        System.out.println(">>> Saving to top_wines.json...");
        saveJsonFile(filteredWines, "top_wines.json");
    }

    private static class ManualMatch {
        String silpoUrl;
        String okwineUrl;
        String vivinoUrl;
        Double vivinoRating;
        Integer reviewsCount;
        String country;
        String winery;
    }

    private static int applyManualVivinoMatches(List<WineProduct> wines) {
        List<ManualMatch> matches = loadManualVivinoMatches();
        if (matches.isEmpty()) return 0;

        int applied = 0;
        for (ManualMatch m : matches) {
            WineProduct target = null;
            for (WineProduct wine : wines) {
                boolean urlMatches = (m.silpoUrl != null && m.silpoUrl.equals(wine.getSilpoUrl()))
                        || (m.okwineUrl != null && m.okwineUrl.equals(wine.getOkwineUrl()));
                if (urlMatches) {
                    target = wine;
                    break;
                }
            }
            if (target == null) continue;

            if (!target.getSourceUrls().containsKey("Vivino")) {
                WineProduct synthetic = new WineProduct();
                synthetic.setName(target.getName());
                synthetic.setCleanName(target.getCleanName());
                synthetic.setVivinoRating(m.vivinoRating);
                synthetic.setReviewsCount(m.reviewsCount);
                synthetic.setCountry(m.country);
                synthetic.setWinery(m.winery);
                synthetic.addSourceUrl("Vivino", m.vivinoUrl);

                target.mergeFrom(synthetic);
                applied++;
            }

            final WineProduct finalTarget = target;
            wines.removeIf(w -> w != finalTarget && hasNoStorePrice(w)
                    && m.vivinoUrl.equals(w.getSourceUrls() == null ? null : w.getSourceUrls().get("Vivino")));
        }
        return applied;
    }

    private static List<ManualMatch> loadManualVivinoMatches() {
        List<ManualMatch> result = new ArrayList<>();
        try (InputStream is = Main.class.getResourceAsStream("/manual_vivino_matches.json")) {
            if (is == null) return result;
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<ManualMatch>>(){}.getType();
                List<ManualMatch> read = new Gson().fromJson(reader, listType);
                if (read != null) result.addAll(read);
            }
        } catch (IOException e) {
            System.err.println("Error reading manual_vivino_matches.json: " + e.getMessage());
        }
        return result;
    }

    private static List<WineProduct> filterByVivinoRatingAndWinery(List<WineProduct> wines, WineryWhitelist wineryWhitelist) {
        List<WineProduct> result = new ArrayList<>();
        for (WineProduct wine : wines) {
            if (wine.getVivinoRating() == null) continue;
            if (!wineryWhitelist.matches(wine.getWinery())) continue;
            result.add(wine);
        }
        return result;
    }

    private static List<WineProduct> loadExistingWines(String fileName) {
        Gson gson = new GsonBuilder().create();
        Path path = Path.of(fileName);
        List<WineProduct> list = new ArrayList<>();
        if (Files.exists(path)) {
            try (java.io.Reader reader = Files.newBufferedReader(path)) {
                Type listType = new TypeToken<ArrayList<WineProduct>>(){}.getType();
                List<WineProduct> read = gson.fromJson(reader, listType);
                if (read != null) list.addAll(read);
            } catch (IOException e) {
                System.err.println("Error reading cache: " + e.getMessage());
            }
        }
        return list;
    }

    private static void saveJsonFile(List<WineProduct> wines, String fileName) {
        // Writes to a .tmp file and atomically renames it into place (see
        // common.parser.util.JsonExporter, already used by rum.parser/beer.parser) --
        // a crash or kill mid-write here used to leave top_wines.json half-written, which
        // then fails to parse as the next run's cache, silently losing everything in it.
        boolean success = new JsonExporter().exportToJson(wines, fileName);
        if (success) {
            System.out.println("=== DONE! Saved: " + wines.size() + " entries ===");
        }
    }

    private static void mergeOrAdd(List<WineProduct> list, WineProduct newWine) {
        if (newWine.getCleanName() == null) return;

        WineProduct exactMatch = findExactMatch(list, newWine);
        if (exactMatch != null) {
            exactMatch.mergeFrom(newWine);
            return;
        }

        WineProduct fuzzyMatch = null;
        double highestScore = 0.0;

        for (WineProduct existing : list) {
            if (existing == null || existing.getCleanName() == null) continue;
            if (sameSource(existing, newWine)) continue;

            if (existing.getVolume() != null && newWine.getVolume() != null
                    && !existing.getVolume().equals(newWine.getVolume())) {
                continue;
            }

            if (!WineryWhitelist.sameWinery(existing.getWinery(), newWine.getWinery())) {
                continue;
            }

            double score = WineNameMatcher.calculateSimilarity(existing.getCleanName(), newWine.getCleanName());
            if (score > highestScore) {
                highestScore = score;
                fuzzyMatch = existing;
            }
        }

        if (fuzzyMatch != null && highestScore >= SIMILARITY_THRESHOLD) {
            fuzzyMatch.mergeFrom(newWine);
        } else {
            list.add(newWine);
        }
    }

    private static WineProduct findExactMatch(List<WineProduct> list, WineProduct newWine) {
        for (WineProduct existing : list) {
            if (existing == null) continue;

            if (newWine.getEan() != null && newWine.getEan().equals(existing.getEan())) {
                return existing;
            }
            if (newWine.getMaudauUrl() != null && newWine.getMaudauUrl().equals(existing.getMaudauUrl())) {
                return existing;
            }
            if (newWine.getSilpoUrl() != null && newWine.getSilpoUrl().equals(existing.getSilpoUrl())) {
                return existing;
            }
            if (newWine.getOkwineUrl() != null && newWine.getOkwineUrl().equals(existing.getOkwineUrl())) {
                return existing;
            }
            if (newWine.getProductUrl() != null && newWine.getProductUrl().equals(existing.getProductUrl())) {
                return existing;
            }
        }
        return null;
    }

    private static boolean sameSource(WineProduct a, WineProduct b) {
        boolean bothMaudau = a.getMaudauPrice() != null && b.getMaudauPrice() != null;
        boolean bothZakaz = a.getPrice() != null && b.getPrice() != null;
        boolean bothSilpo = a.getSilpoPrice() != null && b.getSilpoPrice() != null;
        boolean bothOkwine = a.getOkwinePrice() != null && b.getOkwinePrice() != null;
        return bothMaudau || bothZakaz || bothSilpo || bothOkwine || bothPureVivino(a, b);
    }

    private static boolean bothPureVivino(WineProduct a, WineProduct b) {
        return hasNoStorePrice(a) && hasNoStorePrice(b);
    }

    private static boolean hasNoStorePrice(WineProduct w) {
        return w.getMaudauPrice() == null && w.getPrice() == null
                && w.getSilpoPrice() == null && w.getOkwinePrice() == null;
    }

}
