package com.vstream.Vidiserver;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Draws profile pictures (no outside service): a round avatar with initials, and a larger illustrated
 * head-and-shoulders portrait on a background in the person's flag colours. The same person always gets the
 * same picture; it changes only when their name or country changes.
 */
public final class ProfileArt {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final int[] SKIN = {0xF6D7C3, 0xEBC2A4, 0xD8A07A, 0xB97A57, 0x8D5A3B, 0x6B4226};
    private static final int[] HAIR = {0x1B1B1B, 0x3B2416, 0x6A4E2E, 0xA8743F, 0xD9B46A, 0x7A1F1F};
    private static final int[] SHIRT = {0x1E88E5, 0xE53935, 0x43A047, 0x8E24AA, 0xFB8C00, 0x00897B, 0x3949AB,
        0xD81B60, 0x546E7A};

    private static final int CACHE_MAX = 300;
    private static final Map<String, byte[]> CACHE = new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > CACHE_MAX;
        }
    };

    private ProfileArt() {
    }

    static String initials(String first, String last, String username) {
        String a = first == null ? "" : first.trim();
        String b = last == null ? "" : last.trim();
        String s;
        if (!a.isEmpty() && !b.isEmpty()) {
            s = a.substring(0, 1) + b.substring(0, 1);
        } else if (!a.isEmpty()) {
            s = a.substring(0, Math.min(2, a.length()));
        } else {
            String u = username == null ? "?" : username.replace("_", "");
            s = u.isEmpty() ? "?" : u.substring(0, Math.min(2, u.length()));
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /** PNG bytes of the round avatar. */
    public static byte[] avatar(String username, String first, String last, long seed) {
        String key = "a|" + username + "|" + first + "|" + last + "|" + seed;
        synchronized (CACHE) {
            byte[] b = CACHE.get(key);
            if (b != null) {
                return b;
            }
        }
        int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        smooth(g);
        Random r = new Random(seed);
        float hue = r.nextFloat();
        Color c1 = Color.getHSBColor(hue, 0.55f, 0.85f);
        Color c2 = Color.getHSBColor((hue + 0.12f) % 1f, 0.65f, 0.6f);
        g.setPaint(new GradientPaint(0, 0, c1, size, size, c2));
        g.fill(new Ellipse2D.Float(0, 0, size, size));
        g.setColor(Color.WHITE);
        String text = initials(first, last, username);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 104));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, (size - w) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return remember(key, img);
    }

    /** PNG bytes of the larger illustrated portrait. */
    public static byte[] picture(String username, String first, String last, String country, long seed) {
        String key = "p|" + username + "|" + first + "|" + last + "|" + country + "|" + seed;
        synchronized (CACHE) {
            byte[] b = CACHE.get(key);
            if (b != null) {
                return b;
            }
        }
        int size = 512;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        smooth(g);
        Random r = new Random(seed ^ 0x5DEECE66DL);
        int[] flag = PeopleGenerator.colors(country);
        if (flag == null) {
            float hue = r.nextFloat();
            flag = new int[]{Color.HSBtoRGB(hue, 0.35f, 0.95f), 0xFFFFFF, Color.HSBtoRGB((hue + 0.5f) % 1f, 0.4f, 0.8f)};
        }
        // Background: soft vertical bands in the flag colours.
        int band = size / flag.length;
        for (int i = 0; i < flag.length; i++) {
            g.setColor(soften(new Color(flag[i]), 0.55f));
            g.fillRect(0, i * band, size, i == flag.length - 1 ? size - i * band : band);
        }
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 90), 0, size, new Color(0, 0, 0, 60)));
        g.fillRect(0, 0, size, size);

        Color skin = new Color(SKIN[r.nextInt(SKIN.length)]);
        Color hair = new Color(HAIR[r.nextInt(HAIR.length)]);
        Color shirt = new Color(SHIRT[r.nextInt(SHIRT.length)]);
        boolean longHair = r.nextBoolean();

        // Shoulders and shirt.
        g.setColor(shirt);
        g.fill(new RoundRectangle2D.Float(86, 372, 340, 220, 170, 140));
        g.setColor(shirt.darker());
        g.setStroke(new BasicStroke(6f));
        g.drawArc(206, 356, 100, 60, 200, 140);
        // Neck.
        g.setColor(skin.darker());
        g.fillRect(226, 312, 60, 70);
        // Long hair behind the head.
        if (longHair) {
            g.setColor(hair);
            g.fill(new RoundRectangle2D.Float(150, 150, 212, 250, 120, 120));
        }
        // Head and ears.
        g.setColor(skin);
        g.fill(new Ellipse2D.Float(160, 140, 192, 218));
        g.fill(new Ellipse2D.Float(146, 236, 30, 46));
        g.fill(new Ellipse2D.Float(336, 236, 30, 46));
        // Hair on top.
        g.setColor(hair);
        g.fillArc(154, 124, 204, 150, 0, 180);
        g.fill(new Ellipse2D.Float(160, 168, 60, 60));
        g.fill(new Ellipse2D.Float(292, 168, 60, 60));
        // Eyes, brows and smile.
        g.setColor(new Color(0x2B2B2B));
        g.fill(new Ellipse2D.Float(206, 246, 18, 22));
        g.fill(new Ellipse2D.Float(288, 246, 18, 22));
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(198, 230, 228, 226);
        g.drawLine(284, 226, 314, 230);
        g.setColor(new Color(0x8E3B3B));
        g.drawArc(222, 282, 68, 34, 200, 140);

        // Initials badge in the corner.
        String text = initials(first, last, username);
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Float(size - 118, 18, 100, 56, 28, 28));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, size - 68 - fm.stringWidth(text) / 2, 18 + (56 - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return remember(key, img);
    }

    /** PNG bytes of a drawn "photo" for a test person's post: a sunset, a city skyline or a beach. */
    public static byte[] scene(long seed, String country, String caption) {
        int w = 800;
        int h = 500;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        smooth(g);
        Random r = new Random(seed);
        int kind = r.nextInt(3);
        float hue = r.nextInt(3) == 0 ? 0.04f + r.nextFloat() * 0.08f : 0.55f + r.nextFloat() * 0.2f;
        Color top = Color.getHSBColor(kind == 0 ? 0.6f + r.nextFloat() * 0.1f : hue, 0.5f, kind == 0 ? 0.35f : 0.95f);
        Color bottom = kind == 0 ? new Color(0xFF8A50) : Color.getHSBColor((hue + 0.08f) % 1f, 0.35f, 1f);
        g.setPaint(new GradientPaint(0, 0, top, 0, h * 0.7f, bottom));
        g.fillRect(0, 0, w, h);
        // Sun or moon.
        g.setColor(kind == 0 ? new Color(0xFFD54F) : new Color(255, 255, 255, 200));
        int sx = 120 + r.nextInt(w - 240);
        g.fill(new Ellipse2D.Float(sx, kind == 0 ? 220 : 60 + r.nextInt(60), 110, 110));
        if (kind == 1) {
            // City skyline.
            int x = 0;
            while (x < w) {
                int bw = 40 + r.nextInt(70);
                int bh = 120 + r.nextInt(220);
                g.setColor(new Color(30 + r.nextInt(40), 30 + r.nextInt(40), 60 + r.nextInt(50)));
                g.fillRect(x, h - bh, bw, bh);
                g.setColor(new Color(255, 236, 160));
                for (int wy = h - bh + 12; wy < h - 14; wy += 22) {
                    for (int wx = x + 8; wx < x + bw - 10; wx += 16) {
                        if (r.nextInt(3) > 0) {
                            g.fillRect(wx, wy, 7, 10);
                        }
                    }
                }
                x += bw + 4;
            }
        } else {
            // Hills (sunset) or sea and sand (beach).
            g.setColor(kind == 0 ? new Color(0x3E2723) : new Color(0x0277BD));
            g.fillRect(0, (int) (h * 0.68), w, h);
            if (kind == 0) {
                g.setColor(new Color(0x4E342E));
                for (int i = 0; i < 4; i++) {
                    int cx = r.nextInt(w);
                    g.fill(new Ellipse2D.Float(cx - 220, h * 0.55f, 440, 260));
                }
            } else {
                g.setColor(new Color(0xFFE0B2));
                g.fill(new Ellipse2D.Float(-100, h * 0.82f, w + 200, 300));
                g.setColor(new Color(255, 255, 255, 140));
                g.setStroke(new BasicStroke(4f));
                for (int i = 0; i < 6; i++) {
                    int y = (int) (h * 0.72) + i * 14;
                    g.drawLine(40 + r.nextInt(200), y, 300 + r.nextInt(400), y);
                }
            }
        }
        String text = caption == null ? "" : caption;
        if (!text.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Float(20, 20, tw + 32, 48, 24, 24));
            g.setColor(Color.WHITE);
            g.drawString(text, 36, 20 + (48 - fm.getHeight()) / 2 + fm.getAscent());
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static Color soften(Color c, float towardsWhite) {
        int r = (int) (c.getRed() + (255 - c.getRed()) * towardsWhite);
        int gr = (int) (c.getGreen() + (255 - c.getGreen()) * towardsWhite);
        int b = (int) (c.getBlue() + (255 - c.getBlue()) * towardsWhite);
        return new Color(r, gr, b);
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static byte[] remember(String key, BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] b = out.toByteArray();
        synchronized (CACHE) {
            CACHE.put(key, b);
        }
        return b;
    }
}