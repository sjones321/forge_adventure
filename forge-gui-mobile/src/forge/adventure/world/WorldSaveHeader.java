package forge.adventure.world;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Disposable;
import forge.adventure.util.Serializer;
import forge.util.ScreenUtil;

import java.io.IOException;
import java.util.Date;

/**
 * Header information for the save file like a preview image, save name and saved date.
 */
public class WorldSaveHeader implements java.io.Serializable, Disposable {
    public static int previewImageWidth = 512; // may cause serialization error when removed..
    public Pixmap preview;
    public String name;
    public Date saveDate;

    private void writeObject(java.io.ObjectOutputStream out) throws IOException {

        out.writeUTF(name != null ? name : "");
        // Null-safe: headless / partner-flush tests must not allocate a Pixmap just to serialize.
        Serializer.WritePixmap(out, preview, false);
        out.writeObject(saveDate);
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
            // Drain the PNG payload if ReadPixmap failed mid-read after length.
            // Serializer.ReadPixmap either returns null (len 0) or fully reads bytes
            // before constructing Pixmap — so a Pixmap ctor failure leaves the stream OK.
        }
        saveDate = (Date) in.readObject();
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