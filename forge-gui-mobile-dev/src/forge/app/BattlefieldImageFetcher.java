package forge.app;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.ImageFetcher;
import forge.util.ScryfallRateLimiter;
import forge.util.TextUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forge's image fetcher for this window. Same Scryfall / CDN URLs, written into
 * the same Cache\\pics\\cards folder the board already reads.
 */
final class BattlefieldImageFetcher extends ImageFetcher {
    static final BattlefieldImageFetcher INSTANCE = new BattlefieldImageFetcher();

    @Override
    protected Runnable getDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
        return () -> {
            for (String url : downloadUrls) {
                try {
                    if (save(url, destPath)) {
                        if (notifyObservers != null) {
                            GuiBase.getInterface().invokeInEdtLater(notifyObservers);
                        }
                        return;
                    }
                } catch (Exception failed) {
                    System.err.println("Card picture failed: " + destPath + " — " + failed.getMessage());
                }
            }
        };
    }

    private static boolean save(String urlToDownload, String destPath) throws Exception {
        if (disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) {
            return false;
        }
        if (ScryfallRateLimiter.shouldSkip(urlToDownload)) return false;
        boolean scryfall = urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD)
                || urlToDownload.startsWith(ForgeConstants.URL_SCRYFALL_CDN)
                || urlToDownload.contains("api.scryfall.com")
                || urlToDownload.contains("cards.scryfall.io");
        String dest = destPath;
        if (scryfall || urlToDownload.contains(".fullborder.")) {
            dest = TextUtil.fastReplace(destPath, ".full.", ".fullborder.");
        }
        if (scryfall && !dest.contains(".full") && !dest.contains(".artcrop")
                && !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR)) {
            dest = dest.replace(".jpg", ".fullborder.jpg");
        }
        ScryfallRateLimiter.acquire(urlToDownload);
        URL url = new URL(urlToDownload.replace("PLANECHASEBG:", ""));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            ScryfallRateLimiter.noteIfRateLimited(code, urlToDownload, conn.getHeaderField("Retry-After"));
            conn.disconnect();
            return false;
        }
        Path tmp = Path.of(dest + ".tmp");
        Files.createDirectories(tmp.getParent());
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        } finally {
            conn.disconnect();
        }
        if (Files.size(tmp) < 1000) {
            Files.deleteIfExists(tmp);
            return false;
        }
        Path done = Path.of(dest);
        Files.deleteIfExists(done);
        Files.move(tmp, done);
        BattlefieldPics.arrived(done);
        return true;
    }
}
