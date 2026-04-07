package SubSystems;

import common.*;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI for the drone firefighting system.
 * Layout updated to place existing ZoneCanvas instances into a single map panel
 * positioned according to zone world bounds. All ZoneCanvas internals and animations
 * are preserved; this change is purely layout/visual.
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
    private final Map<Integer, ZoneCanvas> zoneCanvases = new HashMap<>();

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

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int[] b : allZoneBounds.values()) {
            if (b == null || b.length < 4) continue;
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[2]);
            maxY = Math.max(maxY, b[3]);
        }
        if (minX == Double.POSITIVE_INFINITY) { minX = 0; minY = 0; maxX = 2500; maxY = 2500; }

        JLayeredPane mapPanel = new JLayeredPane();
        mapPanel.setPreferredSize(new Dimension(mapPixelW, mapPixelH));
        mapPanel.setLayout(null);

        double worldW = Math.max(1.0, maxX - minX);
        double worldH = Math.max(1.0, maxY - minY);

        int pad = 8;
        double availW = mapPixelW - 2 * pad;
        double availH = mapPixelH - 2 * pad;

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
            zoneCanvases.put(zoneId, canvas);
        }

        JPanel centerWrapper = new JPanel(new BorderLayout());
        centerWrapper.add(mapPanel, BorderLayout.CENTER);

        dronePanel.setLayout(new BoxLayout(dronePanel, BoxLayout.Y_AXIS));

        int totalDrones = Math.max(0, counts.getTotalDrones());
        for (int id = 1; id <= totalDrones; id++) {
            if (!droneState.containsKey(id)) {
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
            }
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
            dronesLabel.setText("Drones busy: " + counts.getBusyDrones() + " / " + counts.getTotalDrones());
        });
        t.start();

        Timer animationTimer = new Timer(80, e -> {
            for (ZoneCanvas c : zoneCanvases.values()) {
                c.tick();
            }
        });
        animationTimer.start();
        final JLayeredPane mapPanelFinal = mapPanel;
        final Map<Integer,int[]> allZoneBoundsFinal = new LinkedHashMap<>(allZoneBounds);
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
            }
        });

    }

    private JLabel boldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private JPanel makeZoneCell(String label, int zoneId, int[] bounds) {
        // kept for compatibility but not used in the new layout
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createLineBorder(new Color(150, 110, 200), 2));

        JLabel l = new JLabel(label);
        l.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        p.add(l, BorderLayout.NORTH);

        // center panel that holds the canvas and the info label
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);

        ZoneCanvas canvas = new ZoneCanvas(zoneId, bounds);
        zoneCanvases.put(zoneId, canvas);
        centerPanel.add(canvas, BorderLayout.CENTER);

        JLabel info = new JLabel("", SwingConstants.CENTER);
        zoneInfoLabels.put(zoneId, info);
        info.setOpaque(false);
        info.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        centerPanel.add(info, BorderLayout.SOUTH);

        p.add(centerPanel, BorderLayout.CENTER);

        zonePanels.put(zoneId, p);
        return p;
    }

    private void repositionZoneWrappers(JLayeredPane mapPanel, Map<Integer,int[]> allZoneBounds,
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
                // no-op for now
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
                markZoneExtinguished(ev);
                firesLabel.setText("Active fires: " + zones.size());
                showDronesInZones();
            }
            case DRONE_FAULT -> {
                DroneFault fault = (DroneFault) msg.getPayload();
                if (common.DebugOutputFilter.isGUIOutputActive())
                    System.out.println("[GUI] DRONE_FAULT: " + fault);
                showDronesInZones();
            }
        }
        repaint();
    }

    private void showDronesInZones() {
        Map<Integer, List<String>> occupantsByZone = new HashMap<>();
        for (Map.Entry<Integer, JLabel> entry : zoneInfoLabels.entrySet()) {
            occupantsByZone.put(entry.getKey(), new ArrayList<>());
        }

        for (DroneStatus st : drones.values()) {
            Integer droneZoneId = st.get_zone_id();
            if (droneZoneId == null) continue;

            switch (st.getState()) {
                case EN_ROUTE, DROPPING, RETURNING, FAULTED, OFFLINE ->
                        occupantsByZone.computeIfAbsent(droneZoneId, k -> new ArrayList<>())
                                .add("Drone " + st.get_drone_id() + " - " + st.getState());
                default -> {
                }
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

        Map<Integer, List<DroneStatus>> perZone = new HashMap<>();
        for (DroneStatus st : drones.values()) {
            Integer zid = st.get_zone_id();
            if (zid == null) continue;
            perZone.computeIfAbsent(zid, k -> new ArrayList<>()).add(st);
        }

        for (Map.Entry<Integer, ZoneCanvas> e : zoneCanvases.entrySet()) {
            int zid = e.getKey();
            ZoneCanvas c = e.getValue();
            List<DroneStatus> list = perZone.getOrDefault(zid, Collections.emptyList());
            c.setDrones(list);
        }
    }

    private void updateDroneRow(DroneStatus st) {
        int id = st.get_drone_id();

        if (!droneState.containsKey(id)) {
            JPanel row = new JPanel(new GridLayout(1, 6, 5, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
            row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel idLabel = new JLabel("Drone " + id);
            JLabel stateLabel = new JLabel("IDLE");
            JLabel posLabel = new JLabel("(0, 0)");
            JLabel agentLabel = new JLabel("100L");
            JLabel batteryLabel = new JLabel("100%");
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
        }

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

    // Inner class: ZoneCanvas
    // Draws zone rectangle (start/end) and animates drones relative to the zone center.
    private static class ZoneCanvas extends JComponent {
        // per-drone animation state keyed by drone id
        private final Map<Integer, DroneAnim> anims = new ConcurrentHashMap<>();
        // animation parameters
        private final double baseRadius = 18.0;

        // world bounds for this zone: [x1,y1,x2,y2]
        private final int worldX1, worldY1, worldX2, worldY2;

        ZoneCanvas(int zoneId, int[] bounds) {
            // bounds expected as [x1,y1,x2,y2]
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

        // Map a world coordinate (wx,wy) into canvas coordinates with padding
        private Point2D.Double worldToCanvas(double wx, double wy, int w, int h) {
            // Add small padding inside canvas
            double pad = 8.0;
            double availW = Math.max(10, w - 2 * pad);
            double availH = Math.max(10, h - 2 * pad);

            double wx1 = worldX1;
            double wy1 = worldY1;
            double wx2 = worldX2;
            double wy2 = worldY2;

            double worldW = Math.max(1.0, wx2 - wx1);
            double worldH = Math.max(1.0, wy2 - wy1);

            double nx = (wx - wx1) / worldW; // 0..1
            double ny = (wy - wy1) / worldH; // 0..1

            double cx = pad + nx * availW;
            double cy = pad + ny * availH;
            return new Point2D.Double(cx, cy);
        }

        // Always show all drones passed in. New drones ARRIVE from edge. Missing drones LEAVE outward.
        void setDrones(List<DroneStatus> dronesList) {
            Set<Integer> newIds = new HashSet<>();
            int idx = 0;
            for (DroneStatus st : dronesList) {
                int id = st.get_drone_id();
                newIds.add(id);
                DroneAnim a = anims.get(id);
                if (a == null) {
                    // new arrival: start at random edge point
                    Point2D.Double start = randomEdgePoint();
                    double targetR = baseRadius + (idx % 3) * 6;
                    a = new DroneAnim(st, start.x, start.y, targetR);
                    a.state = DronePhase.ARRIVING;
                    anims.put(id, a);
                } else {
                    // update status payload so color/state reflect latest info
                    a.status = st;
                    // if it was leaving but reappeared, bring it back to arriving to animate re-entry
                    if (a.state == DronePhase.LEAVING) {
                        a.state = DronePhase.ARRIVING;
                    }
                }
                idx++;
            }

            // mark missing drones as leaving
            for (Integer id : new ArrayList<>(anims.keySet())) {
                if (!newIds.contains(id)) {
                    DroneAnim a = anims.get(id);
                    if (a != null && a.state != DronePhase.LEAVING) {
                        a.state = DronePhase.LEAVING;
                        // compute outward direction from zone center in canvas coords
                        a.computeLeaveVector(getWidth(), getHeight());
                    }
                }
            }
            repaint();
        }

        void tick() {
            int w = getWidth();
            int h = getHeight();

            // compute zone center in canvas coords
            double centerWorldX = (worldX1 + worldX2) / 2.0;
            double centerWorldY = (worldY1 + worldY2) / 2.0;
            Point2D.Double centerCanvas = worldToCanvas(centerWorldX, centerWorldY, w, h);
            double cx = centerCanvas.x;
            double cy = centerCanvas.y;

            for (Iterator<Map.Entry<Integer, DroneAnim>> it = anims.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, DroneAnim> en = it.next();
                DroneAnim a = en.getValue();
                switch (a.state) {
                    case ARRIVING -> {
                        // move toward zone center
                        double dx = cx - a.x;
                        double dy = cy - a.y;
                        double dist = Math.hypot(dx, dy);
                        // pixels per tick for arrival
                        double arriveSpeed = 12.0;
                        if (dist <= arriveSpeed + 1.0) {
                            // arrived: switch to circling
                            a.state = DronePhase.CIRCLING;
                            a.angle = Math.random() * Math.PI * 2;
                            // set radius if not set
                            if (a.radius <= 0) a.radius = baseRadius;
                        } else {
                            a.x += (dx / dist) * arriveSpeed;
                            a.y += (dy / dist) * arriveSpeed;
                        }
                    }
                    case CIRCLING -> {
                        // radians per tick for circling
                        double angularSpeed = 0.25;
                        a.angle += angularSpeed;
                        double r = a.radius > 0 ? a.radius : baseRadius;
                        a.x = cx + Math.cos(a.angle + a.offset) * r;
                        a.y = cy + Math.sin(a.angle + a.offset) * r;
                    }
                    case LEAVING -> {
                        // move along leave vector
                        // pixels per tick for leaving
                        double leaveSpeed = 14.0;
                        a.x += a.leaveVx * leaveSpeed;
                        a.y += a.leaveVy * leaveSpeed;
                        // if outside canvas bounds by margin, remove
                        if (a.x < -20 || a.x > w + 20 || a.y < -20 || a.y > h + 20) {
                            it.remove();
                        }
                    }
                }
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();

                // subtle background
                g2.setColor(new Color(245, 245, 245));
                g2.fillRect(0, 0, w, h);

                // draw zone rectangle mapped from world bounds
                // compute canvas coords for corners
                Point2D.Double c1 = worldToCanvas(worldX1, worldY1, w, h);
                Point2D.Double c2 = worldToCanvas(worldX2, worldY2, w, h);

                double rx = Math.min(c1.x, c2.x);
                double ry = Math.min(c1.y, c2.y);
                double rw = Math.abs(c2.x - c1.x);
                double rh = Math.abs(c2.y - c1.y);

                // filled subtle zone area
                g2.setColor(new Color(230, 240, 255, 60));
                g2.fillRect((int) rx, (int) ry, (int) Math.max(1, rw), (int) Math.max(1, rh));

                // rectangle border
                g2.setColor(new Color(100, 140, 200));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect((int) rx, (int) ry, (int) Math.max(1, rw), (int) Math.max(1, rh));

                // draw each anim (drones)
                for (DroneAnim a : anims.values()) {
                    int id = a.status.get_drone_id();
                    double x = a.x;
                    double y = a.y;

                    // color by state
                    Color c = switch (a.status.getState()) {
                        case IDLE -> Color.GRAY;
                        case EN_ROUTE -> Color.BLUE;
                        case DROPPING -> Color.ORANGE;
                        case RETURNING -> new Color(0, 150, 0);
                        case FAULTED -> Color.RED;
                        case OFFLINE -> Color.DARK_GRAY;
                        default -> Color.BLACK;
                    };

                    // halo for busy drones
                    if (a.status.getState() != DroneState.IDLE && a.status.getState() != DroneState.DONE) {
                        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
                        g2.fillOval((int) (x - 10), (int) (y - 10), 20, 20);
                    }

                    // drone circle
                    g2.setColor(c);
                    g2.fillOval((int) (x - 6), (int) (y - 6), 12, 12);

                    // id label
                    g2.setColor(Color.BLACK);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                    g2.drawString("D" + id, (int) (x + 9), (int) (y + 4));
                }
            } finally {
                g2.dispose();
            }
        }

        // helper: random point on canvas edge
        private Point2D.Double randomEdgePoint() {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            double side = Math.random();
            if (side < 0.25) {
                // left edge
                return new Point2D.Double(-10, Math.random() * h);
            } else if (side < 0.5) {
                // right edge
                return new Point2D.Double(w + 10, Math.random() * h);
            } else if (side < 0.75) {
                // top edge
                return new Point2D.Double(Math.random() * w, -10);
            } else {
                // bottom edge
                return new Point2D.Double(Math.random() * w, h + 10);
            }
        }

        // Drone animation record
        private static class DroneAnim {
            DroneStatus status;
            double x;
            double y;
            double radius;
            double angle;
            double offset; // small offset so multiple drones don't overlap exactly
            DronePhase state;
            // leaving vector normalized
            double leaveVx;
            double leaveVy;

            DroneAnim(DroneStatus status, double x, double y, double radius) {
                this.status = status;
                this.x = x;
                this.y = y;
                this.radius = radius;
                this.angle = Math.random() * Math.PI * 2;
                this.offset = Math.random() * Math.PI * 2;
                this.state = DronePhase.ARRIVING;
                this.leaveVx = 0;
                this.leaveVy = 0;
            }

            void computeLeaveVector(int w, int h) {
                double cx = w / 2.0;
                double cy = h / 2.0;
                double dx = x - cx;
                double dy = y - cy;
                double dist = Math.hypot(dx, dy);
                if (dist == 0) {
                    // random outward
                    double a = Math.random() * Math.PI * 2;
                    leaveVx = Math.cos(a);
                    leaveVy = Math.sin(a);
                } else {
                    leaveVx = dx / dist;
                    leaveVy = dy / dist;
                }
            }
        }

        private enum DronePhase {
            ARRIVING,
            CIRCLING,
            LEAVING
        }
    }
}
