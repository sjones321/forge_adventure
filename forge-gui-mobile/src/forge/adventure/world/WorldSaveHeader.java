package forge.adventure.world;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Disposable;
import forge.adventure.util.Serializer;
import forge.util.ScreenUtil;

import java.io.EOFException;
import java.io.IOException;
import java.io.OptionalDataException;
import java.util.Date;

/**
 * Header information for the save file like a preview image, save name and saved date.
 */
public class WorldSaveHeader implements java.io.Serializable, Disposable {
    public static int previewImageWidth = 512; // may cause serialization error when removed..
    public Pixmap preview;
    public String name;
    public Date saveDate;
    /**
     * CO5 amendment: true when this save is a co-op world (New Game "Co-op world" or
     * one-time convert). Hosting is refused unless this flag is set. Solo saves stay false
     * and never receive co-op / partner progress.
     */
    public boolean coopWorld;

    private void writeObject(java.io.ObjectOutputStream out) throws IOException {

        out.writeUTF(name != null ? name : "");
        // Null-safe: headless / partner-flush tests must not allocate a Pixmap just to serialize.
        Serializer.WritePixmap(out, preview, false);
        out.writeObject(saveDate);
        out.writeBoolean(coopWorld);
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        name = in.readUTF();
        if (preview != null) {
            preview.dispose();
        }
        // Headless / missing natives: length-0 writes decode to null; a non-empty
        // preview that cannot allocate a Pixmap must not abort the whole load.
        try {
            preview = Serializer.ReadPixmap(in);
        } catch (final Throwable t) {
            preview = null;
        }
        saveDate = (Date) in.readObject();
        // Pre-CO5-amendment saves omit the flag → solo world.
        try {
            coopWorld = in.readBoolean();
        } catch (final EOFException | OptionalDataException e) {
            coopWorld = false;
        }
    }

    public void dispose() {
        preview.dispose();
    }

    public void createPreview() {
        try {
            if (preview != null)
                preview.dispose();
            preview = ScreenUtil.getInstance().getThumbnailPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
