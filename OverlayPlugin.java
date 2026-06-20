package com.echo.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Overlay")
public class OverlayPlugin extends Plugin {

    public static OverlayPlugin instance;

    private byte[] pendingBytes;

    /** Called from the capture service to push a recognised value into the web app. */
    public static void pushToWeb(final String js) {
        final OverlayPlugin inst = instance;
        if (inst == null || inst.getBridge() == null || inst.getBridge().getActivity() == null) return;
        inst.getBridge().getActivity().runOnUiThread(new Runnable() {
            public void run() {
                try { inst.getBridge().eval(js, null); } catch (Exception e) {}
            }
        });
    }

    @Override
    public void load() { instance = this; }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        boolean granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext());
        if (!granted) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        JSObject r = new JSObject();
        r.put("granted", granted);
        call.resolve(r);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(getContext())) {
            JSObject r = new JSObject();
            r.put("started", false);
            r.put("reason", "overlay-permission");
            call.resolve(r);
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(call, mpm.createScreenCaptureIntent(), "projectionResult");
    }

    @ActivityCallback
    private void projectionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Intent svc = new Intent(getContext(), OverlayService.class);
            svc.putExtra("resultCode", result.getResultCode());
            svc.putExtra("data", result.getData());
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(svc);
            else getContext().startService(svc);
            JSObject r = new JSObject();
            r.put("started", true);
            call.resolve(r);
        } else {
            JSObject r = new JSObject();
            r.put("started", false);
            r.put("reason", "capture-denied");
            call.resolve(r);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), OverlayService.class));
        call.resolve();
    }

    @PluginMethod
    public void setCurrent(PluginCall call) {
        if (OverlayService.instance != null) {
            String label = call.getString("label", "");
            String unit = call.getString("unit", "");
            String dir = call.getString("dir", "");
            double[] t = { Double.NaN, Double.NaN, Double.NaN };
            JSArray arr = call.getArray("thr");
            if (arr != null) {
                for (int i = 0; i < 3 && i < arr.length(); i++) {
                    if (!arr.isNull(i)) {
                        try { t[i] = arr.getDouble(i); } catch (Exception e) {}
                    }
                }
            }
            OverlayService.instance.setCurrent(label, unit, dir, t);
        }
        call.resolve();
    }

    @PluginMethod
    public void saveDocument(PluginCall call) {
        String data = call.getString("data", "");
        String name = call.getString("name", "document");
        String mime = call.getString("mime", "application/octet-stream");
        try {
            pendingBytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            call.reject("bad data");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(call, intent, "saveResult");
    }

    @ActivityCallback
    private void saveResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        try {
            if (result.getResultCode() == Activity.RESULT_OK
                    && result.getData() != null
                    && result.getData().getData() != null
                    && pendingBytes != null) {
                Uri uri = result.getData().getData();
                java.io.OutputStream os = getContext().getContentResolver().openOutputStream(uri);
                os.write(pendingBytes);
                os.flush();
                os.close();
                pendingBytes = null;
                JSObject r = new JSObject();
                r.put("saved", true);
                call.resolve(r);
            } else {
                pendingBytes = null;
                JSObject r = new JSObject();
                r.put("saved", false);
                call.resolve(r);
            }
        } catch (Exception e) {
            pendingBytes = null;
            call.reject("write failed: " + e.getMessage());
        }
    }
}
