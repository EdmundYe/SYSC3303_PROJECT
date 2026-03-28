package common;

import java.util.Map;

/*
Map class to help with drone tracking
 */

public class ZoneMap {
    private static final Map<Integer, int[]> COORDS = Map.of(
            1, new int[] {500, 0},
            2, new int[] {800, 400},
            3, new int[] {1200, 200},
            4, new int[] {1500, 600}
     );

    public static int[] get(int zoneId){
        return COORDS.getOrDefault(zoneId, new int[]{1000, 0});
    }

    public static boolean isOnPath(int targetZone, int candidateZone, double thresholdMeters){
        int[] dest   = get(targetZone);
        int[] cand   = get(candidateZone);

        // vector from base(0,0) to dest
        double dx = dest[0], dy = dest[1];
        double len = Math.hypot(dx, dy);

        // project candidate onto that line
        double t = (cand[0] * dx + cand[1] * dy) / (len * len);
        if (t < 0 || t > 1) return false; // outside the segment

        double projX = t * dx;
        double projY = t * dy;
        double perpDist = Math.hypot(cand[0] - projX, cand[1] - projY);

        return perpDist <= thresholdMeters;
    }

    public static double distanceFromBase(int zoneId) {
        int[] z = get(zoneId);
        return Math.hypot(z[0], z[1]); // distance from (0,0) to zone center
    }
}
