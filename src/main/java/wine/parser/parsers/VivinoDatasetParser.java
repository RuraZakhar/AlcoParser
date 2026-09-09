package wine.parser.parsers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import wine.parser.model.VivinoEntry;
import wine.parser.model.WineProduct;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class VivinoDatasetParser implements WineParser {

    private static final String RESOURCE_PATH = "/vivino_dataset_java.json";

    @Override
    public List<WineProduct> parse(List<WineProduct> existingCache) {
        List<WineProduct> wines = new ArrayList<>();

        try (InputStream is = VivinoDatasetParser.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                System.err.println("   [VivinoDataset] ❌ Resource not found: " + RESOURCE_PATH);
                return wines;
            }
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<VivinoEntry>>(){}.getType();
                List<VivinoEntry> entries = new Gson().fromJson(reader, listType);
                if (entries == null) return wines;

                for (VivinoEntry entry : entries) {
                    if (entry.getName() == null || entry.getWineryName() == null) continue;

                    WineProduct wine = new WineProduct();
                    wine.setName(entry.getName());
                    wine.setCleanName(entry.getName().toLowerCase());
                    wine.setWinery(entry.getWineryName());
                    wine.setVivinoRating(entry.getRating());
                    wine.setReviewsCount(entry.getRatingsCount());
                    wine.setCountry(entry.getCountryName());
                    wine.addSourceUrl("Vivino", entry.getUrl());
                    wines.add(wine);
                }
            }
        } catch (IOException e) {
            System.err.println("   [VivinoDataset] ❌ Error reading dataset: " + e.getMessage());
        }

        System.out.println("   [VivinoDataset] Loaded from dataset: " + wines.size() + " entries.");
        return wines;
    }
}
