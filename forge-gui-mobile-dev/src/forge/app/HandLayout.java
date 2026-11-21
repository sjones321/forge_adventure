package forge.app;

/**
 * Pure layout/math for {@link HandPrototype}. No libGDX, no Forge game state.
 * All sizes are fractions of window height H unless noted.
 */
public final class HandLayout {
    public static final int CARD_COUNT = 7;
    public static final float CARD_ASPECT = 63f / 88f;
    public static final float FULL_HEIGHT_FRAC = 0.28f;
    /**
     * Resting cards are a little larger than a full scan so the name and mana cost
     * read at a glance. The extra height hangs off the bottom of the screen.
     */
    public static final float REST_SCALE = 1.12f;
    /** Top of the centre resting card. The rest of that card is below the screen edge. */
    public static final float REST_TOP_FRAC = 0.14f;
    public static final float VISIBLE_STRIP_FRAC = 0.45f;
    public static final float MAX_HAND_WIDTH_FRAC = 0.55f;
    public static final float ROTATION_MAX_DEG = 12f;
    public static final float HOVER_BOTTOM_FRAC = 0.02f;
    /**
     * Kept for callers that still ask how tall the old opened row was. Hover no longer
     * grows the neighbours; only the card under the cursor grows in its fan slot.
     */
    public static final float HOVER_OPEN_HEIGHT_FRAC = 0.42f;
    /** The one card under the cursor, scaled up in its slot. Still short of the 0.75H zoom. */
    public static final float HOVER_RAISED_HEIGHT_FRAC = 0.48f;
    /** Prompt and mana plates sit here, above the hand. Not the resting fan. */
    public static final float HOVER_HAND_TOP_FRAC = 0.55f;
    /** Top fraction of a card treated as the title band. */
    public static final float TITLE_BAND_FRAC = 0.16f;
    /**
     * Fraction of each card's width that should stay visible across the title band.
     * The row overlaps below that band when seven cards would not fit side by side.
     */
    public static final float TITLE_VISIBLE_FRAC = 0.55f;
    /** Opened hand keeps a fan so the lower part can overlap while the titles stay apart. */
    public static final float HOVER_ROTATION_MAX_DEG = 12f;
    /** Gap used when the opened cards fit side by side, as a fraction of an opened card width. */
    public static final float HOVER_GAP_FRAC = 0.04f;
    public static final float LEAVE_HAND_ABOVE_FRAC = 0.05f;
    /**
     * Extra hit margin around each card when deciding whether to open or keep the hand open,
     * as a fraction of window height. ~0.02H so empty space above/beside the fan does not open it.
     */
    public static final float OPEN_HIT_PAD_FRAC = 0.02f;
    public static final float EASE_RATE = 14f;
    /**
     * Extra height above the visible hand top. The gripped card casts once its centre
     * clears that band. It does not have to reach the opponent's half.
     */
    public static final float CAST_CLEARANCE_FRAC = 0.04f;
    public static final float DRAG_TILT_MAX_DEG = 8f;
    public static final float CAST_FLY_SEC = 0.15f;
    public static final float CAST_FADE_SEC = 0.25f;
    public static final float CAST_END_SCALE = 0.40f;
    public static final float CANCEL_SNAP_SEC = 0.20f;
    public static final float DRAG_THRESHOLD_PX = 12f;
    public static final float TAP_MAX_SEC = 0.25f;
    public static final float ZOOM_HEIGHT_FRAC = 0.75f;

    private HandLayout() {
    }

    public static float fullHeight(float h) {
        return FULL_HEIGHT_FRAC * h;
    }

    public static float fullWidth(float h) {
        return fullHeight(h) * CARD_ASPECT;
    }

    public static float restHeight(float h) {
        return fullHeight(h) * REST_SCALE;
    }

    public static float restWidth(float h) {
        return fullWidth(h) * REST_SCALE;
    }

    /** Just above the resting titles. A short drag out of the fan crosses it. */
    public static float castLineY(float h) {
        return (REST_TOP_FRAC + CAST_CLEARANCE_FRAC) * h;
    }

    /** Exponential approach used for all motion. */
    public static float easeToward(float value, float target, float deltaSec) {
        if (deltaSec <= 0f) {
            return value;
        }
        float t = 1f - (float) Math.exp(-deltaSec * EASE_RATE);
        return value + (target - value) * t;
    }

    public static boolean isTap(float movePx, float holdSec) {
        return movePx < DRAG_THRESHOLD_PX && holdSec < TAP_MAX_SEC;
    }

    public static boolean isDrag(float movePx) {
        return movePx >= DRAG_THRESHOLD_PX;
    }

    /**
     * Horizontal step between resting card centres. Increases overlap when the
     * ideal fan would exceed {@link #MAX_HAND_WIDTH_FRAC} of window width.
     */
    public static float restStep(float windowW, float h, int n) {
        if (n <= 1) {
            return 0f;
        }
        float restW = restWidth(h);
        float ideal = VISIBLE_STRIP_FRAC * restW;
        float maxHand = MAX_HAND_WIDTH_FRAC * windowW;
        float idealHand = (n - 1) * ideal + restW;
        if (idealHand <= maxHand) {
            return ideal;
        }
        return Math.max(1f, (maxHand - restW) / (n - 1));
    }

    public static float handWidth(float windowW, float h, int n) {
        if (n <= 0) {
            return 0f;
        }
        return (n - 1) * restStep(windowW, h, n) + restWidth(h);
    }

    /** Fan angle (degrees from vertical, negative = leaning left) for card {@code i} of {@code n}. */
    static float fanAngleDeg(int n, int i, float maxDeg) {
        float t = n <= 1 ? 0.5f : i / (float) (n - 1);
        return -maxDeg + 2f * maxDeg * t;
    }

    /**
     * Resting pose for card {@code i} of {@code n}. Origin is card centre. Y is from bottom.
     * HP1: held like a real hand. Every card rotates about one pivot below the hand, so the
     * card bottoms are pinched together and the tops fan outward along an arc (left cards lean
     * left, right cards lean right). The arc radius is chosen so the centre-to-centre spacing
     * matches {@link #restStep}, keeping the 45% visible strip and 55% width cap.
     */
    public static Pose restPose(float windowW, float h, int n, int i) {
        float restW = restWidth(h);
        float restH = restHeight(h);
        float step = restStep(windowW, h, n);
        float handCx = windowW / 2f;
        float theta = fanAngleDeg(n, i, ROTATION_MAX_DEG);
        float stepRad = n <= 1 ? 0f : (float) Math.toRadians(2f * ROTATION_MAX_DEG / (n - 1));
        float radius = stepRad <= 0f ? 0f : step / stepRad;
        // Centre card's top stays at REST_TOP_FRAC * H; the pivot sits radius below its centre.
        float pivotY = REST_TOP_FRAC * h - restH / 2f - radius;
        double rad = Math.toRadians(theta);
        float cx = handCx + radius * (float) Math.sin(rad);
        float cy = pivotY + radius * (float) Math.cos(rad);
        // libGDX rotation is counter-clockwise; a card leaning left (theta < 0) turns CCW.
        return new Pose(cx, cy, restW, restH, -theta, REST_SCALE);
    }

    public static float hoverOpenHeight(float h) {
        return HOVER_OPEN_HEIGHT_FRAC * h;
    }

    public static float hoverOpenWidth(float h) {
        return hoverOpenHeight(h) * CARD_ASPECT;
    }

    public static float hoverRaisedHeight(float h) {
        return HOVER_RAISED_HEIGHT_FRAC * h;
    }

    public static float hoverRaisedWidth(float h) {
        return hoverRaisedHeight(h) * CARD_ASPECT;
    }

    /**
     * How many times taller the opened hand is than the tucked resting hand.
     * Card size does not shrink to fit a narrow window; the lower part overlaps instead.
     */
    public static float hoverHandScale(float windowW, float h, int n) {
        // Width and count do not shrink the cards. A tight window overlaps the lower part instead.
        if (n < 0 || windowW < 0f) {
            return 1f;
        }
        float restH = restHeight(h);
        if (restH <= 0f) {
            return 1f;
        }
        return hoverOpenHeight(h) / restH;
    }

    /**
     * Centre-to-centre step of the opened hand. Prefers a step that leaves
     * {@link #TITLE_VISIBLE_FRAC} of each card's width clear at the title, and tightens
     * (overlapping the lower part) only so the fan still fits in the window.
     */
    public static float hoverStep(float windowW, float h, int n) {
        if (n <= 1) {
            return 0f;
        }
        float cw = hoverOpenWidth(h);
        float ch = hoverOpenHeight(h);
        // Extra half-width of the raised card eats into the neighbour title, so the step
        // has to give that back or the title band slides under the card in front.
        // A card between two others is covered from both sides, so the step has to leave
        // the title band clear of both neighbours: 2*step - width >= TITLE_VISIBLE_FRAC * width.
        float titleStep = cw * (1f + TITLE_VISIBLE_FRAC) / 2f + (hoverRaisedWidth(h) - cw) / 2f;
        float sideBySide = cw * (1f + HOVER_GAP_FRAC);
        float ideal = Math.min(sideBySide, Math.max(cw * 0.35f, titleStep));
        float overhang = ch * (float) Math.sin(Math.toRadians(HOVER_ROTATION_MAX_DEG));
        float fitStep = (windowW - cw - overhang) / (n - 1);
        if ((n - 1) * ideal + cw + overhang <= windowW) {
            return ideal;
        }
        return Math.max(1f, fitStep);
    }

    /** Axis-aligned width of the opened hand, including the raised card. */
    public static float hoverHandWidth(float windowW, float h, int n, int hoverIndex) {
        if (n <= 0 || hoverIndex < 0 || hoverIndex >= n) {
            return 0f;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Pose p = hoverPose(windowW, h, n, i, hoverIndex);
            double r = Math.toRadians(p.rotationDeg);
            float half = (float) (p.w * Math.abs(Math.cos(r)) + p.h * Math.abs(Math.sin(r))) / 2f;
            min = Math.min(min, p.cx - half);
            max = Math.max(max, p.cx + half);
        }
        return max - min;
    }

    /**
     * Visual pose of card {@code i} while {@code hoverIndex} is open. Neighbours stay on
     * {@link #restPose}. The hovered card flies up so the whole face reads, including
     * the text box and P/T. Hit tests stay on {@link #restPose}; this picture is not a target.
     */
    public static Pose hoverPose(float windowW, float h, int n, int i, int hoverIndex) {
        if (hoverIndex < 0 || hoverIndex >= n || i != hoverIndex) {
            return restPose(windowW, h, n, i);
        }
        return grownInSlot(windowW, h, n, i);
    }

    /**
     * Fly the hovered card up out of its fan slot. Same column as the rest pose.
     * The bottom of the face — rules text and the P/T box — sits on
     * {@link #HOVER_BOTTOM_FRAC} so the whole 63/88 scan is on-screen with a little
     * air. Upright at {@link #HOVER_RAISED_HEIGHT_FRAC}. Neighbours stay on
     * {@link #restPose}.
     */
    public static Pose grownInSlot(float windowW, float h, int n, int i) {
        Pose rest = restPose(windowW, h, n, i);
        float rh = hoverRaisedHeight(h);
        float rw = hoverRaisedWidth(h);
        float air = HOVER_BOTTOM_FRAC * h;
        float cy = air + rh / 2f;
        if (cy + rh / 2f > h - air) {
            cy = h - air - rh / 2f;
        }
        float half = rw / 2f;
        float cx = Math.max(half + 8f, Math.min(windowW - half - 8f, rest.cx));
        return new Pose(cx, cy, rw, rh, 0f, rh / fullHeight(h));
    }

    /** True when the resting centres are in the upper half, so the preview hangs downward. */
    public static boolean fanSitsOnTopEdge(float windowW, float h, int n) {
        if (n <= 0 || h <= 0f) {
            return false;
        }
        float cy = 0f;
        for (int i = 0; i < n; i++) {
            cy += restPose(windowW, h, n, i).cy;
        }
        return cy / n >= h * 0.5f;
    }

    /** Highest (or lowest) corner of the resting fan. */
    private static float fanExtremeY(float windowW, float h, int n, boolean lowest) {
        float edge = lowest ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float y = lowest ? axisBounds(restPose(windowW, h, n, i))[2]
                    : axisBounds(restPose(windowW, h, n, i))[3];
            edge = lowest ? Math.min(edge, y) : Math.max(edge, y);
        }
        return edge;
    }

    /**
     * One enlarged card beside a resting fan. {@code growDown} hangs the picture
     * below {@code fanEdge}; otherwise it sits above {@code fanEdge}. The gap is
     * {@link #OPEN_HIT_PAD_FRAC} so the picture stays off the neighbour hit boxes.
     * The whole card is kept on screen when the window is tall enough.
     */
    public static Pose previewBesideFan(float windowH, float anchorX, float fanEdge, boolean growDown) {
        float rh = hoverRaisedHeight(windowH);
        float rw = hoverRaisedWidth(windowH);
        float pad = OPEN_HIT_PAD_FRAC * windowH;
        float cy;
        if (growDown) {
            float top = fanEdge - pad;
            cy = top - rh / 2f;
            if (cy - rh / 2f < 0f) {
                cy = rh / 2f;
            }
        } else {
            float bottom = fanEdge + pad;
            cy = bottom + rh / 2f;
            if (cy + rh / 2f > windowH) {
                cy = windowH - rh / 2f;
            }
        }
        return new Pose(anchorX, cy, rw, rh, 0f, rh / fullHeight(windowH));
    }

    /** Nearest card by centre column in the current layout (resting, or spread around {@code hoverIndex}). */
    public static int nearestCard(float windowW, float h, int n, float pointerX, int hoverIndex) {
        if (n <= 0) {
            return -1;
        }
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Pose p = hoverIndex >= 0 ? hoverPose(windowW, h, n, i, hoverIndex) : restPose(windowW, h, n, i);
            float d = Math.abs(p.cx - pointerX);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Nearest card by resting centre column. */
    public static int nearestCard(float windowW, float h, int n, float pointerX) {
        return nearestCard(windowW, h, n, pointerX, -1);
    }

    /**
     * True if {@code (px, py)} lies inside the rotated card rectangle, expanded by {@code pad}
     * on every side. libGDX rotation is counter-clockwise degrees about the card centre.
     */
    public static boolean pointHitsPose(Pose pose, float px, float py, float pad) {
        float dx = px - pose.cx;
        float dy = py - pose.cy;
        double rad = Math.toRadians(pose.rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float lx = dx * cos + dy * sin;
        float ly = -dx * sin + dy * cos;
        return Math.abs(lx) <= pose.w / 2f + pad && Math.abs(ly) <= pose.h / 2f + pad;
    }

    /**
     * Resting-hand hit: the topmost card whose rotated quad (minus parts covered by cards drawn
     * later / higher index) contains the pointer. Equivalent to testing full quads from front to
     * back and taking the first hit. {@code pad} expands each quad.
     */
    public static int restingVisibleHit(float windowW, float h, int n, float pointerX, float pointerY,
                                        float pad) {
        if (n <= 0) {
            return -1;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (pointHitsPose(restPose(windowW, h, n, i), pointerX, pointerY, pad)) {
                return i;
            }
        }
        return -1;
    }

    /** True while the pointer is over any card of the opened (spread) hand, plus {@code pad}. */
    public static boolean pointerOverOpenedCards(float windowW, float h, int n, int hoverIndex,
                                                 float pointerX, float pointerY, float pad) {
        if (n <= 0 || hoverIndex < 0 || hoverIndex >= n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (pointHitsPose(hoverPose(windowW, h, n, i, hoverIndex), pointerX, pointerY, pad)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pose the hand eases toward. Only the resting slot under the pointer flies up
     * ({@link #grownInSlot}); neighbours stay on {@link #restPose}. Grow is visual:
     * {@link #hoverIndexFor} still hits the resting fan, and draw order stays fan
     * order. {@code dragging} tucks every slot back to rest. Callers that still have
     * the pointer inside the hand use {@link #dragLayoutPose} to open a reorder gap.
     * The gripped card is not moved by this; the caller keeps it on the cursor.
     */
    public static Pose layoutPose(float windowW, float h, int n, int i, int hoverIndex, boolean dragging) {
        if (dragging || hoverIndex < 0 || hoverIndex >= n || i != hoverIndex) {
            return restPose(windowW, h, n, i);
        }
        return grownInSlot(windowW, h, n, i);
    }

    /**
     * True while the pointer is in the resting fan Steve sees, plus {@link #OPEN_HIT_PAD_FRAC}.
     * The raised picture is not part of this zone. {@code opened} and {@code hoverIndex} do not
     * widen it: coming in from the side does not grow a second hit target.
     * The hand is left when the pointer is above that top, past the cast line and clear of
     * the row, or outside the row horizontally.
     */
    public static boolean pointerInHand(float windowW, float h, int n, int hoverIndex, boolean opened,
                                         float pointerX, float pointerY) {
        if (n <= 0) {
            return false;
        }
        float pad = OPEN_HIT_PAD_FRAC * h;
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float[] box = axisBounds(restPose(windowW, h, n, i));
            minX = Math.min(minX, box[0]);
            maxX = Math.max(maxX, box[1]);
            minY = Math.min(minY, box[2]);
            maxY = Math.max(maxY, box[3]);
        }
        boolean outsideX = pointerX < minX - pad || pointerX > maxX + pad;
        boolean aboveHand = pointerY > maxY + pad;
        boolean belowHand = pointerY < minY - pad;
        boolean pastCastLine = pointerY > castLineY(h) && aboveHand;
        return !outsideX && !aboveHand && !belowHand && !pastCastLine;
    }

    /** Axis-aligned bounds of a rotated card: minX, maxX, minY, maxY. */
    private static float[] axisBounds(Pose p) {
        double rad = Math.toRadians(p.rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float hw = p.w / 2f;
        float hh = p.h / 2f;
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (float lx : new float[]{-hw, hw}) {
            for (float ly : new float[]{-hh, hh}) {
                float x = p.cx + lx * cos - ly * sin;
                float y = p.cy + lx * sin + ly * cos;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return new float[]{minX, maxX, minY, maxY};
    }

    /**
     * Resting-fan centre x of slot {@code i}. Reorder thresholds stay on the fan
     * the pointer is actually over.
     */
    public static float slotCenterX(float windowW, float h, int n, int i) {
        return restPose(windowW, h, n, i).cx;
    }

    /**
     * Where the gripped card would insert, from 0 through {@code n - 1}.
     * Counts other cards whose slot centre is strictly left of the pointer.
     * Sitting on a centre stays before that card, so the midpoint between two
     * centres does not flicker.
     */
    public static int reorderIndex(float windowW, float h, int n, int gripIndex, float pointerX) {
        if (n <= 1) {
            return 0;
        }
        int drop = 0;
        for (int i = 0; i < n; i++) {
            if (i == gripIndex) {
                continue;
            }
            if (pointerX > slotCenterX(windowW, h, n, i)) {
                drop++;
            }
        }
        return drop;
    }

    /**
     * Fan slot for card {@code cardIndex} while {@code gripIndex} is held and the gap
     * is {@code dropIndex}. The gripped card returns -1 so it is not drawn in the fan.
     */
    public static int reorderSlot(int n, int cardIndex, int gripIndex, int dropIndex) {
        if (n <= 0 || cardIndex < 0 || cardIndex >= n || cardIndex == gripIndex) {
            return -1;
        }
        int clampedDrop = Math.max(0, Math.min(n - 1, dropIndex));
        int rank = cardIndex < gripIndex ? cardIndex : cardIndex - 1;
        return rank < clampedDrop ? rank : rank + 1;
    }

    /** Resting pose of a non-gripped card, shifted so {@code dropIndex} is an empty gap. */
    public static Pose reorderPose(float windowW, float h, int n, int cardIndex, int gripIndex, int dropIndex) {
        int slot = reorderSlot(n, cardIndex, gripIndex, dropIndex);
        if (slot < 0) {
            return restPose(windowW, h, n, Math.max(0, Math.min(n - 1, cardIndex)));
        }
        return restPose(windowW, h, n, slot);
    }

    /**
     * Pose for every card except the one on the cursor. Inside the hand, the others stay
     * in the resting fan and shift to leave a gap. Once the pointer has left, they close
     * that gap. They do not grow.
     */
    public static Pose dragLayoutPose(float windowW, float h, int n, int i, int hoverIndex, boolean opened,
                                       int gripIndex, float pointerX, float pointerY) {
        if (i < 0 || i >= n) {
            return restPose(windowW, h, Math.max(n, 1), 0);
        }
        if (!pointerInHand(windowW, h, n, hoverIndex, opened, pointerX, pointerY) || i == gripIndex) {
            return restPose(windowW, h, n, i);
        }
        return reorderPose(windowW, h, n, i, gripIndex, reorderIndex(windowW, h, n, gripIndex, pointerX));
    }

    /** Identity order with {@code from} removed and inserted at {@code to}. */
    public static int[] movedOrder(int n, int from, int to) {
        int[] order = new int[n];
        if (n <= 0) {
            return order;
        }
        if (from < 0 || from >= n) {
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            return order;
        }
        int dest = Math.max(0, Math.min(n - 1, to));
        int[] rest = new int[n - 1];
        int r = 0;
        for (int i = 0; i < n; i++) {
            if (i != from) {
                rest[r++] = i;
            }
        }
        r = 0;
        for (int i = 0; i < n; i++) {
            order[i] = i == dest ? from : rest[r++];
        }
        return order;
    }

    /**
     * Card indices after release. Inside the hand, the gripped card inserts at
     * {@link #reorderIndex}. Outside, the order is unchanged (that release is a cast or a cancel).
     */
    public static int[] orderAfterRelease(float windowW, float h, int n, int gripIndex, int hoverIndex,
                                           boolean opened, float pointerX, float pointerY) {
        int[] same = new int[n];
        for (int i = 0; i < n; i++) {
            same[i] = i;
        }
        if (gripIndex < 0 || gripIndex >= n
                || !pointerInHand(windowW, h, n, hoverIndex, opened, pointerX, pointerY)) {
            return same;
        }
        return movedOrder(n, gripIndex, reorderIndex(windowW, h, n, gripIndex, pointerX));
    }

    /**
     * Hover target for a pointer. Only the visible resting fan, padded by
     * {@link #OPEN_HIT_PAD_FRAC} * H. The grown card is not a hit target, so the
     * pointer can move onto the next resting slot while that card is drawn larger.
     * {@code currentHover} does not stick: leaving the fan closes the hover.
     * Returns -1 when the pointer is off every resting card.
     */
    public static int hoverIndexFor(float windowW, float h, int n, int currentHover, float pointerX, float pointerY) {
        if (n <= 0) {
            return -1;
        }
        return restingVisibleHit(windowW, h, n, pointerX, pointerY, OPEN_HIT_PAD_FRAC * h);
    }

    /**
     * True while the pointer is on the resting fan plus {@link #OPEN_HIT_PAD_FRAC} * H.
     * The raised picture does not count.
     */
    public static boolean pointerInHandArea(float windowW, float h, int n, int hoverIndex,
                                           float pointerX, float pointerY) {
        return restingVisibleHit(windowW, h, n, pointerX, pointerY, OPEN_HIT_PAD_FRAC * h) >= 0;
    }

    public static final class Pose {
        public final float cx;
        public final float cy;
        public final float w;
        public final float h;
        public final float rotationDeg;
        public final float scale;

        public Pose(float cx, float cy, float w, float h, float rotationDeg, float scale) {
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.rotationDeg = rotationDeg;
            this.scale = scale;
        }
    }
}
