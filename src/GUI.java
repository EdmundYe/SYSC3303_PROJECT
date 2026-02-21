import common.SystemCounts;

import javax.swing.*;
import java.awt.*;

public class GUI extends JFrame {

    private final JLabel firesLabel = new JLabel("Active fires: 0");
    private final JLabel dronesLabel = new JLabel("Drones busy: 0 / 0");

    public GUI(SystemCounts counts) {
        setTitle("Simple Grid GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(520, 360);
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
        grid.add(makeZoneCell("Z(1)"));
        grid.add(makeZoneCell("Z(2)"));
        grid.add(makeZoneCell("Z(3)"));
        grid.add(makeZoneCell("Z(4)"));

        root.add(top, BorderLayout.NORTH);
        root.add(grid, BorderLayout.CENTER);
        setContentPane(root);

        // Poll counts periodically (simple + safe; does not intercept messages)
        Timer t = new Timer(200, e -> {
            firesLabel.setText("Active fires: " + counts.getActiveFires());
            dronesLabel.setText("Drones busy: " + counts.getBusyDrones() + " / " + counts.getTotalDrones());
        });
        t.start();
    }

    private static JPanel makeZoneCell(String label) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createLineBorder(new Color(150, 110, 200), 2));

        JLabel l = new JLabel(label);
        l.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));

        p.add(l, BorderLayout.NORTH);
        return p;
    }
}