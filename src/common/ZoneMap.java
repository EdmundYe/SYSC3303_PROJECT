package common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/*
Map class to help with drone tracking
 */

public class ZoneMap {
    private static volatile Map<Integer, int[]> COORDS = null; // centers
    private static volatile Map<Integer, int[]> BOUNDS = null; // [x1,y1,x2,y2]
    private static final String ZONES_FILE = "src/Final_zone_file_w26.csv";

    // Default backup zones - centers computed from rectangle examples
    private static final Map<Integer, int[]> DEFAULT_COORDS = Map.of(
            1, new int[] {450, 450},
            2, new int[] {1700, 450},
            3, new int[] {600, 1350},
            4, new int[] {1850, 1350},
            5, new int[] {1250, 2150}
    );

    // Default bounds (used only if CSV missing)
    private static final Map<Integer, int[]> DEFAULT_BOUNDS = Map.of(
            1, new int[] {0, 0, 900, 900},
            2, new int[] {900, 0, 2500, 900},
            3, new int[] {0, 900, 1200, 1800},
            4, new int[] {1200, 900, 2500, 1800},
            5, new int[] {0, 1800, 2500, 2500}
    );

    /**
     * Lazy loading: zones are loaded from CSV file on first access
     * Subsequent calls will use the cached COORDS and BOUNDS maps
     *
     * Thread-safe: uses double-checked locking with volatile fields.
     */
    private static void ensureLoaded() {
        if (COORDS == null || BOUNDS == null) { // fast path
            synchronized (ZoneMap.class) {
                if (COORDS == null || BOUNDS == null) { // safe init
                    loadZonesFromFileAndPopulate();
                }
            }
        }
    }

    /**
     * Load zone configurations from CSV file at runtime.
     * Supports:
     *  - New rectangle format: Zone ID,(x;y),(x;y)
     *  - Legacy point format: ZoneId,X,Y
     *
     * Populates both COORDS (centers) and BOUNDS ([x1,y1,x2,y2]).
     */
    private static void loadZonesFromFileAndPopulate() {
        Map<Integer, int[]> centers = new HashMap<>();
        Map<Integer, int[]> bounds = new HashMap<>();
        Path p = Paths.get(ZONES_FILE);

        if (common.DebugOutputFilter.isZoneMapDebugActive()) {
            System.out.println("[ZONEMAP] Attempting to load zones from: " + p.toAbsolutePath());
        }

        try {
            Files.lines(p)
                    .skip(1) // skip header
                    .forEach(line -> {
                        try {
                            String raw = line;
                            String trimmed = raw.trim();
                            if (trimmed.isEmpty()) return;

                            if (common.DebugOutputFilter.isZoneMapDebugActive()) {
                                System.out.println("[ZONEMAP] Raw line: " + raw);
                            }

                            // split into at most 3 parts
                            String[] parts = trimmed.split(",", 3);
                            if (parts.length != 3) {
                                if (common.DebugOutputFilter.isZoneMapDebugActive())
                                    System.err.println("[ZONEMAP] Unexpected number of fields, skipping: " + raw);
                                return;
                            }

                            int zoneId;
                            try {
                                zoneId = Integer.parseInt(parts[0].trim());
                            } catch (NumberFormatException nfe) {
                                if (common.DebugOutputFilter.isZoneMapDebugActive())
                                    System.err.println("[ZONEMAP] Invalid zone id, skipping: " + raw);
                                return;
                            }

                            String f1 = parts[1].trim();
                            String f2 = parts[2].trim();

                            // Try rectangle "(x;y)" format first
                            int[] start = parseParenCoord(f1);
                            int[] end = parseParenCoord(f2);
                            if (start != null && end != null) {
                                int x1 = Math.min(start[0], end[0]);
                                int y1 = Math.min(start[1], end[1]);
                                int x2 = Math.max(start[0], end[0]);
                                int y2 = Math.max(start[1], end[1]);
                                bounds.put(zoneId, new int[]{x1, y1, x2, y2});
                                int centerX = (x1 + x2) / 2;
                                int centerY = (y1 + y2) / 2;
                                centers.put(zoneId, new int[]{centerX, centerY});
                                if (common.DebugOutputFilter.isZoneMapDebugActive())
                                    System.out.println("[ZONEMAP] Parsed rect zone " + zoneId + " -> bounds (" + x1 + "," + y1 + "," + x2 + "," + y2 + ")");
                                return;
                            }

                            // Fallback: legacy numeric X,Y
                            try {
                                int x = Integer.parseInt(f1);
                                int y = Integer.parseInt(f2);
                                bounds.put(zoneId, new int[]{x, y, x, y});
                                centers.put(zoneId, new int[]{x, y});
                                if (common.DebugOutputFilter.isZoneMapDebugActive())
                                    System.out.println("[ZONEMAP] Parsed legacy zone " + zoneId + " -> (" + x + "," + y + ")");
                                return;
                            } catch (NumberFormatException ignored) {
                                // fall through to error log
                            }

                            if (common.DebugOutputFilter.isZoneMapDebugActive())
                                System.err.println("[ZONEMAP] Invalid coordinate format, skipping: " + raw);

                        } catch (Exception e) {
                            if (common.DebugOutputFilter.isZoneMapDebugActive())
                                System.err.println("[ZONEMAP] Skipping invalid zone line (unexpected): " + line + " -> " + e.getMessage());
                        }
                    });

            if (!centers.isEmpty()) {
                if (common.DebugOutputFilter.isZoneMapDebugActive())
                    System.out.println("[ZONEMAP] Successfully loaded " + centers.size() + " zones from " + p.toAbsolutePath());
                COORDS = centers;
                BOUNDS = bounds;
                return;
            }
        } catch (IOException e) {
            if (common.DebugOutputFilter.isZoneMapDebugActive())
                System.err.println("[ZONEMAP] Could not read zones file: " + e.getMessage());
        }

        if (common.DebugOutputFilter.isZoneMapDebugActive())
            System.out.println("[ZONEMAP] Using default hardcoded zones (zones.csv not found or invalid)");
        COORDS = new HashMap<>(DEFAULT_COORDS);
        BOUNDS = new HashMap<>(DEFAULT_BOUNDS);
    }

    /**
     * Parse a coordinate string of the form "(x;y)" where x and y are integers.
     * Returns int[]{x,y} or null if parsing fails.
     */
    private static int[] parseParenCoord(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("(") && s.endsWith(")")) {
            String inner = s.substring(1, s.length() - 1).trim();
            String[] xy = inner.split(";");
            if (xy.length == 2) {
                try {
                    int x = Integer.parseInt(xy[0].trim());
                    int y = Integer.parseInt(xy[1].trim());
                    return new int[]{x, y};
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // Existing API: returns center [x,y]
    public static int[] get(int zoneId){
        ensureLoaded();
        return COORDS.getOrDefault(zoneId, new int[]{1000, 0});
    }

    // New API: returns bounds [x1,y1,x2,y2]
    public static int[] getZoneBounds(int zoneId) {
        ensureLoaded();
        return BOUNDS.getOrDefault(zoneId, new int[]{0,0,0,0});
    }

    // New API: returns copy of all bounds
    public static Map<Integer, int[]> getAllZoneBounds() {
        ensureLoaded();
        return new HashMap<>(BOUNDS);
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
