package forge.app;

import com.badlogic.gdx.Gdx;
import forge.ImageKeys;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.screens.match.MatchController;
import forge.screens.match.PlayableTracker;
import forge.screens.match.views.VPrompt;
import forge.toolbox.FButton;
import forge.util.ImageUtil;
import forge.util.ThreadUtil;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Pop-out window for a second monitor: the human player's hand in a grid at a readable size
 * (never stacked), cards castable right now glowing green like Arena, a side panel with a large
 * image and the full text of the hovered card, and the game's prompt with its two buttons.
 * Clicking a card selects it exactly as clicking it in the in-game hand would; mouse button 4
 * presses OK. The window remembers its position and size. Opened via reflection from DuelScene.
 */
public final class HandWindow {
    private static final double ASPECT = 3.5 / 2.5;
    private static final int SIDE_WIDTH = 440;
    private static final Color BG = new Color(24, 24, 28);
    private static final Color SIDE_BG = new Color(30, 30, 36);
    private static final Color GLOW = new Color(60, 230, 90);
    private static final Preferences PREFS = Preferences.userNodeForPackage(HandWindow.class);
    private static final Map<String, BufferedImage> IMAGES = new HashMap<>();
    private static final Map<String, Long> MISS_RETRY_AT = new HashMap<>();

    private static JFrame frame;
    private static HandPanel handPanel;
    private static PreviewPanel preview;
    private static JEditorPane infoText;
    private static JLabel promptLabel;
    private static JButton okButton, cancelButton;
    private static Timer timer;

    private HandWindow() {
    }

    public static void show() {
        SwingUtilities.invokeLater(() -> {
            if (frame == null)
                create();
            frame.setVisible(true);
            timer.start();
        });
    }

    public static void hide() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                timer.stop();
                frame.setVisible(false);
            }
        });
    }

    private static void create() {
        frame = new JFrame("Forge - Your Hand");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        handPanel = new HandPanel();
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(handPanel, BorderLayout.CENTER);
        root.add(createSidePanel(), BorderLayout.EAST);
        root.add(createPromptBar(), BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setBounds(PREFS.getInt("x", 100), PREFS.getInt("y", 100), PREFS.getInt("w", 1400), PREFS.getInt("h", 800));
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) { savePlacement(); }
            @Override
            public void componentResized(ComponentEvent e) { savePlacement(); }
        });
        // mouse button 4 (back side button) presses OK anywhere in this window
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED && me.getButton() == 4
                    && SwingUtilities.getWindowAncestor(me.getComponent()) == frame)
                pressPrompt(true);
        }, AWTEvent.MOUSE_EVENT_MASK);
        timer = new Timer(400, e -> {
            handPanel.refresh();
            refreshPrompt();
        });
    }

    private static void savePlacement() {
        Rectangle b = frame.getBounds();
        PREFS.putInt("x", b.x);
        PREFS.putInt("y", b.y);
        PREFS.putInt("w", b.width);
        PREFS.putInt("h", b.height);
    }

    // ------------------------------------------------------------------ side panel: big image + text

    private static JPanel createSidePanel() {
        preview = new PreviewPanel();
        infoText = new JEditorPane("text/html", "");
        infoText.setEditable(false);
        infoText.setOpaque(true);
        infoText.setBackground(SIDE_BG);
        infoText.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        infoText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        infoText.setForeground(Color.WHITE);
        infoText.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        infoText.setText("<html><i>Hover a card to read it.</i></html>");
        JScrollPane scroll = new JScrollPane(infoText, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(SIDE_BG);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(SIDE_BG);
        side.setPreferredSize(new Dimension(SIDE_WIDTH, 400));
        side.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(70, 70, 80)));
        side.add(preview, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    /** Large image of the hovered card, as wide as the side panel. */
    private static final class PreviewPanel extends JPanel {
        private CardView card;

        PreviewPanel() {
            setBackground(SIDE_BG);
            setPreferredSize(new Dimension(SIDE_WIDTH, (int) ((SIDE_WIDTH - 24) * ASPECT) + 24));
        }

        void setCard(CardView cv) {
            card = cv;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (card == null)
                return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth() - 24, h = (int) (w * ASPECT);
            if (h > getHeight() - 24) {
                h = getHeight() - 24;
                w = (int) (h / ASPECT);
            }
            BufferedImage img = imageFor(card);
            int x = (getWidth() - w) / 2;
            if (img != null)
                g.drawImage(img, x, 12, w, h, null);
            else
                drawPlaceholder(g, card, new Rectangle(x, 12, w, h));
        }
    }

    private static void showInfo(CardView cv) {
        preview.setCard(cv);
        try {
            CardView.CardStateView s = cv.getCurrentState();
            StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif; font-size:16pt; color:white'>");
            sb.append("<b style='font-size:19pt'>").append(esc(s.getName())).append("</b>");
            if (s.getManaCost() != null && !s.getManaCost().isNoCost())
                sb.append("&nbsp;&nbsp;<span style='color:#ffd36b'>").append(esc(s.getManaCost().toString())).append("</span>");
            sb.append("<br><span style='color:#b8c4ff'>").append(esc(String.valueOf(s.getType()))).append("</span>");
            if (s.isCreature())
                sb.append("<br><b>").append(s.getPower()).append(" / ").append(s.getToughness()).append("</b>");
            String text = s.getOracleText();
            if (text == null || text.isBlank())
                text = cv.getText();
            if (text != null && !text.isBlank())
                sb.append("<p>").append(esc(text).replace("\\n", "<br>").replace("\n", "<br>")).append("</p>");
            sb.append("</body></html>");
            infoText.setText(sb.toString());
            infoText.setCaretPosition(0);
        } catch (Exception ignored) {
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ prompt bar

    private static JPanel createPromptBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(new Color(36, 36, 44));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        promptLabel = new JLabel(" ");
        promptLabel.setForeground(Color.WHITE);
        promptLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        promptLabel.setVerticalAlignment(JLabel.TOP);
        okButton = new JButton("OK");
        cancelButton = new JButton("Cancel");
        for (JButton b : new JButton[]{okButton, cancelButton}) {
            b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            b.setPreferredSize(new Dimension(150, 44));
            b.setFocusable(false);
        }
        okButton.addActionListener(e -> pressPrompt(true));
        cancelButton.addActionListener(e -> pressPrompt(false));
        // FlowLayout keeps the buttons at their normal size even when the message is tall
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(okButton);
        buttons.add(cancelButton);
        bar.add(promptLabel, BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    private static VPrompt activePrompt() {
        try {
            return MatchController.getView() == null ? null : MatchController.getView().getActivePrompt();
        } catch (Exception e) {
            return null;
        }
    }

    private static void refreshPrompt() {
        VPrompt p = activePrompt();
        if (p == null) {
            promptLabel.setText(" ");
            okButton.setVisible(false);
            cancelButton.setVisible(false);
            return;
        }
        String msg = p.getMessage() == null ? "" : p.getMessage();
        String html = "<html>" + esc(msg).replace("\n", "<br>") + "</html>";
        if (!html.equals(promptLabel.getText()))
            promptLabel.setText(html);
        mirror(okButton, p.getBtnOk());
        mirror(cancelButton, p.getBtnCancel());
    }

    private static void mirror(JButton target, FButton source) {
        String text = source.getText();
        boolean show = source.isVisible() && text != null && !text.isEmpty();
        target.setVisible(show);
        if (show && !text.equals(target.getText()))
            target.setText(text);
        target.setEnabled(source.isEnabled());
    }

    /** Press the real prompt button on the game's UI thread, so its double-click guard and state apply. */
    private static void pressPrompt(boolean ok) {
        VPrompt p = activePrompt();
        if (p == null)
            return;
        Gdx.app.postRunnable(() -> (ok ? p.getBtnOk() : p.getBtnCancel()).trigger());
    }

    // ------------------------------------------------------------------ hand grid

    private static final class HandPanel extends JPanel {
        private List<CardView> hand = new ArrayList<>();
        private Set<Integer> playable = new HashSet<>();
        private final List<Rectangle> slots = new ArrayList<>();
        private int hovered = -1;

        HandPanel() {
            setBackground(BG);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int h = slotAt(e.getX(), e.getY());
                    if (h != hovered) {
                        hovered = h;
                        if (h >= 0 && h < hand.size())
                            showInfo(hand.get(h));
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = -1;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1)
                        return;
                    int i = slotAt(e.getX(), e.getY());
                    if (i < 0 || i >= hand.size())
                        return;
                    CardView cv = hand.get(i);
                    ThreadUtil.invokeInGameThread(() -> {
                        try {
                            MatchController.instance.getGameController().selectCard(cv, null, null);
                        } catch (Exception ignored) {
                        }
                    });
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int slotAt(int x, int y) {
            for (int i = slots.size() - 1; i >= 0; i--)
                if (slots.get(i).contains(x, y))
                    return i;
            return -1;
        }

        void refresh() {
            List<CardView> newHand = new ArrayList<>();
            Set<Integer> newPlayable = new HashSet<>();
            try {
                PlayerView me = MatchController.instance.getCurrentPlayer();
                if (me != null && me.getHand() != null)
                    newHand.addAll(me.getHand());
                // same "castable now" answer as the main window's hand glow
                PlayableTracker.refreshIfStale();
                newPlayable.addAll(PlayableTracker.playableIds());
            } catch (Exception ignored) {
                // game state changing under us; try again next tick
            }
            hand = newHand;
            playable = newPlayable;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            slots.clear();
            int n = hand.size();
            if (n == 0) {
                g.setColor(Color.GRAY);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
                g.drawString("No cards in hand (or no game running).", 20, 40);
                return;
            }
            // Pick the number of rows that gives the biggest cards for this window size.
            int pad = 14, bestRows = 1, bestW = 0;
            for (int rows = 1; rows <= Math.min(n, 4); rows++) {
                int cols = (n + rows - 1) / rows;
                int byWidth = (getWidth() - pad * (cols + 1)) / cols;
                int byHeight = (int) (((getHeight() - pad * (rows + 1)) / (double) rows) / ASPECT);
                int w = Math.min(byWidth, byHeight);
                if (w > bestW) {
                    bestW = w;
                    bestRows = rows;
                }
            }
            int cols = (n + bestRows - 1) / bestRows;
            int cardW = Math.max(40, bestW), cardH = (int) (cardW * ASPECT);
            int gridH = bestRows * cardH + (bestRows - 1) * pad;
            int top = Math.max(pad, (getHeight() - gridH) / 2);
            for (int i = 0; i < n; i++) {
                int row = i / cols, col = i % cols;
                int inRow = Math.min(cols, n - row * cols);
                int rowW = inRow * cardW + (inRow - 1) * pad;
                int left = (getWidth() - rowW) / 2;
                Rectangle r = new Rectangle(left + col * (cardW + pad), top + row * (cardH + pad), cardW, cardH);
                slots.add(r);
                CardView cv = hand.get(i);
                if (playable.contains(cv.getId())) {
                    g.setColor(GLOW);
                    g.setStroke(new BasicStroke(6f));
                    g.drawRoundRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8, 18, 18);
                }
                BufferedImage img = imageFor(cv);
                if (img != null)
                    g.drawImage(img, r.x, r.y, r.width, r.height, null);
                else
                    drawPlaceholder(g, cv, r);
                if (i == hovered) {
                    g.setColor(new Color(255, 255, 255, 50));
                    g.fillRect(r.x, r.y, r.width, r.height);
                }
            }
        }
    }

    // ------------------------------------------------------------------ images

    private static void drawPlaceholder(Graphics2D g, CardView cv, Rectangle r) {
        g.setColor(new Color(60, 60, 70));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, r.width / 12)));
        g.drawString(cv.getName(), r.x + 8, r.y + 24);
    }

    private static BufferedImage imageFor(CardView cv) {
        try {
            String key = cv.getCurrentState().getImageKey();
            if (key == null)
                return null;
            if (IMAGES.containsKey(key))
                return IMAGES.get(key);
            Long retry = MISS_RETRY_AT.get(key);
            if (retry != null && System.currentTimeMillis() < retry)
                return null; // the game may still be downloading it; look again shortly
            File f = findImageFile(key, cv);
            BufferedImage img = f != null ? ImageIO.read(f) : null;
            if (img != null)
                IMAGES.put(key, img);
            else
                MISS_RETRY_AT.put(key, System.currentTimeMillis() + 5000);
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /** Finds the cached picture the main window uses: exact key first, then by set folder and name. */
    private static File findImageFile(String key, CardView cv) {
        File f = ImageKeys.getImageFile(key);
        if (f != null && f.isFile())
            return f;
        String base = ForgeConstants.CACHE_CARD_PICS_DIR;
        try {
            PaperCard pc = ImageUtil.getPaperCardFromImageKey(key);
            if (pc != null) {
                String rel = ImageUtil.getImageRelativePath(pc, "", true, false);
                for (String ext : new String[]{".fullborder.jpg", ".full.jpg", ".jpg", ".png"}) {
                    File c = new File(base, rel + ext);
                    if (c.isFile())
                        return c;
                }
            }
        } catch (Exception ignored) {
        }
        String set = cv.getCurrentState().getSetCode();
        String name = cv.getCurrentState().getName();
        if (set == null || name == null)
            return null;
        File[] files = new File(base, set).listFiles((dir, n) -> n.startsWith(name) && (n.endsWith(".jpg") || n.endsWith(".png"))
                && n.substring(name.length()).matches("\\d*\\..*"));
        return files == null || files.length == 0 ? null : files[0];
    }
}
