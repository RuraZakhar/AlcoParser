package wine.parser.parsers;

import wine.parser.model.WineProduct;
import wine.parser.util.VolumeExtractor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class SilpoParser implements WineParser {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String API_CATALOG_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products";
    private static final String API_DETAILS_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0";

    private static final int MAX_RETRIES = 3;

    @Override
    public List<WineProduct> parse(List<WineProduct> existingCache) {
        List<WineProduct> wines = new ArrayList<>();
        int limit = 100;
        int offset = 0;
        boolean hasMore = true;

        System.out.println("   [SilpoWine] Starting to collect wines from the Vivino selection...");

        while (hasMore) {
            String url = API_CATALOG_URL + "?limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&sortBy=productsList&sortDirection=desc&set=vyno-vivino";

            String responseBody = fetchWithRetry(url);
            if (responseBody == null) {
                System.out.println("   [SilpoWine] ❌ Failed to fetch data for offset=" + offset);
                break;
            }

            try {
                JsonObject rootObj = JsonParser.parseString(responseBody).getAsJsonObject();
                JsonArray items = rootObj.getAsJsonArray("items");

                if (items == null || items.isEmpty() || !items.isJsonArray()) {
                    hasMore = false;
                    continue;
                }

                for (JsonElement element : items) {
                    JsonObject item = element.getAsJsonObject();

                    Double vivinoRating = getDoubleOrNull(item, "vivinoRating");
                    if (vivinoRating == null || vivinoRating < 3.8) {
                        continue;
                    }

                    WineProduct wine = new WineProduct();
                    wine.setVivinoRating(vivinoRating);

                    String title = getStringOrNull(item, "title");
                    wine.setName(title);
                    wine.setWinery(getStringOrNull(item, "brandTitle"));

                    if (title != null) {
                        wine.setCleanName(title.toLowerCase());
                    }

                    String displayRatio = getStringOrNull(item, "displayRatio");
                    if (displayRatio != null) {
                        VolumeExtractor.extractVolumeFromString(wine, displayRatio);
                    } else if (title != null) {
                        VolumeExtractor.extractVolumeFromString(wine, title);
                    }

                    wine.setSilpoPrice(getDoubleOrNull(item, "price"));

                    String slug = getStringOrNull(item, "slug");
                    if (slug != null) {
                        wine.setSilpoUrl(BASE_PRODUCT_URL + slug);
                        wine.addSourceUrl("Silpo", wine.getSilpoUrl());
                        fetchAndAddDetails(slug, wine);
                    }

                    wines.add(wine);
                    System.out.println("      ✅ Added: " + wine.getName() + " | Price: " + wine.getSilpoPrice() + " | Vivino: " + wine.getVivinoRating());
                }

                if (items.size() < limit) {
                    hasMore = false;
                } else {
                    offset += limit;
                }

            } catch (Exception e) {
                System.err.println("   [SilpoWine] ❌ JSON parsing error: " + e.getMessage());
                hasMore = false;
            }
        }

        System.out.println("   [SilpoWine] Done. Collected wines (>= 3.8): " + wines.size());
        return wines;
    }

    private void fetchAndAddDetails(String slug, WineProduct wine) {
        String detailsUrl = API_DETAILS_URL + slug;
        String responseBody = fetchWithRetry(detailsUrl);
        if (responseBody == null) return;

        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

            String ean = getStringOrNull(root, "barcode");
            if (ean == null) ean = getStringOrNull(root, "ean");
            if (ean != null && !ean.isBlank()) wine.setEan(ean);

            JsonArray groups = root.getAsJsonArray("attributeGroups");
            if (groups != null) {
                for (JsonElement groupEl : groups) {
                    JsonObject group = groupEl.getAsJsonObject();
                    if ("generalInfo".equals(getStringOrNull(group, "key"))) {
                        JsonArray attributes = group.getAsJsonArray("attributes");
                        for (JsonElement attrEl : attributes) {
                            JsonObject attr = attrEl.getAsJsonObject();
                            JsonObject attrKeyObj = attr.getAsJsonObject("attribute");
                            JsonObject valueObj = attr.getAsJsonObject("value");

                            if (attrKeyObj != null && valueObj != null) {
                                String key = getStringOrNull(attrKeyObj, "key");
                                String valueTitle = getStringOrNull(valueObj, "title");
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String fetchWithRetry(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Referer", "https://silpo.ua/")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.body();
                } else if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    long sleepMs = 1500L * attempt;
                    Thread.sleep(sleepMs);
                } else {
                    return null;
                }
            } catch (Exception e) {
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsString();
        return null;
    }

    private Double getDoubleOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsDouble();
        return null;
    }
}
