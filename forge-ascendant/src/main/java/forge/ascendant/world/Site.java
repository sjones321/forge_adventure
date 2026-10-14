package forge.ascendant.world;

public final class Site {
    public final String kind;
    public final String name;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String tmxRelative;
    public final int skipRowsTop;
    public final boolean home;

    public Site(String kind, String name, int x, int y, int w, int h,
                String tmxRelative, int skipRowsTop, boolean home) {
        this.kind = kind;
        this.name = name;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.tmxRelative = tmxRelative;
        this.skipRowsTop = skipRowsTop;
        this.home = home;
    }

    public boolean containsTile(int tx, int ty) {
        return tx >= x && ty >= y && tx < x + w && ty < y + h;
    }
}
