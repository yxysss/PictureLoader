package libcore.io;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.*;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import libcore.io.DiskLruCache;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.concurrent.*;
import libcore.io.DiskLruCache.*;

/**
 * Created by Y.X.Y on 2017/4/23 0023.
 */
public class PictureLoader {

    private Context context;

    private static PictureLoader pictureLoader = null;

    public static PictureLoader getPictureLoader(Context context) {
        if (pictureLoader == null) {
            synchronized (PictureLoader.class) {
                if (pictureLoader == null) pictureLoader = new PictureLoader(context);
            }
        }
        return pictureLoader;
    }

    private LruCache<String, Bitmap> MemoryCache;

    private HashMap<Long, ImageView> imageViewHashMap;

    private ThreadPoolExecutor executor;

    private DiskLruCache diskLruCache = null;

    private Handler handler = new Handler() {
        public void handleMessage(Message message) {
            switch (message.what) {
                case 0:
                    Log.d("Error", "0");
                    break;
                case 1:
                    Log.d("Success", "1");
                    Bundle bundle = message.getData();
                    Long time = bundle.getLong("time");
                    Bitmap bitmap = bundle.getParcelable("bitmap");
                    ImageView imageView = imageViewHashMap.get(time);
                    imageView.setImageBitmap(bitmap);
                    Log.d("ImageView", imageView.getId() + "");
                    Log.d("Bitmap", bitmap.toString());
                    imageViewHashMap.remove(time);

                    break;
                default:
                    break;
            }
        }
    };

    private PictureLoader(Context context) {
        this.context = context;
        int maxMemory = (int) (Runtime.getRuntime().maxMemory())/1024;
        int cacheSize = maxMemory/8;
        Log.d("PictureLoader", maxMemory + "," + cacheSize);
        MemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount()/1024;
            }
        };
        imageViewHashMap = new HashMap<>();
        executor = new ThreadPoolExecutor(5, 10, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue(5), new ThreadPoolExecutor.AbortPolicy());

        File cacheDir = getDiskCacheDir(context, "bitmap");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        try {
            diskLruCache = DiskLruCache.open(cacheDir, getAppVersion(context), 1, 10*1024*1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // BitmapFactory.decodeByteArray()
        // BitmapFactory.decodeFile()
        // BitmapFactory.decodeFileDescriptor()
        // BitmapFactory.decodeResource()
        // BitmapFactory.decodeResourceStream()
        // BitmapFactory.decodeStream()
    }

    public void ImageRequest(String url, ImageView imageView, int reqWidth, int reqHeight) {
        URL Url = null;
        try {
            Url = new URL(url);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        if (Url == null) return ;
        String key = urltokey(url);
        Log.d("key", key);
        Bitmap bitmap = getbitmapfromcache(key);
        if (bitmap == null) {
            Long time = SystemClock.currentThreadTimeMillis();
            imageViewHashMap.put(time, imageView);
            pictureloaderthread thread = new pictureloaderthread(url, time, reqWidth, reqHeight);
            executor.submit(thread);
        } else {
            Bitmap bitmap0 = Bitmap.createScaledBitmap(bitmap, reqWidth, reqHeight, true);
            // 如果reqWidth == bitmap.Width, reqHeight == bitmap.Height , bitmap0 == bitmap
            if (bitmap != bitmap0) {
                bitmap.recycle();
            }
            imageView.setImageBitmap(bitmap0);
        }
    }

    private void putbitmapintocache(String key, Bitmap bitmap) {
        MemoryCache.put(key, bitmap);
    }

    private Bitmap getbitmapfromcache(String key) {
        return MemoryCache.get(key);
    }

    public File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !Environment.isExternalStorageRemovable()) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        Log.d("bitmapPath", cachePath + File.separator + uniqueName);
        return new File(cachePath + File.separator + uniqueName);
    }

    public int getAppVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return 1;
    }

    private boolean downloadurltostream(String url, OutputStream out) {
        URL Url = null;
        try {
            Url = new URL(url);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        int mark = 1;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)Url.openConnection();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (connection == null) return false;
        try {
            connection.setRequestMethod("GET");
        } catch (ProtocolException e) {
            e.printStackTrace();
        }
        try {
            connection.connect();
        } catch (IOException e) {
            e.printStackTrace();
        }
        BufferedInputStream bin = null;
        try {
            bin = new BufferedInputStream(connection.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bin == null) return false;
        byte[] bytes = new byte[100024];
        try {
            int length;
            while( (length = bin.read(bytes, 0, bytes.length)) != -1) {
                out.write(bytes, 0, length);
            }
            mark = 0;
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (bin != null) bin.close();
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (connection != null) connection.disconnect();
        if (mark == 0) {
            return true;
        } else {
            return false;
        }
    }

    class pictureloaderthread extends Thread {

        private String imageurl;

        private int reqWidth;

        private int reqHeight;

        private Long time;


        public pictureloaderthread(String imageurl, Long time, int reqWidth, int reqHeight) {
            this.imageurl = imageurl;
            this.time = time;
            this.reqWidth = reqWidth;
            this.reqHeight = reqHeight;
            Log.d("pictureloaderthread", time + " " + reqWidth + " " + reqHeight);
        }

        public void run() {
            Snapshot snapshot = null;
            String key = urltokey(imageurl);
            try {
                snapshot = diskLruCache.get(key);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (snapshot == null) {
                Editor editor = null;
                try {
                    editor = diskLruCache.edit(key);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (editor == null) {
                    sendErrorMessage();
                    return ;
                }
                OutputStream out = null;
                try {
                    out = editor.newOutputStream(0);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (out == null) {
                    sendErrorMessage();
                    return ;
                }
                if (downloadurltostream(imageurl, out)) {
                    try {
                        editor.commit();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        editor.abort();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                try {
                    snapshot = diskLruCache.get(key);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (snapshot == null) {
                sendErrorMessage();
                return ;
            }
            Bitmap bitmap = compress(key, reqWidth, reqHeight);
            // Bitmap bitmap = BitmapFactory.decodeStream(in);
            Message message = new Message();
            message.what = 1;
            Bundle bundle = new Bundle();
            bundle.putParcelable("bitmap", bitmap);
            bundle.putLong("time", time);
            message.setData(bundle);
            handler.sendMessage(message);
            return ;
        }

        private void sendErrorMessage() {
            Message message = new Message();
            message.what = 0;
            handler.sendMessage(message);
        }

    }

    private String urltokey(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        MessageDigest md5 = null;

        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        if (md5 == null) return "";
        byte[] bytes = md5.digest(url.getBytes());
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            String temp = Integer.toHexString(b & 0xff);
            if (temp.length() == 1) {
                temp = "0" + temp;
            }
            result.append(temp);
        }
        return result.toString();
    }

    private Bitmap compress(String key, int reqWidth, int reqHeight) {
        Snapshot snapshot = null;
        try {
            snapshot = diskLruCache.get(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (snapshot == null) return null;
        InputStream in = snapshot.getInputStream(0);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, new Rect(), options);
        int imageHeight = options.outHeight;
        int imageWidth = options.outWidth;
        Log.d("imageHeight", imageHeight + " " + reqHeight);
        Log.d("imageWidth", imageWidth + " " + reqWidth);
        int heightRadio = Math.round((float)imageHeight/(float)reqHeight);
        int widthRadio = Math.round((float)imageWidth/(float)reqWidth);
        int inSampleSize = heightRadio<widthRadio?heightRadio:widthRadio;
        if (inSampleSize < 1) inSampleSize = inSampleSize;
        Log.d("inSampleSize", inSampleSize + "");
        options.inSampleSize = 1;
        options.inJustDecodeBounds = false;
        try {
            snapshot = diskLruCache.get(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
        in = snapshot.getInputStream(0);
        Bitmap bitmap = BitmapFactory.decodeStream(in, new Rect(), options);
        Bitmap bitmap0 = Bitmap.createScaledBitmap(bitmap, reqWidth, reqHeight, true);
        // 如果reqWidth == bitmap.Width, reqHeight == bitmap.Height , bitmap0 == bitmap
        if (bitmap != bitmap0) {
            bitmap.recycle();
        }
        return bitmap0;
    }

    private void Destroy() {
        executor.shutdownNow();
        context = null;
        pictureLoader = null;
        imageViewHashMap = null;
        diskLruCache = null;
        MemoryCache = null;
    }
}
