import common.DroneStatus;
import common.FireEvent;
import common.Message;
import common.SystemCounts;

import javax.swing.*;
import java.awt.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class GUI extends JFrame {

    private final JLabel firesLabel = new JLabel("Active fires: 0");
    private final JLabel dronesLabel = new JLabel("Drones busy: 0 / 0");
    private final JTextArea consoleLabel = new JTextArea();

    private final Map<Integer, FireEvent> zones = new HashMap<>();
    private final Map<Integer, DroneStatus> drones = new HashMap<>();
    private final Map<Integer, JPanel> zonePanels = new HashMap<>();
    private final Map<Integer, JLabel> zoneInfoLabels = new HashMap<>();

    public GUI(SystemCounts counts) {
        setTitle("Automated Drone Fire Response System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 900);
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
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(250, 0));
        sidebar.setBorder(BorderFactory.createTitledBorder("System Monitor"));

        // Console area
        consoleLabel.setEditable(false);
        consoleLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(consoleLabel);
        scroll.setPreferredSize(new Dimension(250, 600));
        sidebar.add(scroll);

        root.add(top, BorderLayout.NORTH);
        root.add(grid, BorderLayout.CENTER);
        root.add(sidebar, BorderLayout.EAST);

        setContentPane(root);


        // Poll counts periodically (simple + safe; does not intercept messages)
        Timer t = new Timer(200, e -> {
            firesLabel.setText("Active fires: " + counts.getActiveFires());
            dronesLabel.setText("Drones busy: " + counts.getBusyDrones() + " / " + counts.getTotalDrones());
        });
        t.start();


        new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(8000)) {
                byte[] buf = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);

                while (true) {
                    sock.receive(packet);
                    Message msg = Message.fromBytes(
                            Arrays.copyOf(packet.getData(), packet.getLength())
                    );
                    handleIncomingMessage(msg);
                }
            } catch (Exception ignored) {}
        }).start();
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

                long busy = drones.values().stream()
                        .filter(d -> d.getState() != common.DroneState.IDLE)
                        .count();

                dronesLabel.setText("Drones busy: " + busy + " / " + drones.size());

                appendToConsole("DRONE_STATUS: " + st);
            }
            case DRONE_TASK -> {
                appendToConsole("DRONE_TASK: " + msg.getPayload());
            }
            case DRONE_DONE -> {
                DroneStatus st = (DroneStatus) msg.getPayload();
                drones.put(st.get_drone_id(), st);
                appendToConsole("DRONE_DONE: " + st);
            }
            case FIRE_EVENT -> {
                FireEvent ev = (FireEvent) msg.getPayload();
                zones.put(ev.getZoneId(), ev);
                updateZoneColor(ev);
                firesLabel.setText("Active fires: " + zones.size());
                appendToConsole("FIRE_EVENT: " + ev);
            }
            case FIRE_OUT -> {
                FireEvent ev = (FireEvent) msg.getPayload();
                zones.remove(ev.getZoneId());
                markZoneExtinguished(ev);
                firesLabel.setText("Active fires: " + zones.size());
                appendToConsole("FIRE_OUT: " + ev);
            }
        }
        repaint();
    }

    private void updateZoneColor(FireEvent ev) {
        JPanel p = zonePanels.get(ev.getZoneId());
        if (p == null) return;

        switch (ev.getSeverity()) {
            case HIGH -> p.setBackground(Color.RED);
            case MODERATE -> p.setBackground(Color.ORANGE);
            case LOW -> p.setBackground(Color.YELLOW);
        }
    }

    private void markZoneExtinguished(FireEvent ev) {
        JPanel p = zonePanels.get(ev.getZoneId());
        if (p != null) p.setBackground(Color.GREEN);

        JLabel label = zoneInfoLabels.get(ev.getZoneId());
        if (label != null) label.setText("");
    }

    private void appendToConsole(String text) {
        System.out.println("appendToConsole debug");
        consoleLabel.append(text + "\n");
    }
}