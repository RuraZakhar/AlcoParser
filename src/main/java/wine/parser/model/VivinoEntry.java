package wine.parser.model;

public class VivinoEntry {
    private String name;
    private Integer year;
    private String wineType;
    private Double rating;
    private Integer ratingsCount;
    private String regionName;
    private String countryName;
    private String wineryName;
    private String url;

    public String getName() { return name; }
    public Integer getYear() { return year; }
    public String getWineType() { return wineType; }
    public Double getRating() { return rating; }
    public Integer getRatingsCount() { return ratingsCount; }
    public String getRegionName() { return regionName; }
    public String getCountryName() { return countryName; }
    public String getWineryName() { return wineryName; }
    public String getUrl() { return url; }
}
