package SubSystems;

import common.*;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.*;
import java.util.List;

public class GUI extends JFrame {

    private final JLabel firesLabel = new JLabel("Active fires: 0");
    private final JLabel dronesLabel = new JLabel("Drones busy: 0 / 0");
    private final JPanel dronePanel = new JPanel();
    private final Map<Integer, JLabel> droneState = new HashMap<>();
    private final Map<Integer, JLabel> dronePos = new HashMap<>();
    private final Map<Integer, JLabel> droneAgent = new HashMap<>();
    private final Map <Integer, JLabel> droneAssignedZone = new HashMap<>();
    private final Map <Integer, JLabel> droneFaultType = new HashMap<>();
    private final Map<Integer, List<FireEvent>> zones = new HashMap<>();
    private final Map<Integer, DroneStatus> drones = new HashMap<>();
    private final Map<Integer, JPanel> zonePanels = new HashMap<>();
    private final Map<Integer, JLabel> zoneInfoLabels = new HashMap<>();
    private final Map<Integer, FaultType> droneFault = new HashMap<>();


    public GUI(SystemCounts counts) {
        setTitle("Automated Drone Fire Response System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1600, 1000);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Top counter bar
        JPanel top = new JPanel(new GridLayout(1, 2, 10, 0));
        firesLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        dronesLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        top.add(firesLabel);
        top.add(dronesLabel);

        // 2x2 grid of zones
        JPanel grid = new JPanel(new GridLayout(2, 2, 10, 10));
        grid.add(makeZoneCell("Z(1)", 1));
        grid.add(makeZoneCell("Z(2)", 2));
        grid.add(makeZoneCell("Z(3)", 3));
        grid.add(makeZoneCell("Z(4)", 4));

        // Sidebar
        dronePanel.setLayout(new BoxLayout(dronePanel, BoxLayout.Y_AXIS));
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(575, 0));
        sidebar.setBorder(BorderFactory.createTitledBorder("Drone Status"));
        // header
        JPanel header = new JPanel(new GridLayout(1, 6, 0, 0));
        header.add(boldLabel("Drone"));
        header.add(boldLabel("State"));
        header.add(boldLabel("Position"));
        header.add(boldLabel("Agent"));
        header.add(boldLabel("Assigned Zone"));
        header.add(boldLabel("Faults"));
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


        // Poll counts periodically (simple + safe; does not intercept messages)
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
                FaultType lastFault = droneFault.getOrDefault(st.get_drone_id(), FaultType.NONE);
                updateDroneRow(st, FaultType.NONE);

                long busy = drones.values().stream()
                        .filter(d -> d.getState() != common.DroneState.IDLE)
                        .count();

                dronesLabel.setText("Drones busy: " + busy + " / " + drones.size());
                showDroneInZone(st);

            }
            case DRONE_TASK -> {
                //appendToConsole("DRONE_TASK: " + msg.getPayload());
            }
            case DRONE_DONE -> {
                DroneStatus st = (DroneStatus) msg.getPayload();
                drones.put(st.get_drone_id(), st);
                FaultType lastFault = droneFault.getOrDefault(st.get_drone_id(), FaultType.NONE);
                updateDroneRow(st, FaultType.NONE);
            }
            case FIRE_EVENT -> {
                if(!(msg.getPayload() instanceof FireEvent ev)){
                    break;
                }
                zones.computeIfAbsent(ev.getZoneId(), k -> new ArrayList<>()).add(ev);
                updateZoneColour(ev);
                firesLabel.setText("Active fires: " + zones.values().stream().mapToInt(List::size).sum());
            }
            case FIRE_OUT -> {
                FireEvent ev = (FireEvent) msg.getPayload();
                List<FireEvent> zoneEvents = zones.get(ev.getZoneId());

                if (zoneEvents != null){
                    zoneEvents.remove(0);
                    if (zoneEvents.isEmpty()){
                        zones.remove(ev.getZoneId());
                        markZoneExtinguished(ev);
                    }
                    else {
                        updateZoneColour(ev);
                    }
                }
                firesLabel.setText("Active fires: " + zones.size());
            }

            case DRONE_FAULT -> {
                DroneFault fault = (DroneFault) msg.getPayload();
                System.out.println("[GUI] DRONE_FAULT: " + fault);
                droneFault.put(fault.getDroneId(), fault.getFaultType());
                DroneStatus st = drones.get(fault.getDroneId());

                FaultType type = fault.getFaultType();
                updateDroneRow(st, type);

                Integer zoneId = st.get_zone_id();
                if (zoneId != null) {
                    List<FireEvent> fires = zones.get(zoneId);
                    if (fires != null && !fires.isEmpty()) {
                        updateZoneColour(fires.get(0)); // re-color zone based on current fires
                    }
                }
            }
        }
        repaint();
    }

    private void showDroneInZone(DroneStatus st){
        for(Map.Entry<Integer, JLabel> entry : zoneInfoLabels.entrySet()){
            String text = entry.getValue().getText();
            if(text.contains("Drone " + st.get_drone_id())){
                entry.getValue().setText("");
            }
        }

        Integer zoneId = st.get_zone_id();
        if (zoneId == null) return;

        switch (st.getState()){
            case DROPPING -> {
                JLabel label = zoneInfoLabels.get(zoneId);
                if (label != null){
                    label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                    label.setText("DRONE " + st.get_drone_id());
                }
            }
            case RETURNING, OFFLINE -> {
                JLabel label = zoneInfoLabels.get(zoneId);
                if(label != null){
                    label.setText("");
                }
            }
        }
    }

    private void updateDroneRow(DroneStatus st, FaultType ft) {
        int id = st.get_drone_id();

        // create row if first time seeing this drone
        if (!droneState.containsKey(id)) {
            JPanel row = new JPanel(new GridLayout(1, 4, 5, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
            row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel idLabel = new JLabel("Drone " + id);
            JLabel stateLabel = new JLabel("IDLE");
            JLabel posLabel = new JLabel("(0, 0)");
            JLabel agentLabel = new JLabel("100L");
            JLabel assignedZone = new JLabel("N/A");
            JLabel faultLabel = new JLabel("N/A");

            idLabel.setHorizontalAlignment(SwingConstants.LEFT);
            stateLabel.setHorizontalAlignment(SwingConstants.LEFT);
            posLabel.setHorizontalAlignment(SwingConstants.LEFT);
            agentLabel.setHorizontalAlignment(SwingConstants.LEFT);
            assignedZone.setHorizontalAlignment(SwingConstants.LEFT);
            faultLabel.setHorizontalAlignment(SwingConstants.LEFT);

            droneState.put(id, stateLabel);
            dronePos.put(id, posLabel);
            droneAgent.put(id, agentLabel);
            droneAssignedZone.put(id, assignedZone);
            droneFaultType.put(id, faultLabel);

            row.add(idLabel);
            row.add(stateLabel);
            row.add(posLabel);
            row.add(agentLabel);
            row.add(assignedZone);
            row.add(faultLabel);

            dronePanel.add(row);
            dronePanel.revalidate();
        }

        // update existing row
        droneState.get(id).setText(st.getState().toString());
        dronePos.get(id).setText(
                "(" + String.format("%.0f", st.getPosX())
                        + ", " + String.format("%.0f", st.getPosY()) + ")"
        );
        droneAgent.get(id).setText(
                st.get_remaining_agent() != null ? st.get_remaining_agent() + "L" : "?"
        );
        Integer zoneId = st.get_zone_id();
        droneAssignedZone.get(id).setText(zoneId != null ? zoneId.toString() : "N/A");

        droneFaultType.get(id).setText(ft.toString());

        // color the state label by what the drone is doing
        JLabel stateLabel = droneState.get(id);
        switch (st.getState()) {
            case IDLE -> stateLabel.setForeground(Color.GRAY);
            case EN_ROUTE -> stateLabel.setForeground(Color.BLUE);
            case DROPPING -> stateLabel.setForeground(Color.ORANGE);
            case RETURNING -> stateLabel.setForeground(new Color(0, 150, 0));
            default -> stateLabel.setForeground(Color.BLACK);
        }
        JLabel faultLabel = droneFaultType.get(id);
        switch (ft){
            case NONE -> faultLabel.setForeground(Color.GRAY);
            case DRONE_STUCK -> faultLabel.setForeground(Color.ORANGE);
            case NOZZLE_JAM -> faultLabel.setForeground(Color.RED);
            case PACKET_LOSS -> faultLabel.setForeground(Color.YELLOW);
        }
    }

    private void updateZoneColour(FireEvent ev) {
        JPanel p = zonePanels.get(ev.getZoneId());
        if (p == null) return;

        List<FireEvent> fires = zones.get(ev.getZoneId());
        if (fires == null || fires.isEmpty()) return;
        Severity worst = fires.stream().map(FireEvent::getSeverity).max(Comparator.comparingInt(Severity::ordinal)).orElse(Severity.LOW);

        switch (worst) {
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