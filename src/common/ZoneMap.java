package common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/*
Map class to help with drone tracking
 */

public class ZoneMap {
    private static Map<Integer, int[]> COORDS = null;
    private static final String ZONES_FILE = "src/zones.csv";

    // Default backup zones - used if zones.csv file is not found
    private static final Map<Integer, int[]> DEFAULT_COORDS = Map.of(
            1, new int[] {500, 0},
            2, new int[] {800, 400},
            3, new int[] {1200, 200},
            4, new int[] {1500, 600}
    );

    /**
     * Lazy loading: zones are loaded from CSV file on first access
     * Subsequent calls will use the cached COORDS map
     */
    private static void ensureLoaded() {
        if (COORDS == null) {
            COORDS = loadZonesFromFile();
        }
    }

    /**
     * Load zone configurations from CSV file at runtime
     * CSV format: ZoneId,X,Y
     * Falls back to default hardcoded zones if file cannot be read
     *
     * @return Map of zone IDs to coordinate arrays [x, y]
     */
    private static Map<Integer, int[]> loadZonesFromFile() {
        Map<Integer, int[]> zones = new HashMap<>();

        try {
            Files.lines(Paths.get(ZONES_FILE))
                    .skip(1)  // Skip header row
                    .forEach(line -> {
                        try {
                            String[] parts = line.trim().split(",");
                            if (parts.length == 3) {
                                int zoneId = Integer.parseInt(parts[0].trim());
                                int x = Integer.parseInt(parts[1].trim());
                                int y = Integer.parseInt(parts[2].trim());
                                zones.put(zoneId, new int[]{x, y});
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("[ZONEMAP] Skipping invalid zone line: " + line);
                        }
                    });

            // If zones were successfully loaded, log and return them
            if (!zones.isEmpty()) {
                System.out.println("[ZONEMAP] Successfully loaded " + zones.size() + " zones from " + ZONES_FILE);
                return zones;
            }
        } catch (IOException e) {
            System.err.println("[ZONEMAP] Could not read zones file: " + e.getMessage());
        }

        // File not found or is empty - use default hardcoded zones
        System.out.println("[ZONEMAP] Using default hardcoded zones (zones.csv not found or invalid)");
        return new HashMap<>(DEFAULT_COORDS);
    }

    public static int[] get(int zoneId){
        ensureLoaded();
        return COORDS.getOrDefault(zoneId, new int[]{1000, 0});
    }

    public static boolean isOnPath(int targetZone, int candidateZone, double thresholdMeters){
        ensureLoaded();

        int[] dest = get(targetZone);
        int[] cand = get(candidateZone);

        // vector from base(0,0) to dest
        double dx = dest[0], dy = dest[1];
        double len = Math.hypot(dx, dy);

        if (len == 0) return false;

        // project candidate onto that line
        double t = (cand[0] * dx + cand[1] * dy) / (len * len);
        if (t < 0 || t > 1) return false; // outside the segment

        double projX = t * dx;
        double projY = t * dy;
        double perpDist = Math.hypot(cand[0] - projX, cand[1] - projY);

        return perpDist <= thresholdMeters;
    }

    public static double distanceFromBase(int zoneId) {
        ensureLoaded();
        int[] z = get(zoneId);
        return Math.hypot(z[0], z[1]); // distance from (0,0) to zone center
    }

    public static Map<Integer, int[]> getAllZones() {
        ensureLoaded();
        return new HashMap<>(COORDS);
    }
}
