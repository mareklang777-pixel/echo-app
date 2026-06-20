package com.echo.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OverlayService extends Service {

    public static OverlayService instance;

    private static final int GREEN = 0xFF2BB673, AMBER = 0xFFE6A100, ORANGE = 0xFFE8631A,
            RED = 0xFFC0392B, GREY = 0xFF9FB3AC, WHITE = 0xFFFFFFFF;

    private WindowManager wm;
    private LinearLayout root;
    private WindowManager.LayoutParams lp;
    private View box;
    private GradientDrawable boxBg;
    private TextView labelView, valueView, statusView;
    private ScaleGestureDetector scaleDet;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay vdisplay;
    private int sw, sh, dpi;

    private HandlerThread capThread;
    private Handler capHandler;
    private volatile Bitmap lastFull;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    // numbers captured from the last Read, walked through by Confirm/Skip
    private final List<String> queue = new ArrayList<>();
    private int qIndex = 0;
    private String pending = "";

    // severity for the current measurement, pushed from the web app
    private String sevDir = "";
    private double[] sevThr = { Double.NaN, Double.NaN, Double.NaN };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundInternal();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels; sh = dm.heightPixels; dpi = dm.densityDpi;
        buildOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int code = intent != null ? intent.getIntExtra("resultCode", Integer.MIN_VALUE) : Integer.MIN_VALUE;
        Intent data = intent != null ? (Intent) intent.getParcelableExtra("data") : null;
        if (data != null && code != Integer.MIN_VALUE) startProjection(code, data);
        return START_STICKY;
    }

    private void startForegroundInternal() {
        String chId = "echo_overlay";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(chId, "Echo capture", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification notif = new Notification.Builder(this, chId)
                .setContentTitle("Echo capture running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        boolean micOk = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (micOk) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, type);
        else
            startForeground(1, notif);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void buildOverlay() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        box = new View(this);
        boxBg = new GradientDrawable();
        boxBg.setColor(Color.TRANSPARENT);
        boxBg.setStroke(dp(2), GREEN);
        boxBg.setCornerRadius(dp(8));
        box.setBackground(boxBg);
        root.addView(box, new LinearLayout.LayoutParams(dp(200), dp(90)));

        scaleDet = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) box.getLayoutParams();
                float sx = d.getPreviousSpanX() > 0 ? d.getCurrentSpanX() / d.getPreviousSpanX() : 1f;
                float sy = d.getPreviousSpanY() > 0 ? d.getCurrentSpanY() / d.getPreviousSpanY() : 1f;
                int nw = (int) (p.width * sx);
                int nh = (int) (p.height * sy);
                p.width = Math.max(dp(60), Math.min(sw, nw));
                p.height = Math.max(dp(40), Math.min(sh, nh));
                box.setLayoutParams(p);
                return true;
            }
        });
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                scaleDet.onTouchEvent(ev);
                return true;
            }
        });

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(Color.parseColor("#0F2A22"));
        strip.setPadding(dp(8), dp(6), dp(8), dp(6));

        labelView = new TextView(this);
        labelView.setText("\u2014");
        labelView.setTextColor(Color.parseColor("#7FE3B6"));
        labelView.setTextSize(13);
        strip.addView(labelView);

        valueView = new TextView(this);
        valueView.setText("\u2013\u2013");
        valueView.setTextColor(GREY);
        valueView.setTextSize(22);
        strip.addView(valueView);

        statusView = new TextView(this);
        statusView.setText("aim box at a number, then Read");
        statusView.setTextColor(Color.parseColor("#9FB3AC"));
        statusView.setTextSize(10);
        strip.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(makeBtn("Read", "#1F6FB2", 4));
        row1.addView(makeBtn("Confirm", "#1F9D62", 0));
        strip.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(makeBtn("Skip", "#C0392B", 1));
        row2.addView(makeBtn("Next", "#33564A", 2));
        row2.addView(makeBtn("X", "#33564A", 3));
        strip.addView(row2);

        final Button voiceBtn = new Button(this);
        voiceBtn.setText("Hold to talk");
        voiceBtn.setTextSize(12);
        voiceBtn.setTextColor(Color.WHITE);
        voiceBtn.setBackgroundColor(Color.parseColor("#6D4AB2"));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(dp(2), dp(6), dp(2), 0);
        voiceBtn.setLayoutParams(vlp);
        voiceBtn.setPadding(0, dp(8), 0, dp(8));
        voiceBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        voiceBtn.setText("Listening\u2026");
                        voiceBtn.setBackgroundColor(Color.parseColor("#C0392B"));
                        OverlayPlugin.pushToWeb("window.echoNative&&window.echoNative.voiceStart&&window.echoNative.voiceStart()");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        voiceBtn.setText("Hold to talk");
                        voiceBtn.setBackgroundColor(Color.parseColor("#6D4AB2"));
                        OverlayPlugin.pushToWeb("window.echoNative&&window.echoNative.voiceStop&&window.echoNative.voiceStop()");
                        return true;
                }
                return false;
            }
        });
        strip.addView(voiceBtn);

        root.addView(strip);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(20);
        lp.y = dp(80);

        final float[] d = new float[2];
        final int[] o = new int[2];
        labelView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        d[0] = ev.getRawX(); d[1] = ev.getRawY();
                        o[0] = lp.x; o[1] = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = o[0] + (int) (ev.getRawX() - d[0]);
                        lp.y = o[1] + (int) (ev.getRawY() - d[1]);
                        wm.updateViewLayout(root, lp);
                        return true;
                }
                return false;
            }
        });

        wm.addView(root, lp);
    }

    private Button makeBtn(String text, String color, final int action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(4), dp(2), 0);
        btn.setLayoutParams(p);
        btn.setPadding(0, dp(6), 0, dp(6));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action == 0) sendCurrent();
                else if (action == 1) skipCurrent();
                else if (action == 2) OverlayPlugin.pushToWeb("window.echoNative.next()");
                else if (action == 4) readNow();
                else stopSelf();
            }
        });
        return btn;
    }

    public void setCurrent(final String label, final String unit, final String dir, final double[] thr) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                sevDir = dir == null ? "" : dir;
                sevThr = (thr != null && thr.length == 3) ? thr : new double[]{ Double.NaN, Double.NaN, Double.NaN };
                labelView.setText(unit != null && unit.length() > 0 ? label + "  (" + unit + ")" : label);
                queue.clear();
                qIndex = 0;
                resetValue();
            }
        });
    }

    private void resetValue() {
        pending = "";
        valueView.setText("\u2013\u2013");
        valueView.setTextColor(GREY);
        boxBg.setStroke(dp(2), GREEN);
    }

    private double parseNum(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }

    private int colorFor(double x) {
        int[] cols = { GREEN, AMBER, ORANGE, RED };
        boolean any = false;
        for (double t : sevThr) if (!Double.isNaN(t)) any = true;
        if (!any || Double.isNaN(x)) return WHITE;
        int g = 0;
        for (int k = 0; k < 3; k++) {
            if (Double.isNaN(sevThr[k])) continue;
            if ("lo".equals(sevDir)) { if (x < sevThr[k]) g = k + 1; }
            else { if (x >= sevThr[k]) g = k + 1; }
        }
        return cols[g];
    }

    // ---- number extraction (numbers only, in reading order) ----
    private List<String> numbersIn(String text) {
        List<String> nums = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+(?:[.,]\\d+)?").matcher(text);
        while (m.find()) {
            String n = m.group().replace(",", ".");
            int digits = n.replace(".", "").length();
            if (digits >= 1 && digits <= 5) nums.add(n);
        }
        return nums;
    }

    private interface TextCb { void on(String t); }

    private void ocrBox(final TextCb cb) {
        final Bitmap full = lastFull;
        if (full == null) {
            handler.post(new Runnable() { public void run() { statusView.setText("waiting for screen\u2026"); } });
            return;
        }
        Rect r = boxRectOnScreen();
        int left = Math.max(0, Math.min(r.left, full.getWidth() - 1));
        int top = Math.max(0, Math.min(r.top, full.getHeight() - 1));
        int cw = Math.max(1, Math.min(r.width(), full.getWidth() - left));
        int chh = Math.max(1, Math.min(r.height(), full.getHeight() - top));
        Bitmap crop;
        try { crop = Bitmap.createBitmap(full, left, top, cw, chh); }
        catch (Exception e) { return; }
        recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener(t -> cb.on(t.getText()))
                .addOnFailureListener(e -> handler.post(new Runnable() { public void run() { statusView.setText("reader error"); } }));
    }

    private void readNow() {
        ocrBox(new TextCb() {
            public void on(String t) {
                final List<String> nums = numbersIn(t);
                handler.post(new Runnable() {
                    public void run() {
                        queue.clear();
                        queue.addAll(nums);
                        qIndex = 0;
                        showQueueItem();
                    }
                });
            }
        });
    }

    private void showQueueItem() {
        if (qIndex >= 0 && qIndex < queue.size()) {
            pending = queue.get(qIndex);
            int c = colorFor(parseNum(pending));
            valueView.setText(pending);
            valueView.setTextColor(c);
            boxBg.setStroke(dp(2), c);
            statusView.setText("number " + (qIndex + 1) + " of " + queue.size() + ", tap Confirm");
        } else {
            pending = "";
            valueView.setText("\u2013\u2013");
            valueView.setTextColor(GREY);
            boxBg.setStroke(dp(2), GREEN);
            statusView.setText(queue.isEmpty() ? "no number in box, reposition and Read" : "no more numbers, tap Read");
        }
    }

    private void sendCurrent() {
        if (pending.isEmpty()) return;
        OverlayPlugin.pushToWeb("window.echoNative.receive('" + pending.replace("'", "") + "')");
        qIndex++;
        showQueueItem();
    }

    private void skipCurrent() {
        if (queue.isEmpty()) return;
        qIndex++;
        showQueueItem();
    }

    private Rect boxRectOnScreen() {
        if (box == null) return new Rect(0, 0, sw, sh);
        int[] loc = new int[2];
        box.getLocationOnScreen(loc);
        int left = Math.max(0, loc[0]);
        int top = Math.max(0, loc[1]);
        return new Rect(left, top, left + box.getWidth(), top + box.getHeight());
    }

    private void startProjection(int code, Intent data) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        capThread = new HandlerThread("echo-cap");
        capThread.start();
        capHandler = new Handler(capThread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { lastFull = null; }
        }, capHandler);
        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buffer = plane.getBuffer();
                    int pixelStride = plane.getPixelStride();
                    int rowStride = plane.getRowStride();
                    int rowPadding = rowStride - pixelStride * sw;
                    Bitmap bmp = Bitmap.createBitmap(sw + rowPadding / pixelStride, sh, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buffer);
                    lastFull = bmp;
                } catch (Exception e) {
                    // skip a bad frame
                } finally {
                    if (image != null) { try { image.close(); } catch (Exception ignored) {} }
                }
            }
        }, capHandler);
        vdisplay = projection.createVirtualDisplay(
                "echo-cap", sw, sh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, capHandler);
        handler.postDelayed(statusTick, 1000);
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (pending.isEmpty() && queue.isEmpty()) {
                statusView.setText(lastFull != null ? "screen ready, tap Read" : "waiting for screen\u2026");
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onDestroy() {
        try { handler.removeCallbacks(statusTick); } catch (Exception e) {}
        try { if (imageReader != null) imageReader.setOnImageAvailableListener(null, null); } catch (Exception e) {}
        try { if (vdisplay != null) vdisplay.release(); } catch (Exception e) {}
        try { if (projection != null) projection.stop(); } catch (Exception e) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception e) {}
        try { if (capThread != null) capThread.quitSafely(); } catch (Exception e) {}
        try { if (root != null) wm.removeView(root); } catch (Exception e) {}
        lastFull = null;
        instance = null;
        super.onDestroy();
    }
}
