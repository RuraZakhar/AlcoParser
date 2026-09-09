package wine.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wine.parser.model.WineProduct;
import wine.parser.util.VolumeExtractor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OkwineParser implements WineParser {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String LISTING_API = "https://product.okwine.ua/api/v1/filter/full";
    private static final String PRODUCT_URL_BASE = "https://okwine.ua/ua/product/";
    private static final String CITY_ID = "61e159f3ab2700007200435f";

    private static final String[] CATEGORY_IDS = {
            "61c460bf1fda1bf332a33be8",
            "61c460bf1fda1bf332a33c0c"
    };

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int MAX_RETRIES = 3;

    @Override
    public List<WineProduct> parse(List<WineProduct> existingCache) {
        List<WineProduct> wines = new ArrayList<>();

        for (String categoryId : CATEGORY_IDS) {
            System.out.println("   [OkWine] Starting to collect category " + categoryId + "...");
            wines.addAll(parseCategory(categoryId));
        }

        System.out.println("   [OkWine] Done. Collected entries: " + wines.size());
        return wines;
    }

    private List<WineProduct> parseCategory(String categoryId) {
        List<WineProduct> wines = new ArrayList<>();

        int page = 1;
        int maxPage = 1;

        do {
            String url = LISTING_API + "?category=" + categoryId + "&city=" + CITY_ID + "&lang=ua&page=" + page;

            String responseBody = fetchWithRetry(url);
            if (responseBody == null) {
                System.out.println("   [OkWine] ❌ Failed to fetch page " + page + " of category " + categoryId);
                break;
            }

            try {
                JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
                JsonObject productsData = root.getAsJsonObject("data").getAsJsonObject("productsData");
                maxPage = productsData.get("maxPage").getAsInt();

                JsonArray items = productsData.getAsJsonArray("data");
                if (items != null) {
                    for (JsonElement el : items) {
                        WineProduct wine = toWineProduct(el.getAsJsonObject());
                        if (wine != null) {
                            wines.add(wine);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("   [OkWine] ❌ JSON parsing error (page " + page + "): " + e.getMessage());
                break;
            }

            page++;
        } while (page <= maxPage);

        return wines;
    }

    private WineProduct toWineProduct(JsonObject item) {
        String name = getStringOrNull(item, "name");
        String slug = getStringOrNull(item, "url");
        if (name == null || slug == null) return null;

        WineProduct wine = new WineProduct();
        wine.setName(name);
        wine.setCleanName(extractMatchableName(name));

        String productUrl = PRODUCT_URL_BASE + slug;
        wine.setOkwineUrl(productUrl);
        wine.addSourceUrl("OkWine", productUrl);

        wine.setSku(getStringOrNull(item, "utp"));

        if (item.has("inStock") && !item.get("inStock").isJsonNull()) {
            wine.setInStock(item.get("inStock").getAsBoolean());
        }

        JsonObject prices = item.has("prices") && item.get("prices").isJsonObject() ? item.getAsJsonObject("prices") : null;
        if (prices != null) {
            wine.setOkwinePrice(getDoubleOrNull(prices, "price"));
        }

        VolumeExtractor.extractVolumeFromString(wine, name);

        return wine;
    }

    private String extractMatchableName(String name) {
        int slashIdx = name.indexOf('/');
        String matchable = slashIdx >= 0 ? name.substring(slashIdx + 1) : name;
        return matchable.toLowerCase().trim();
    }

    private String fetchWithRetry(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.body();
                } else if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    Thread.sleep(1500L * attempt);
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
