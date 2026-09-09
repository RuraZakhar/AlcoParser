# AlcoParser

AlcoParser is a Java Maven application that scrapes rum, wine, and beer listings from Ukrainian retailers (Silpo, OKWine) and matches them against external rating sources (RumRatings, The Rum Howler Blog, Vivino) to output curated, top-rated product lists.

## Features

- Scrapes rum, wine, and beer listings from Silpo and OKWine.
- Cross-references listings against RumRatings, The Rum Howler Blog, and Vivino ratings.
- Deduplicates and merges matching products across sources.
- Filters output to only well-rated products.
- Outputs structured JSON per category (`top_rum_products.json`, `top_wines.json`, `top_beers.json`).

## Requirements

- Java 17
- Maven

## Key Dependencies

- **Gson:** JSON processing.
- **Jsoup:** HTML scraping.
- **Lombok:** Code generation.

## Configuration

Configuration is managed via environment variables. An example configuration file is provided as `.env.example`.

You can set these variables in your environment before running the application:

- `FIRECRAWL_API_KEY`: API key for Firecrawl.
- `MAX_RUM_RATINGS_PAGES`: Maximum number of RumRatings pages scraped in one run.
- `MAX_HOWLER_PRODUCTS`: Maximum number of Rum Howler products fetched in one run.
- `MAX_SILPO_LOOKUPS_PER_RUN`: Maximum number of previously unpriced products sent to Silpo lookup per run.

## Build and Run

To compile the project, run the following Maven command:

```bash
mvn clean compile
```

To run a specific category parser:

```bash
mvn exec:java -Dexec.mainClass="rum.parser.Main"
mvn exec:java -Dexec.mainClass="wine.parser.Main"
mvn exec:java -Dexec.mainClass="beer.parser.Main"
```

## Output

Each category parser reads from and writes to its own JSON file in the root directory:

- `top_rum_products.json`
- `top_wines.json`
- `top_beers.json`

Each file stores a list of unique, well-rated products along with their ratings, prices, and links to the source websites.
