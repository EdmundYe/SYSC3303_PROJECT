package SubSystems;

import common.*;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

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

    public GUI(SystemCounts counts) {
        setTitle("Automated Drone Fire Response System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 1000);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Top counter bar
        JPanel top = new JPanel(new GridLayout(1, 2, 10, 0));
        firesLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        dronesLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        top.add(firesLabel);
        top.add(dronesLabel);

        // Load zones dynamically from ZoneMap
        Map<Integer, int[]> allZones = ZoneMap.getAllZones();
        int zoneCount = allZones.size();

        // Calculate grid dimensions (roughly square)
        int cols = (int) Math.ceil(Math.sqrt(zoneCount));
        int rows = (int) Math.ceil((double) zoneCount / cols);

        JPanel grid = new JPanel(new GridLayout(rows, cols, 10, 10));

        // Add zone cells dynamically
        for (Map.Entry<Integer, int[]> entry : allZones.entrySet()) {
            int zoneId = entry.getKey();
            int[] coords = entry.getValue();
            grid.add(makeZoneCell("Z(" + zoneId + ") [" + coords[0] + "," + coords[1] + "]", zoneId));
        }

        // Sidebar
        dronePanel.setLayout(new BoxLayout(dronePanel, BoxLayout.Y_AXIS));
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

        dronePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(dronePanel);

        root.add(top, BorderLayout.NORTH);
        root.add(grid, BorderLayout.CENTER);
        root.add(sidebar, BorderLayout.EAST);

        setContentPane(root);

        // Poll counts periodically
        Timer t = new Timer(200, e -> {
            firesLabel.setText("Active fires: " + zones.size());
            dronesLabel.setText("Drones busy: " + counts.getBusyDrones() + " / " + counts.getTotalDrones());
        });
        t.start();
    }

    private JLabel boldLabel(String text){
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private JPanel makeZoneCell(String label, int zoneId) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createLineBorder(new Color(150, 110, 200), 2));

        JLabel l = new JLabel(label);
        l.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        p.add(l, BorderLayout.NORTH);

        JLabel info = new JLabel("", SwingConstants.CENTER);
        zoneInfoLabels.put(zoneId, info);
        p.add(info, BorderLayout.CENTER);

        zonePanels.put(zoneId, p);
        return p;
    }

    public void handleIncomingMessage(Message msg) {
        System.out.println("[GUI] RECEIVED MESSAGE");
        switch (msg.getType()) {
            case DRONE_STATUS -> {
                DroneStatus st = (DroneStatus) msg.getPayload();
                drones.put(st.get_drone_id(), st);
                updateDroneRow(st);

                long busy = drones.values().stream()
                        .filter(d -> d.getState() != DroneState.IDLE)
                        .count();

                dronesLabel.setText("Drones busy: " + busy + " / " + drones.size());
                showDroneInZone(st);
            }
            case DRONE_TASK -> {
                // no-op for now
            }
            case DRONE_DONE -> {
                DroneStatus st = (DroneStatus) msg.getPayload();
                drones.put(st.get_drone_id(), st);
                updateDroneRow(st);
            }
            case FIRE_EVENT -> {
                if (!(msg.getPayload() instanceof FireEvent ev)) {
                    break;
                }
                zones.put(ev.getZoneId(), ev);
                updateZoneColor(ev);
                firesLabel.setText("Active fires: " + zones.size());
            }
            case FIRE_OUT -> {
                FireEvent ev = (FireEvent) msg.getPayload();
                zones.remove(ev.getZoneId());
                markZoneExtinguished(ev);
                firesLabel.setText("Active fires: " + zones.size());
            }
            case DRONE_FAULT -> {
                DroneFault fault = (DroneFault) msg.getPayload();
                System.out.println("[GUI] DRONE_FAULT: " + fault);
            }
        }
        repaint();
    }

    private void showDroneInZone(DroneStatus st){
        // Clear previous drone display in all zones
        for (Map.Entry<Integer, JLabel> entry : zoneInfoLabels.entrySet()) {
            String text = entry.getValue().getText();
            if (text.contains("Drone " + st.get_drone_id()) || text.contains("DRONE " + st.get_drone_id())) {
                entry.getValue().setText("");
            }
        }

        Integer zoneId = st.get_zone_id();
        if (zoneId == null) return;

        // Show drone in zone during EN_ROUTE, DROPPING, RETURNING states
        switch (st.getState()){
            case EN_ROUTE, DROPPING, RETURNING -> {
                JLabel label = zoneInfoLabels.get(zoneId);
                if (label != null){
                    label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                    label.setText("DRONE " + st.get_drone_id());
                }
            }
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
        if (p != null){
            p.setBackground(Color.GREEN);
            p.revalidate();
            p.repaint();
        }

        JLabel label = zoneInfoLabels.get(ev.getZoneId());
        if (label != null) label.setText("Fire out");
    }
}