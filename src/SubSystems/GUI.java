package SubSystems;

import common.*;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;

/**
 * GUI for the drone firefighting system.
 *
 * Overlay approach:
 * - Zones are still laid out as wrappers inside a JLayeredPane
 * - Drones are drawn on ONE shared transparent overlay above the whole map
 * - Drone positions use reported world coordinates (posX, posY)
 * - Base is drawn at global (0,0), which is forced into the visible map bounds
 */
public class GUI extends JFrame {

    private final JLabel firesLabel = new JLabel("Active fires: 0");
    private final JLabel dronesLabel = new JLabel("Drones busy: 0 / 0");
    private final JPanel dronePanel = new JPanel();

    private final Map<Integer, JLabel> droneState = new HashMap<>();
    private final Map<Integer, JLabel> dronePos = new HashMap<>();
    private final Map<Integer, JLabel> droneAgent = new HashMap<>();
    private final Map<Integer, JLabel> droneBattery = new HashMap<>();
    private final Map<Integer, JLabel> droneAssignedZone = new HashMap<>();

    private final Map<Integer, FireEvent> zones = new HashMap<>();
    private final Map<Integer, DroneStatus> drones = new HashMap<>();
    private final Map<Integer, JPanel> zonePanels = new HashMap<>();
    private final Map<Integer, JLabel> zoneInfoLabels = new HashMap<>();

    private GlobalDroneCanvas droneOverlay;

    public GUI(SystemCounts counts) {
        setTitle("Automated Drone Fire Response System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 1000);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new GridLayout(1, 2, 10, 0));
        firesLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        dronesLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        top.add(firesLabel);
        top.add(dronesLabel);

        Map<Integer, int[]> allZoneBounds = ZoneMap.getAllZoneBounds();

        int mapPixelW = 900;
        int mapPixelH = 900;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (int[] b : allZoneBounds.values()) {
            if (b == null || b.length < 4) continue;
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[2]);
            maxY = Math.max(maxY, b[3]);
        }

        if (minX == Double.POSITIVE_INFINITY) {
            minX = 0;
            minY = 0;
            maxX = 2500;
            maxY = 2500;
        }

        // Force global origin (0,0) into the visible map so the launch base is shown.
        minX = Math.min(minX, 0);
        minY = Math.min(minY, 0);
        maxX = Math.max(maxX, 0);
        maxY = Math.max(maxY, 0);

        JLayeredPane mapPanel = new JLayeredPane();
        mapPanel.setPreferredSize(new Dimension(mapPixelW, mapPixelH));
        mapPanel.setLayout(null);

        double worldW = Math.max(1.0, maxX - minX);
        double worldH = Math.max(1.0, maxY - minY);

        int pad = 8;
        double availW = mapPixelW - 2.0 * pad;
        double availH = mapPixelH - 2.0 * pad;

        for (Map.Entry<Integer, int[]> entry : allZoneBounds.entrySet()) {
            int zoneId = entry.getKey();
            int[] b = entry.getValue();
            if (b == null || b.length < 4) continue;

            double nx1 = (b[0] - minX) / worldW;
            double ny1 = (b[1] - minY) / worldH;
            double nx2 = (b[2] - minX) / worldW;
            double ny2 = (b[3] - minY) / worldH;

            int px1 = pad + (int) Math.round(nx1 * availW);
            int py1 = pad + (int) Math.round(ny1 * availH);
            int px2 = pad + (int) Math.round(nx2 * availW);
            int py2 = pad + (int) Math.round(ny2 * availH);

            int pw = Math.max(60, Math.abs(px2 - px1));
            int ph = Math.max(60, Math.abs(py2 - py1));
            int px = Math.min(px1, px2);
            int py = Math.min(py1, py2);

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBackground(Color.WHITE);
            wrapper.setBorder(BorderFactory.createLineBorder(new Color(150, 110, 200), 2));
            wrapper.setOpaque(true);
            wrapper.setBounds(px, py, pw, ph);

            JLabel title = new JLabel("Z(" + zoneId + ")", SwingConstants.LEFT);
            title.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            wrapper.add(title, BorderLayout.NORTH);

            ZoneCanvas canvas = new ZoneCanvas(zoneId, new int[]{b[0], b[1], b[2], b[3]});
            wrapper.add(canvas, BorderLayout.CENTER);

            JLabel info = new JLabel("", SwingConstants.CENTER);
            info.setOpaque(false);
            info.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            wrapper.add(info, BorderLayout.SOUTH);

            mapPanel.add(wrapper, Integer.valueOf(0));

            zonePanels.put(zoneId, wrapper);
            zoneInfoLabels.put(zoneId, info);
        }

        // Shared drone overlay above all zone wrappers
        droneOverlay = new GlobalDroneCanvas(minX, minY, maxX, maxY);
        droneOverlay.setBounds(0, 0, mapPixelW, mapPixelH);
        mapPanel.add(droneOverlay, Integer.valueOf(100));

        JPanel centerWrapper = new JPanel(new BorderLayout());
        centerWrapper.add(mapPanel, BorderLayout.CENTER);

        dronePanel.setLayout(new BoxLayout(dronePanel, BoxLayout.Y_AXIS));

        int totalDrones = counts != null ? Math.max(0, counts.getTotalDrones()) : 0;
        for (int id = 1; id <= totalDrones; id++) {
            addDroneRowIfMissing(id);
        }
        dronePanel.revalidate();

        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(550, 0));
        sidebar.setBorder(BorderFactory.createTitledBorder("Drone Status"));

        JPanel header = new JPanel(new GridLayout(1, 6, 0, 0));
        header.add(boldLabel("Drone"));
        header.add(boldLabel("State"));
        header.add(boldLabel("Position"));
        header.add(boldLabel("Agent"));
        header.add(boldLabel("Battery"));
        header.add(boldLabel("Assigned Zone"));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(header);
        sidebar.add(new JSeparator());

        JScrollPane droneScrollPane = new JScrollPane(dronePanel);
        droneScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        droneScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        droneScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        droneScrollPane.setPreferredSize(new Dimension(530, 800));
        sidebar.add(droneScrollPane);

        root.add(top, BorderLayout.NORTH);
        root.add(centerWrapper, BorderLayout.CENTER);
        root.add(sidebar, BorderLayout.EAST);

        setContentPane(root);

        Timer t = new Timer(200, e -> {
            firesLabel.setText("Active fires: " + zones.size());

            int total = counts != null ? counts.getTotalDrones() : Math.max(droneState.size(), drones.size());
            int busy = counts != null ? counts.getBusyDrones() : calculateBusyDronesFallback();

            dronesLabel.setText("Drones busy: " + busy + " / " + total);
        });
        t.start();

        final JLayeredPane mapPanelFinal = mapPanel;
        final Map<Integer, int[]> allZoneBoundsFinal = new LinkedHashMap<>(allZoneBounds);
        final double minXFinal = minX;
        final double minYFinal = minY;
        final double maxXFinal = maxX;
        final double maxYFinal = maxY;
        final int padFinal = pad;

        repositionZoneWrappers(mapPanelFinal, allZoneBoundsFinal, minXFinal, minYFinal, maxXFinal, maxYFinal, padFinal);

        mapPanelFinal.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                repositionZoneWrappers(mapPanelFinal, allZoneBoundsFinal, minXFinal, minYFinal, maxXFinal, maxYFinal, padFinal);
                if (droneOverlay != null) {
                    droneOverlay.setBounds(0, 0, mapPanelFinal.getWidth(), mapPanelFinal.getHeight());
                    droneOverlay.repaint();
                }
            }
        });
    }

    private int calculateBusyDronesFallback() {
        int busy = 0;
        for (DroneStatus st : drones.values()) {
            if (st == null || st.getState() == null) continue;
            switch (st.getState()) {
                case EN_ROUTE, DROPPING, RETURNING, FAULTED -> busy++;
                default -> {
                }
            }
        }
        return busy;
    }

    private JLabel boldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private void addDroneRowIfMissing(int id) {
        if (droneState.containsKey(id)) {
            return;
        }

        JPanel row = new JPanel(new GridLayout(1, 6, 5, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel idLabel = new JLabel("Drone " + id);
        JLabel stateLabel = new JLabel("N/A");
        JLabel posLabel = new JLabel("(0, 0)");
        JLabel agentLabel = new JLabel("?");
        JLabel batteryLabel = new JLabel("?");
        JLabel assignedZone = new JLabel("N/A");

        idLabel.setHorizontalAlignment(SwingConstants.LEFT);
        stateLabel.setHorizontalAlignment(SwingConstants.LEFT);
        posLabel.setHorizontalAlignment(SwingConstants.LEFT);
        agentLabel.setHorizontalAlignment(SwingConstants.LEFT);
        batteryLabel.setHorizontalAlignment(SwingConstants.LEFT);
        assignedZone.setHorizontalAlignment(SwingConstants.LEFT);

        droneState.put(id, stateLabel);
        dronePos.put(id, posLabel);
        droneAgent.put(id, agentLabel);
        droneBattery.put(id, batteryLabel);
        droneAssignedZone.put(id, assignedZone);

        row.add(idLabel);
        row.add(stateLabel);
        row.add(posLabel);
        row.add(agentLabel);
        row.add(batteryLabel);
        row.add(assignedZone);

        dronePanel.add(row);
        dronePanel.revalidate();
        dronePanel.repaint();
    }

    private void repositionZoneWrappers(JLayeredPane mapPanel, Map<Integer, int[]> allZoneBounds,
                                        double minX, double minY, double maxX, double maxY, int pad) {
        int mapPixelW = Math.max(100, mapPanel.getWidth());
        int mapPixelH = Math.max(100, mapPanel.getHeight());

        double worldW = Math.max(1.0, maxX - minX);
        double worldH = Math.max(1.0, maxY - minY);

        double availW = Math.max(10, mapPixelW - 2.0 * pad);
        double availH = Math.max(10, mapPixelH - 2.0 * pad);

        for (Map.Entry<Integer, int[]> entry : allZoneBounds.entrySet()) {
            int zoneId = entry.getKey();
            int[] b = entry.getValue();
            if (b == null || b.length < 4) continue;

            double nx1 = (b[0] - minX) / worldW;
            double ny1 = (b[1] - minY) / worldH;
            double nx2 = (b[2] - minX) / worldW;
            double ny2 = (b[3] - minY) / worldH;

            int px1 = pad + (int) Math.round(nx1 * availW);
            int py1 = pad + (int) Math.round(ny1 * availH);
            int px2 = pad + (int) Math.round(nx2 * availW);
            int py2 = pad + (int) Math.round(ny2 * availH);

            int pw = Math.max(40, Math.abs(px2 - px1));
            int ph = Math.max(40, Math.abs(py2 - py1));
            int px = Math.min(px1, px2);
            int py = Math.min(py1, py2);

            JPanel wrapper = zonePanels.get(zoneId);
            if (wrapper != null) {
                wrapper.setBounds(px, py, pw, ph);
                wrapper.revalidate();
                wrapper.repaint();
            }
        }
    }

    public void handleIncomingMessage(Message msg) {
        switch (msg.getType()) {
            case DRONE_STATUS, DRONE_DONE -> {
                DroneStatus st = (DroneStatus) msg.getPayload();
                drones.put(st.get_drone_id(), st);
                updateDroneRow(st);
                showDronesInZones();
            }

            case DRONE_TASK -> {
                // no-op
            }

            case FIRE_EVENT -> {
                if (!(msg.getPayload() instanceof FireEvent ev)) {
                    break;
                }
                zones.put(ev.getZoneId(), ev);
                updateZoneColor(ev);
                firesLabel.setText("Active fires: " + zones.size());
                showDronesInZones();
            }

            case FIRE_OUT -> {
                FireEvent ev = (FireEvent) msg.getPayload();
                zones.remove(ev.getZoneId());

                Timer timer = new Timer(600, e -> {
                    markZoneExtinguished(ev);
                    firesLabel.setText("Active fires: " + zones.size());
                    showDronesInZones();
                });
                timer.setRepeats(false);
                timer.start();
            }

            case DRONE_FAULT -> {
                DroneFault fault = (DroneFault) msg.getPayload();
                if (common.DebugOutputFilter.isGUIOutputActive()) {
                    System.out.println("[GUI] DRONE_FAULT: " + fault);
                }
                showDronesInZones();
            }
        }

        repaint();
    }

    private void showDronesInZones() {
        Map<Integer, List<String>> occupantsByZone = new HashMap<>();
        Map<Integer, Boolean> workingZone = new HashMap<>();

        for (Map.Entry<Integer, JLabel> entry : zoneInfoLabels.entrySet()) {
            occupantsByZone.put(entry.getKey(), new ArrayList<>());
            workingZone.put(entry.getKey(), false);
        }

        for (DroneStatus st : drones.values()) {
            Integer droneZoneId = st.get_zone_id();
            if (droneZoneId == null) continue;

            switch (st.getState()) {
                case EN_ROUTE, DROPPING, RETURNING, FAULTED, OFFLINE -> {
                    occupantsByZone.computeIfAbsent(droneZoneId, k -> new ArrayList<>())
                            .add("Drone " + st.get_drone_id() + " - " + st.getState());

                    if (st.getState() == DroneState.EN_ROUTE
                            || st.getState() == DroneState.DROPPING
                            || st.getState() == DroneState.FAULTED) {
                        workingZone.put(droneZoneId, true);
                    }
                }
                default -> {
                }
            }
        }

        for (Integer zoneId : zonePanels.keySet()) {
            JPanel p = zonePanels.get(zoneId);
            if (p == null) continue;

            if (zones.containsKey(zoneId)) {
                updateZoneColor(zones.get(zoneId));
            } else if (Boolean.TRUE.equals(workingZone.get(zoneId))) {
                // Safety resync: if drones are still inbound/working, do not leave it green
                p.setBackground(Color.YELLOW);
            }
        }

        for (Map.Entry<Integer, JLabel> entry : zoneInfoLabels.entrySet()) {
            int zoneId = entry.getKey();
            JLabel label = entry.getValue();
            List<String> occupants = occupantsByZone.getOrDefault(zoneId, Collections.emptyList());

            if (!occupants.isEmpty()) {
                label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                label.setText("<html>" + String.join("<br>", occupants) + "</html>");
            } else if (zones.containsKey(zoneId)) {
                label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                label.setText("Fire active");
            } else {
                label.setText("");
            }
        }

        if (droneOverlay != null) {
            droneOverlay.setDrones(new ArrayList<>(drones.values()));
        }

        repaint();
    }

    private void updateDroneRow(DroneStatus st) {
        int id = st.get_drone_id();
        addDroneRowIfMissing(id);

        droneState.get(id).setText(st.getState().toString());
        dronePos.get(id).setText(
                "(" + String.format("%.0f", st.getPosX())
                        + ", " + String.format("%.0f", st.getPosY()) + ")"
        );
        droneAgent.get(id).setText(
                st.get_remaining_agent() != null ? st.get_remaining_agent() + "L" : "?"
        );
        droneBattery.get(id).setText(
                st.get_battery_level() != null ? st.get_battery_level() + "%" : "?"
        );

        Integer zoneId = st.get_zone_id();
        droneAssignedZone.get(id).setText(zoneId != null ? zoneId.toString() : "N/A");

        JLabel stateLabel = droneState.get(id);
        switch (st.getState()) {
            case IDLE -> stateLabel.setForeground(Color.GRAY);
            case EN_ROUTE -> stateLabel.setForeground(Color.BLUE);
            case DROPPING -> stateLabel.setForeground(Color.ORANGE);
            case RETURNING -> stateLabel.setForeground(new Color(0, 150, 0));
            case FAULTED -> stateLabel.setForeground(Color.RED);
            case OFFLINE -> stateLabel.setForeground(Color.BLACK);
            default -> stateLabel.setForeground(Color.BLACK);
        }

        JLabel batteryLabel = droneBattery.get(id);
        Integer battery = st.get_battery_level();
        if (battery == null) {
            batteryLabel.setForeground(Color.BLACK);
        } else if (battery <= 20) {
            batteryLabel.setForeground(Color.RED);
        } else if (battery <= 50) {
            batteryLabel.setForeground(Color.ORANGE);
        } else {
            batteryLabel.setForeground(new Color(0, 140, 0));
        }
    }

    private void updateZoneColor(FireEvent ev) {
        JPanel p = zonePanels.get(ev.getZoneId());
        if (p == null) return;

        switch (ev.getSeverity()) {
            case HIGH -> p.setBackground(Color.RED);
            case MODERATE -> p.setBackground(Color.ORANGE);
            case LOW -> p.setBackground(Color.YELLOW);
        }
        p.revalidate();
        p.repaint();
    }

    private void markZoneExtinguished(FireEvent ev) {
        JPanel p = zonePanels.get(ev.getZoneId());
        if (p != null) {
            p.setBackground(Color.GREEN);
            p.revalidate();
            p.repaint();
        }

        JLabel label = zoneInfoLabels.get(ev.getZoneId());
        if (label != null) {
            label.setText("Fire out");
        }
    }

    /**
     * Shared transparent overlay that draws all drones using global world coordinates.
     */
    private static class GlobalDroneCanvas extends JComponent {
        private final Map<Integer, DroneStatus> drones = new HashMap<>();
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;

        private static final double CLUSTER_DISTANCE_PX = 18.0; // how close before we separate visually
        private static final double SPREAD_RADIUS_PX = 14.0;    // how far apart clustered drones appear

        GlobalDroneCanvas(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            setOpaque(false);
        }

        void setDrones(List<DroneStatus> dronesList) {
            drones.clear();
            for (DroneStatus st : dronesList) {
                drones.put(st.get_drone_id(), st);
            }
            repaint();
        }

        private Point2D.Double worldToCanvas(double wx, double wy, int w, int h) {
            double pad = 8.0;
            double availW = Math.max(10, w - 2 * pad);
            double availH = Math.max(10, h - 2 * pad);

            double worldW = Math.max(1.0, maxX - minX);
            double worldH = Math.max(1.0, maxY - minY);

            double nx = (wx - minX) / worldW;
            double ny = (wy - minY) / worldH;

            double cx = pad + nx * availW;
            double cy = pad + ny * availH;
            return new Point2D.Double(cx, cy);
        }

        private static class DrawInfo {
            final DroneStatus status;
            final Point2D.Double basePoint;
            Point2D.Double drawPoint;

            DrawInfo(DroneStatus status, Point2D.Double basePoint) {
                this.status = status;
                this.basePoint = basePoint;
                this.drawPoint = basePoint;
            }
        }

        private List<List<DrawInfo>> clusterCloseDrones(List<DrawInfo> points) {
            List<List<DrawInfo>> clusters = new ArrayList<>();
            boolean[] used = new boolean[points.size()];

            for (int i = 0; i < points.size(); i++) {
                if (used[i]) continue;

                List<DrawInfo> cluster = new ArrayList<>();
                cluster.add(points.get(i));
                used[i] = true;

                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (int j = 0; j < points.size(); j++) {
                        if (used[j]) continue;

                        for (DrawInfo existing : cluster) {
                            double dx = points.get(j).basePoint.x - existing.basePoint.x;
                            double dy = points.get(j).basePoint.y - existing.basePoint.y;
                            double dist = Math.hypot(dx, dy);

                            if (dist <= CLUSTER_DISTANCE_PX) {
                                cluster.add(points.get(j));
                                used[j] = true;
                                changed = true;
                                break;
                            }
                        }
                    }
                }

                clusters.add(cluster);
            }

            return clusters;
        }

        private void applyVisualOffsets(List<DrawInfo> infos) {
            List<List<DrawInfo>> clusters = clusterCloseDrones(infos);

            for (List<DrawInfo> cluster : clusters) {
                if (cluster.size() == 1) {
                    cluster.get(0).drawPoint = cluster.get(0).basePoint;
                    continue;
                }

                cluster.sort(Comparator.comparingInt(a -> a.status.get_drone_id()));

                double centerX = 0;
                double centerY = 0;
                for (DrawInfo info : cluster) {
                    centerX += info.basePoint.x;
                    centerY += info.basePoint.y;
                }
                centerX /= cluster.size();
                centerY /= cluster.size();

                for (int i = 0; i < cluster.size(); i++) {
                    double angle = (2 * Math.PI * i) / cluster.size();
                    double ox = Math.cos(angle) * SPREAD_RADIUS_PX;
                    double oy = Math.sin(angle) * SPREAD_RADIUS_PX;

                    cluster.get(i).drawPoint = new Point2D.Double(centerX + ox, centerY + oy);
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                // Draw launch base at global (0,0)
                Point2D.Double base = worldToCanvas(0, 0, w, h);
                g2.setColor(Color.BLACK);
                g2.fillOval((int) base.x - 6, (int) base.y - 6, 12, 12);
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                g2.drawString("Base (0,0)", (int) base.x + 10, (int) base.y - 8);

                List<DrawInfo> drawInfos = new ArrayList<>();
                for (DroneStatus st : drones.values()) {
                    Point2D.Double p = worldToCanvas(st.getPosX(), st.getPosY(), w, h);
                    drawInfos.add(new DrawInfo(st, p));
                }

                applyVisualOffsets(drawInfos);

                for (DrawInfo info : drawInfos) {
                    DroneStatus st = info.status;
                    Point2D.Double p = info.drawPoint;

                    Color c = switch (st.getState()) {
                        case IDLE -> Color.GRAY;
                        case EN_ROUTE -> Color.BLUE;
                        case DROPPING -> Color.ORANGE;
                        case RETURNING -> new Color(0, 150, 0);
                        case FAULTED -> Color.RED;
                        case OFFLINE -> Color.DARK_GRAY;
                        default -> Color.BLACK;
                    };

                    // Optional: draw a faint line from true position to offset position
                    if (Math.hypot(info.drawPoint.x - info.basePoint.x, info.drawPoint.y - info.basePoint.y) > 1.0) {
                        g2.setColor(new Color(0, 0, 0, 60));
                        g2.drawLine(
                                (int) info.basePoint.x, (int) info.basePoint.y,
                                (int) info.drawPoint.x, (int) info.drawPoint.y
                        );
                    }

                    if (st.getState() != DroneState.IDLE && st.getState() != DroneState.DONE) {
                        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
                        g2.fillOval((int) p.x - 10, (int) p.y - 10, 20, 20);
                    }

                    g2.setColor(c);
                    g2.fillOval((int) p.x - 6, (int) p.y - 6, 12, 12);

                    g2.setColor(Color.BLACK);
                    g2.drawString("D" + st.get_drone_id(), (int) p.x + 8, (int) p.y + 4);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Simple zone canvas: just shows the zone box area visually.
     * Drones are NOT drawn here anymore.
     */
    private static class ZoneCanvas extends JComponent {
        private final int worldX1;
        private final int worldY1;
        private final int worldX2;
        private final int worldY2;

        ZoneCanvas(int zoneId, int[] bounds) {
            if (bounds != null && bounds.length == 4) {
                this.worldX1 = bounds[0];
                this.worldY1 = bounds[1];
                this.worldX2 = bounds[2];
                this.worldY2 = bounds[3];
            } else {
                this.worldX1 = 0;
                this.worldY1 = 0;
                this.worldX2 = 0;
                this.worldY2 = 0;
            }
            setPreferredSize(new Dimension(200, 120));
            setOpaque(false);
        }

        private Point2D.Double worldToCanvas(double wx, double wy, int w, int h) {
            double pad = 8.0;
            double availW = Math.max(10, w - 2 * pad);
            double availH = Math.max(10, h - 2 * pad);

            double worldW = Math.max(1.0, worldX2 - worldX1);
            double worldH = Math.max(1.0, worldY2 - worldY1);

            double nx = (wx - worldX1) / worldW;
            double ny = (wy - worldY1) / worldH;

            double cx = pad + nx * availW;
            double cy = pad + ny * availH;
            return new Point2D.Double(cx, cy);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                Point2D.Double c1 = worldToCanvas(worldX1, worldY1, w, h);
                Point2D.Double c2 = worldToCanvas(worldX2, worldY2, w, h);

                double rx = Math.min(c1.x, c2.x);
                double ry = Math.min(c1.y, c2.y);
                double rw = Math.abs(c2.x - c1.x);
                double rh = Math.abs(c2.y - c1.y);

                g2.setColor(new Color(255, 255, 255, 70));
                g2.fillRect((int) rx, (int) ry, (int) Math.max(1, rw), (int) Math.max(1, rh));

                g2.setColor(new Color(100, 140, 200));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect((int) rx, (int) ry, (int) Math.max(1, rw), (int) Math.max(1, rh));
            } finally {
                g2.dispose();
            }
        }
    }
}