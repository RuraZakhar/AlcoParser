package rum.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import rum.parser.model.RumProduct;
import rum.parser.util.RumNameMatcher;
import common.parser.http.HttpRetry;
import common.parser.util.JsonUtils;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SilpoParser implements RumParser {

    private static final String BASE_IMAGE_URL = "https://images.silpo.ua/products/1600x1600/webp/";
    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String BASE_API_DETAILS_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products/";
    private static final Pattern AGE_DIGITS_PATTERN = Pattern.compile("\\d+");

    // Note: distinct from FUZZY_THRESHOLD (0.90) in RumHowlerParser/RumRatingsParser --
    // pre-existing, previously hardcoded inline here. Left at its original value per
    // this refactor's constraints (thresholds are out of scope); only named for clarity.
    private static final double FUZZY_THRESHOLD = 0.82;

    private final HttpRetry httpRetry = new HttpRetry(HttpClient.newHttpClient(), 3);

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[3/3] Starting Silpo Parser...");

        List<RumProduct> silpoRums = fetchAllSilpoRums();
        System.out.println("Collected " + silpoRums.size() + " rums from Silpo. Starting matching...");

        int matchedCount = 0;
        int newAddedCount = 0;

        for (RumProduct silpoRum : silpoRums) {
            if (mergeIntoCollection(silpoRum, rumSet)) {
                newAddedCount++;
            } else {
                matchedCount++;
            }
        }

        System.out.println("Silpo Parser finished!");
        System.out.println("   Matches found: " + matchedCount);
        System.out.println("   New items added (Silpo only): " + newAddedCount);
    }

    // Exact match first, mirroring beer.parser.Main / RumHowlerParser / RumRatingsParser's own
    // exact-match step. Without this, a Silpo item that already fuzzy-matched into another entry
    // on a PREVIOUS run gets silently re-added as a fresh duplicate on every SUBSEQUENT run: the
    // fuzzy loop below skips any candidate that already has a Silpo sourceUrl at all (meant to
    // stop a second, DIFFERENT Silpo item from overwriting an existing match), which also blocks
    // re-matching THIS SAME item against its own already-merged target once cached. Confirmed
    // real damage: Chairman's Reserve, Appleton Estate Signature Blend, Blackwell Fine Jamaican,
    // and Santiago de Cuba Carta Blanca each ended up duplicated this way (official English name
    // from Howler/RumRatings vs Silpo's own Ukrainian listing name never re-converge).
    private boolean mergeIntoCollection(RumProduct silpoRum, Set<RumProduct> rumSet) {
        for (RumProduct existingRum : rumSet) {
            if (silpoRum.getProductUrl() != null
                    && silpoRum.getProductUrl().equals(existingRum.getSourceUrls().get("Silpo"))) {
                applySilpoMatch(existingRum, silpoRum, 1.0);
                return false;
            }
        }

        RumProduct bestMatch = null;
        double bestScore = 0.0;

        for (RumProduct existingRum : rumSet) {
            if (existingRum.getSourceUrls().containsKey("Silpo")) continue;
            if (!RumNameMatcher.sameBrand(existingRum.getBrand(), silpoRum.getBrand())) continue;
            double score = RumNameMatcher.similarity(existingRum.getName(), silpoRum.getName());
            if (score > bestScore) {
                bestScore = score;
                bestMatch = existingRum;
            }
        }

        if (bestMatch != null && bestScore > FUZZY_THRESHOLD) {
            applySilpoMatch(bestMatch, silpoRum, bestScore);
            return false;
        }

        applySilpoMatch(silpoRum, silpoRum, 1.0);
        rumSet.add(silpoRum);
        return true;
    }

    private void applySilpoMatch(RumProduct target, RumProduct silpoRum, double score) {
        target.setSilpoMatch(new RumProduct.SilpoMatch(
                silpoRum.getName(),
                score,
                silpoRum.getPrice(),
                silpoRum.getPrice() != null && silpoRum.getPrice() > 0,
                silpoRum.getProductUrl()
        ));
        target.addSourceUrl("Silpo", silpoRum.getProductUrl());
        if (!silpoRum.getRatings().isEmpty()) {
            target.getRatings().addAll(silpoRum.getRatings());
        }

        // Confirmed real bug (external audit): top-level price never followed silpoMatch.price
        // -- 17 rums ended up with a stale/wrong price and 50 more had no price at all, even
        // though a Silpo match with a real price existed right next to it. Silpo is the only
        // source of ground-truth pricing here, so its price always wins on refresh.
        if (silpoRum.getPrice() != null) {
            target.setPrice(silpoRum.getPrice());
        }

        if (target.getRegion() == null) target.setRegion(silpoRum.getRegion());
        if (target.getAbv() == null) target.setAbv(silpoRum.getAbv());
        if (target.getAge() == null) target.setAge(silpoRum.getAge());
    }

    private List<RumProduct> fetchAllSilpoRums() {
        List<RumProduct> silpoList = new ArrayList<>();
        int limit = 100;
        int offset = 0;
        boolean hasMore = true;

        try {
            while (hasMore) {
                System.out.println("   [Silpo] Reading page (offset=" + offset + ", limit=" + limit + ")...");
                String catalogUrl = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=rom-4468&includeChildCategories=true&sortBy=popularity&sortDirection=desc&inStock=false";
                String responseBody = sendGetRequest(catalogUrl);

                if (responseBody != null) {
                    JsonObject rootObj = JsonParser.parseString(responseBody).getAsJsonObject();
                    JsonArray items = rootObj.getAsJsonArray("items");
                    int fetchedSize = items.size();

                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        RumProduct rum = new RumProduct();

                        rum.setName(JsonUtils.getStringOrNull(item, "title"));
                        rum.setBrand(JsonUtils.getStringOrNull(item, "brandTitle"));
                        rum.setVolumeWeight(JsonUtils.getStringOrNull(item, "displayRatio"));
                        rum.setPrice(JsonUtils.getDoubleOrNull(item, "price"));
                        rum.setCategory("rum");

                        Double rawRating = JsonUtils.getDoubleOrNull(item, "guestProductRating");
                        if (rawRating != null) {
                            rum.getRatings().add(new RumProduct.Rating("Silpo", rawRating * 2.0));
                        }

                        String icon = JsonUtils.getStringOrNull(item, "icon");
                        if (icon != null) rum.setImgUrl(BASE_IMAGE_URL + icon);

                        String slug = JsonUtils.getStringOrNull(item, "slug");
                        if (slug != null && !slug.isEmpty()) {
                            rum.setProductUrl(BASE_PRODUCT_URL + slug);
                            fetchAndAddDetails(slug, rum);
                            Thread.sleep(50);
                        }

                        silpoList.add(rum);
                    }

                    System.out.println("   [Silpo] Page (offset=" + offset + "): received " + fetchedSize
                            + " items (total collected: " + silpoList.size() + ")");

                    if (fetchedSize < limit) hasMore = false;
                    else offset += limit;
                } else {
                    System.out.println("   [Silpo] No response from server at offset=" + offset + ", stopping pagination.");
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return silpoList;
    }

    private void fetchAndAddDetails(String slug, RumProduct rum) {
        String detailsUrl = BASE_API_DETAILS_URL + slug;
        String responseBody = sendGetRequest(detailsUrl);
        if (responseBody == null) return;

        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray groups = root.getAsJsonArray("attributeGroups");

            if (groups != null) {
                for (JsonElement groupEl : groups) {
                    JsonObject group = groupEl.getAsJsonObject();
                    if ("generalInfo".equals(JsonUtils.getStringOrNull(group, "key"))) {
                        JsonArray attributes = group.getAsJsonArray("attributes");
                        for (JsonElement attrEl : attributes) {
                            JsonObject attr = attrEl.getAsJsonObject();
                            JsonObject attrKeyObj = attr.getAsJsonObject("attribute");
                            JsonObject valueObj = attr.getAsJsonObject("value");

                            if (attrKeyObj != null && valueObj != null) {
                                String key = JsonUtils.getStringOrNull(attrKeyObj, "key");
                                String valueTitle = JsonUtils.getStringOrNull(valueObj, "title");

                                if (valueTitle != null) {
                                    if ("country".equals(key)) {
                                        rum.setRegion(valueTitle);
                                    } else if ("alcoholcontent".equals(key)) {
                                        try {
                                            rum.setAbv(Double.parseDouble(valueTitle.replace("%", "").trim()));
                                        } catch (Exception ignored) {
                                        }
                                    } else if ("strokvytrymky".equals(key)) {
                                        Matcher m = AGE_DIGITS_PATTERN.matcher(valueTitle);
                                        if (m.find()) {
                                            rum.setAge(Double.parseDouble(m.group()));
                                        }
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String sendGetRequest(String url) {
        try {
            return httpRetry.fetch(url, builder -> builder
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0")
                    .header("Referer", "https://silpo.ua/"));
        } catch (Exception e) {
            return null;
        }
    }
}
