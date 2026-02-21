import javax.swing.*;
import java.awt.*;

/**
 * This GUI only provides the structure/layout.
 * No subsystem logic is connected at this stage.
 */
public class GUI extends JFrame {

    private JTextArea fireArea;
    private JTextArea schedulerArea;
    private JTextArea droneArea;

    public GUI() {
        setTitle("Firefighting Drone System - Iteration 1");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        setLayout(new GridLayout(3, 1));

        fireArea = createPanel("Fire Incident Subsystem");
        schedulerArea = createPanel("Scheduler");
        droneArea = createPanel("Drone Subsystem");

        add(wrap(fireArea));
        add(wrap(schedulerArea));
        add(wrap(droneArea));
    }

    private JTextArea createPanel(String title) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setBorder(BorderFactory.createTitledBorder(title));
        return area;
    }

    private JScrollPane wrap(JTextArea area) {
        return new JScrollPane(area);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            GUI gui = new GUI();
            gui.setVisible(true);
        });
    }
}