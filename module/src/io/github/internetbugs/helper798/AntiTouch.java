package io.github.internetbugs.helper798;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Anti-accidental-touch ("防误触") water session guard for com.cloudora.android.
 *
 * Opt-in toggle (default OFF, persisted until manually switched off) injected directly into both
 * DeviceCommonDetailActivity and StateCommonActivity. When enabled during an active phone-started
 * water session:
 *
 *   1. Extends DevStateHelper's status monitor beyond the host's default 20x6s (2 min) cap to
 *      continuous 2s polling (plus a watchdog) and promotes the host process with a real
 *      dataSync Foreground Service + partial WakeLock + WifiLock so screen-off does not freeze
 *      polling or cut off network sockets.
 *   2. When the server reports the device stopped without a phone-side manual stop (i.e. physical
 *      button touched on the machine), suppresses CloseAdPageEvent / Activity.finish() and
 *      automatically re-starts the device via DevControlHelper.t(false).
 *   3. Disarms immediately as soon as the user manually stops water from the phone (tapping
 *      「立即结算」 on StateCommonActivity or DeviceCommonDetailActivity, or any deviceEnd path),
 *      including a race guard if an auto-restart request was already in flight.
 */
final class AntiTouch {

    private static final String TAG = "N798";
    private static final String PREFS = "n798_helper_prefs";
    private static final String KEY_ENABLED = "anti_touch_enabled";

    private static final String CLS_DETAIL = "com.ilife.module.device.view.activity.DeviceCommonDetailActivity";
    private static final String CLS_STATE = "com.ilife.module.device.view.activity.StateCommonActivity";
    private static final String CLS_CTRL = "com.ilife.module.device.view.helper.DevControlHelper";
    private static final String CLS_STATE_HELPER = "com.ilife.module.device.view.helper.DevStateHelper";
    private static final String CLS_START_TIMEOUT = "com.ilife.module.device.view.helper.DevStateHelper$refreshAfterDeviceStart$2";
    private static final String CLS_VM = "com.ilife.lib.coremodel.http.vmx.DeviceViewModel";
    private static final String CLS_CACHE = "com.ilife.lib.common.data.DeviceCacheData";
    private static final String CLS_BUS = "com.ilife.lib.coremodel.bus.EventBus";

    // Main-process services already declared in com.cloudora.android's manifest.
    // The first four declare android:foregroundServiceType="dataSync" and are unused because
    // AdBlock suppresses all ad downloads.
    private static final String[] FGS_CANDIDATES = {
            "com.yfanads.android.adx.thirdpart.filedownload.services.FileDownloadService$SharedMainProcessService",
            "cj.mobile.fw.download.DownloadService",
            "com.fancy.mpsdk.download.DownloadService",
            "com.ltmb.ltsdk.core.fy.ptgapi.download.DownloadService",
            "androidx.work.impl.foreground.SystemForegroundService",
    };

    private static final String EXTRA_GUARD_FGS = "n798_guard_fgs";
    private static final String ACTION_GUARD_START = "io.github.internetbugs.helper798.GUARD_START";
    private static final String ACTION_GUARD_STOP = "io.github.internetbugs.helper798.GUARD_STOP";
    private static final String NOTIF_CHANNEL_ID = "n798_anti_touch_guard";
    private static final int NOTIF_ID = 79801;

    private static final String TAG_DETAIL_BAR = "n798_anti_touch_detail_bar";
    private static final String TAG_STATE_CARD = "n798_anti_touch_state_card";

    private static final int POLL_TIMES = 7200;          // 4 hours at 2s interval
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final long RESTART_DELAY_MS = 250L;
    private static final long RETRY_DELAY_MS = 2500L;
    private static final long RESTART_COOLDOWN_MS = 2000L;
    private static final long RESTART_TIMEOUT_MS = 16000L;
    private static final long INITIAL_START_TIMEOUT_MS = 20000L;
    private static final long WATCHDOG_INTERVAL_MS = 5000L;
    private static final long POLL_STALE_MS = 8500L;
    private static final long WAKELOCK_MAX_MS = 2L * 60 * 60 * 1000;
    private static final int MAX_CONSECUTIVE_FAILURES = 8;

    private enum State {
        IDLE,
        STARTING,
        RUNNING,
        RESTARTING
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static XposedModule mod;
    private static volatile boolean enabled;
    private static volatile boolean prefLoaded;

    private static volatile State state = State.IDLE;
    private static volatile boolean manualStop;
    private static volatile boolean inAutoRestartCall;
    private static volatile boolean inRaceEndCall;
    private static volatile String lastBtnStatus = "";
    private static volatile int restartCount;
    private static volatile int consecutiveFailures;
    private static volatile long lastRestartAttemptMs;
    private static volatile long lastPollMs;
    private static volatile long lastMonitorStartMs;
    private static volatile int currentDeviceNtype = -1;
    private static volatile String errorNote;

    private static Class<?> fgsServiceClass;
    private static boolean fgsDeclaresDataSync;
    private static volatile boolean fgsRunning;

    private static WeakReference<Activity> detailActRef = new WeakReference<>(null);
    private static WeakReference<Activity> stateActRef = new WeakReference<>(null);
    private static WeakReference<Object> ctrlHelperRef = new WeakReference<>(null);
    private static WeakReference<Object> stateHelperRef = new WeakReference<>(null);
    private static WeakReference<Object> monitorCbRef = new WeakReference<>(null);
    private static WeakReference<Service> guardServiceRef = new WeakReference<>(null);

    private static PowerManager.WakeLock wakeLock;
    private static WifiManager.WifiLock wifiLock;
    private static AudioTrack silentAudioTrack;

    private static final Runnable RESTART_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            performAutoRestart();
        }
    };

    private static final Runnable INITIAL_START_TIMEOUT_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            if (state == State.STARTING) {
                resetSession("initial start did not confirm within timeout");
            }
        }
    };

    private static final Runnable RESTART_TIMEOUT_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            if (state == State.RESTARTING && enabled && !manualStop) {
                log("auto-restart timed out waiting for confirmation");
                onAutoRestartError(-1, "启动确认超时");
            }
        }
    };

    private static final Runnable WATCHDOG_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            if (!enabled || manualStop || state != State.RUNNING || !isSupportedWaterDevice()) return;
            Activity detail = detailActRef.get();
            if (detail == null || detail.isFinishing() || detail.isDestroyed()) {
                resetSession("detail activity gone");
                return;
            }
            long gap = SystemClock.elapsedRealtime() - lastPollMs;
            if (lastPollMs > 0 && gap > POLL_STALE_MS) {
                log("watchdog detected stale poll (" + gap + "ms), restarting monitor");
                lastPollMs = SystemClock.elapsedRealtime();
                restartMonitorPolling(POLL_TIMES, POLL_INTERVAL_MS);
            }
            MAIN.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private AntiTouch() {
    }

    static void install(final XposedModule m, final ClassLoader cl) {
        mod = m;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 600; i++) {
                    if (resolvable(cl, CLS_DETAIL) && resolvable(cl, CLS_CTRL) && resolvable(cl, CLS_STATE_HELPER)) {
                        log("anti-touch classes ready after " + (i * 100) + "ms");
                        hookAll(m, cl);
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                log("anti-touch classes never appeared");
            }
        }, "n798-antitouch");
        t.setDaemon(true);
        t.start();
    }

    private static boolean resolvable(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String msg) {
        if (mod != null) mod.log(Log.INFO, TAG, "[AntiTouch] " + msg);
    }

    private static void ensurePrefLoaded(Context ctx) {
        if (prefLoaded || ctx == null) return;
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            enabled = sp.getBoolean(KEY_ENABLED, false);
            prefLoaded = true;
            log("loaded pref enabled=" + enabled);
        } catch (Throwable t) {
            log("load pref failed: " + t);
        }
    }

    private static void setEnabled(Context ctx, boolean val) {
        enabled = val;
        prefLoaded = true;
        errorNote = null;
        if (ctx != null) {
            try {
                ctx.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_ENABLED, val)
                        .apply();
            } catch (Throwable t) {
                log("save pref failed: " + t);
            }
        }
        log("toggled enabled=" + val + " state=" + state + " manualStop=" + manualStop);

        if (val) {
            if (state == State.RUNNING && !manualStop && isSupportedWaterDevice()) {
                startKeepAlive(ctx);
                lastPollMs = SystemClock.elapsedRealtime();
                restartMonitorPolling(POLL_TIMES, POLL_INTERVAL_MS);
                startWatchdog();
            }
        } else {
            MAIN.removeCallbacks(INITIAL_START_TIMEOUT_RUNNABLE);
            MAIN.removeCallbacks(RESTART_RUNNABLE);
            MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);
            MAIN.removeCallbacks(WATCHDOG_RUNNABLE);
            stopKeepAlive(ctx);
            if (state == State.RESTARTING) {
                state = State.IDLE;
            } else if (state == State.RUNNING) {
                // Restore default polling cadence when user turns guard off mid-session.
                restartMonitorPolling(20, 6000L);
            }
        }
        refreshAllUi();
    }

    private static boolean isSupportedWaterDevice() {
        int t = currentDeviceNtype;
        if (t < 0) return true; // unknown yet; allow
        // Exclude fixed-cycle / non-water appliances (washing machine, hair drier, dryer, charger, etc.)
        return t == 5 || t == 6 || t == 8 || t == 9 || t == 95 || t == 130;
    }

    private static boolean isGuarding() {
        return enabled && !manualStop && isSupportedWaterDevice()
                && (state == State.RUNNING || state == State.RESTARTING);
    }

    private static boolean shouldSuppressClose() {
        if (!enabled || manualStop || !isSupportedWaterDevice()) return false;
        if (state == State.RESTARTING) return true;
        return state == State.RUNNING && !"SETTLE_IMMEDIATELY".equals(lastBtnStatus);
    }

    // -------------------------------------------------------------------------
    // Hooks
    // -------------------------------------------------------------------------

    private static void hookAll(XposedModule m, ClassLoader cl) {
        hookForegroundService(m, cl);
        hookDetailActivity(m, cl);
        hookStateActivity(m, cl);
        hookControlHelper(m, cl);
        hookStateHelper(m, cl);
        hookDeviceViewModel(m, cl);
        hookDeviceCache(m, cl);
        hookEventBus(m, cl);
    }

    private static void hookForegroundService(XposedModule m, ClassLoader cl) {
        for (int i = 0; i < FGS_CANDIDATES.length; i++) {
            String name = FGS_CANDIDATES[i];
            Class<?> c = loadClass(cl, name);
            if (c == null || !Service.class.isAssignableFrom(c)) continue;

            Method onStartCommand = findMethodBelowService(c, "onStartCommand",
                    Intent.class, int.class, int.class);
            if (onStartCommand == null) {
                log("skip FGS candidate without onStartCommand: " + name);
                continue;
            }

            final Class<?> targetCls = c;
            final boolean hasDataSync = (i < 4);
            fgsServiceClass = targetCls;
            fgsDeclaresDataSync = hasDataSync;

            Method onCreate = findMethodBelowService(targetCls, "onCreate");
            if (onCreate != null) {
                hookExact(m, onCreate, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (chain.getThisObject().getClass() == targetCls) {
                            return null;
                        }
                        return chain.proceed();
                    }
                });
            }

            hookExact(m, onStartCommand, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    if (self.getClass() != targetCls) return chain.proceed();
                    Service svc = (Service) self;
                    Intent intent = (Intent) chain.getArg(0);
                    return handleGuardServiceCommand(svc, intent);
                }
            });

            Method onBind = findMethodBelowService(targetCls, "onBind", Intent.class);
            if (onBind != null) {
                hookExact(m, onBind, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (chain.getThisObject().getClass() == targetCls) return null;
                        return chain.proceed();
                    }
                });
            }

            Method onDestroy = findMethodBelowService(targetCls, "onDestroy");
            if (onDestroy != null) {
                hookExact(m, onDestroy, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object self = chain.getThisObject();
                        if (self.getClass() == targetCls) {
                            if (guardServiceRef.get() == self) {
                                guardServiceRef = new WeakReference<>(null);
                            }
                            fgsRunning = false;
                            log("guard FGS destroyed");
                            return null;
                        }
                        return chain.proceed();
                    }
                });
            }

            log("hooked guard FGS host: " + name + " (dataSync=" + hasDataSync + ")");
            return;
        }
        log("no candidate Foreground Service class resolved");
    }

    private static boolean isSessionKeepAliveAllowed() {
        return enabled && !manualStop && isSupportedWaterDevice()
                && (state == State.STARTING || state == State.RUNNING || state == State.RESTARTING);
    }

    private static int handleGuardServiceCommand(Service svc, Intent intent) {
        if (intent != null && ACTION_GUARD_STOP.equals(intent.getAction())) {
            fgsRunning = false;
            guardServiceRef = new WeakReference<>(null);
            stopForegroundCompat(svc);
            svc.stopSelf();
            log("guard FGS stopped");
            return Service.START_NOT_STICKY;
        }
        if (isSessionKeepAliveAllowed()
                && (isGuarding() || (intent != null && ACTION_GUARD_START.equals(intent.getAction())))) {
            guardServiceRef = new WeakReference<>(svc);
            try {
                Notification notif = buildGuardNotification(svc);
                startForegroundCompat(svc, notif);
                fgsRunning = true;
                log("guard FGS active (notification shown)");
            } catch (Throwable t) {
                log("startForeground failed: " + t);
            }
            return Service.START_NOT_STICKY;
        }
        fgsRunning = false;
        stopForegroundCompat(svc);
        svc.stopSelf();
        return Service.START_NOT_STICKY;
    }

    private static void hookDetailActivity(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_DETAIL);
        if (cls == null) return;

        hookMethod(m, cls, "onCreate", new Class<?>[]{android.os.Bundle.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    final Activity act = (Activity) self;
                    ensurePrefLoaded(act);
                    detailActRef = new WeakReference<>(act);
                    captureHelpersFromDetail(act);
                    scheduleAttachDetailUi(act);
                }
                return res;
            }
        });

        hookMethod(m, cls, "onResume", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    final Activity act = (Activity) self;
                    ensurePrefLoaded(act);
                    detailActRef = new WeakReference<>(act);
                    captureHelpersFromDetail(act);
                    scheduleAttachDetailUi(act);
                }
                return res;
            }
        });

        // D0() launches StateCommonActivity after device start confirms.
        // Avoid stacking a duplicate StateCommonActivity when auto-restart recovers.
        hookMethod(m, cls, "D0", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Activity existing = stateActRef.get();
                if (existing != null && !existing.isFinishing() && !existing.isDestroyed()
                        && enabled && !manualStop) {
                    log("skip duplicate StateCommonActivity launch during guard recovery");
                    refreshStateActivityUi(existing);
                    return null;
                }
                return chain.proceed();
            }
        });

        hookMethod(m, cls, "onDestroy", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = chain.getThisObject();
                if (self == detailActRef.get()) {
                    resetSession("DeviceCommonDetailActivity destroyed");
                    detailActRef = new WeakReference<>(null);
                    ctrlHelperRef = new WeakReference<>(null);
                    stateHelperRef = new WeakReference<>(null);
                    monitorCbRef = new WeakReference<>(null);
                }
                return chain.proceed();
            }
        });
    }

    private static void hookStateActivity(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_STATE);
        if (cls == null) return;

        hookMethod(m, cls, "onCreate", new Class<?>[]{android.os.Bundle.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity act = (Activity) self;
                    ensurePrefLoaded(act);
                    stateActRef = new WeakReference<>(act);
                    scheduleAttachStateUi(act);
                }
                return res;
            }
        });

        hookMethod(m, cls, "onResume", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity act = (Activity) self;
                    ensurePrefLoaded(act);
                    stateActRef = new WeakReference<>(act);
                    scheduleAttachStateUi(act);
                }
                return res;
            }
        });

        // Clicking mClUser ("立即结算") on StateCommonActivity is a manual phone-side stop.
        hookMethod(m, cls, "onClick", new Class<?>[]{View.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object arg = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                if (arg instanceof View) {
                    String idName = getIdName((View) arg);
                    if ("mClUser".equals(idName)) {
                        boolean wasRestarting = (state == State.RESTARTING);
                        onManualStop("StateCommonActivity.onClick(mClUser)");
                        if (wasRestarting) {
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    invokeRaceCleanupEnd();
                                }
                            });
                            Object self = chain.getThisObject();
                            if (self instanceof Activity) {
                                ((Activity) self).finish();
                            }
                            return null;
                        }
                    }
                }
                return chain.proceed();
            }
        });

        // Q() updates StateCommonActivity's status badge and action button from DeviceCacheData.
        // While auto-restarting, keep the button usable as 「立即结算」 so manual stop stays available.
        hookMethod(m, cls, "Q", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity && state == State.RESTARTING && enabled && !manualStop) {
                    keepStateStopButtonVisible((Activity) self);
                }
                refreshAllUi();
                return res;
            }
        });

        hookMethod(m, cls, "onDestroy", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = chain.getThisObject();
                if (self == stateActRef.get()) {
                    stateActRef = new WeakReference<>(null);
                }
                return chain.proceed();
            }
        });
    }

    private static void hookControlHelper(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_CTRL);
        if (cls == null) return;

        // t(Boolean): unified device start entry (covers both online and BLE devices).
        hookMethod(m, cls, "t", new Class<?>[]{Boolean.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = chain.getThisObject();
                captureControlHelper(self);
                if (!inAutoRestartCall) {
                    manualStop = false;
                    restartCount = 0;
                    consecutiveFailures = 0;
                    errorNote = null;
                    state = State.STARTING;
                    log("user initiated device start via DevControlHelper.t");
                    // Start FGS immediately while the Activity is definitely foreground so Android 12+
                    // allows startForegroundService even if the user locks the screen 1s after tapping start.
                    // Guarded by INITIAL_START_TIMEOUT_RUNNABLE so cancelled/aborted starts never linger.
                    if (enabled && isSupportedWaterDevice()) {
                        startKeepAlive(getAnyContext());
                        MAIN.removeCallbacks(INITIAL_START_TIMEOUT_RUNNABLE);
                        MAIN.postDelayed(INITIAL_START_TIMEOUT_RUNNABLE, INITIAL_START_TIMEOUT_MS);
                    }
                    refreshAllUi();
                }
                return chain.proceed();
            }
        });

        // s(Object): deviceStart HTTP succeeded.
        hookMethod(m, cls, "s", new Class<?>[]{Object.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = chain.getThisObject();
                captureControlHelper(self);
                if (manualStop) {
                    log("deviceStart succeeded after manual stop; issuing immediate deviceEnd");
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            invokeRaceCleanupEnd();
                        }
                    });
                    return null;
                }
                log("deviceStart succeeded (state=" + state + ")");
                return chain.proceed();
            }
        });

        // o(Integer, String): deviceStart HTTP failed.
        hookMethod(m, cls, "o", new Class<?>[]{Integer.class, String.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Integer code = (Integer) chain.getArg(0);
                String msg = (String) chain.getArg(1);
                if (state == State.RESTARTING && enabled && !manualStop) {
                    boolean willRetry = onAutoRestartError(code, msg);
                    if (willRetry) {
                        return null;
                    }
                } else if (state == State.STARTING) {
                    resetSession("initial deviceStart failed: " + msg);
                }
                return chain.proceed();
            }
        });

        // i(Boolean) and w(Boolean): phone-side manual stop paths.
        XposedInterface.Hooker stopHook = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!inRaceEndCall) {
                    onManualStop("DevControlHelper." + ((Method) chain.getExecutable()).getName());
                }
                return chain.proceed();
            }
        };
        hookMethod(m, cls, "i", new Class<?>[]{Boolean.class}, stopHook);
        hookMethod(m, cls, "w", new Class<?>[]{Boolean.class}, stopHook);
    }

    private static void hookStateHelper(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_STATE_HELPER);
        if (cls == null) return;

        // s(int times, long timeMillis): monitorDeviceEndForOwner.
        hookMethod(m, cls, "s", new Class<?>[]{int.class, long.class}, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                stateHelperRef = new WeakReference<>(chain.getThisObject());
                if (isGuarding()) {
                    log("upgrading DevStateHelper.s to " + POLL_TIMES + "x" + POLL_INTERVAL_MS + "ms");
                    return chain.proceed(new Object[]{POLL_TIMES, POLL_INTERVAL_MS});
                }
                return chain.proceed();
            }
        });

        // B(int times, long timeMillis, Function1 onEachComplete, Function0 onComplete): startRefreshJob.
        for (Method method : cls.getDeclaredMethods()) {
            if (!"B".equals(method.getName()) || method.getParameterTypes().length != 4) continue;
            try {
                m.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        stateHelperRef = new WeakReference<>(chain.getThisObject());
                        int times = (Integer) chain.getArg(0);
                        long interval = (Long) chain.getArg(1);
                        Object onEach = chain.getArg(2);
                        Object onComplete = chain.getArg(3);

                        if (onComplete == null) {
                            if (onEach != null) {
                                monitorCbRef = new WeakReference<>(onEach);
                            }
                            if (isGuarding() && (times < POLL_TIMES || interval > POLL_INTERVAL_MS)) {
                                lastMonitorStartMs = SystemClock.elapsedRealtime();
                                log("upgrading DevStateHelper.B(" + times + "," + interval + ") -> ("
                                        + POLL_TIMES + "," + POLL_INTERVAL_MS + ")");
                                return chain.proceed(new Object[]{POLL_TIMES, POLL_INTERVAL_MS, onEach, null});
                            }
                            if (times >= POLL_TIMES && interval <= POLL_INTERVAL_MS) {
                                lastMonitorStartMs = SystemClock.elapsedRealtime();
                            }
                        } else if (state == State.RESTARTING && enabled && !manualStop && times == 4 && interval == 3000L) {
                            return chain.proceed(new Object[]{6, 2000L, onEach, onComplete});
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable t) {
                log("hook DevStateHelper.B failed: " + t);
            }
        }

        // l(DeviceDetailData): applies freshly polled status to DeviceCacheData & UI button state.
        for (Method method : cls.getDeclaredMethods()) {
            if (!"l".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
            try {
                m.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        stateHelperRef = new WeakReference<>(chain.getThisObject());
                        Object data = chain.getArg(0);
                        Object res = chain.proceed();
                        onStatusPollApplied(data);
                        return res;
                    }
                });
            } catch (Throwable t) {
                log("hook DevStateHelper.l failed: " + t);
            }
        }

        // n(DeviceDetailData): checks if owner session ended and posts CloseAdPageEvent + DevStateHelper.o().
        for (Method method : cls.getDeclaredMethods()) {
            if (!"n".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
            try {
                m.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (shouldSuppressClose()) {
                            log("suppressed DevStateHelper.n (keeping session open for auto-restart)");
                            return null;
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable t) {
                log("hook DevStateHelper.n failed: " + t);
            }
        }

        // o(): delayed runnable that calls DeviceCommonDetailActivity.finish().
        hookMethod(m, cls, "o", new Class<?>[0], new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (shouldSuppressClose()) {
                    log("suppressed DevStateHelper.o (finish)");
                    return null;
                }
                return chain.proceed();
            }
        });

        // refreshAfterDeviceStart$2.invoke(): fired if confirmation polls after start exhaust without running.
        Class<?> timeoutCls = loadClass(cl, CLS_START_TIMEOUT);
        if (timeoutCls != null) {
            hookMethod(m, timeoutCls, "invoke", new Class<?>[0], new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (state == State.RESTARTING && enabled && !manualStop) {
                        log("refreshAfterDeviceStart$2 fired during auto-restart");
                        if (onAutoRestartError(-2, "设备未响应启动")) {
                            return null;
                        }
                    } else if (state == State.STARTING) {
                        resetSession("initial start poll timed out");
                    }
                    return chain.proceed();
                }
            });
        }
    }

    private static void hookDeviceViewModel(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_VM);
        if (cls == null) return;
        for (Method method : cls.getDeclaredMethods()) {
            if (!"deviceEnd".equals(method.getName())) continue;
            try {
                m.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!inRaceEndCall) {
                            onManualStop("DeviceViewModel.deviceEnd");
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable t) {
                log("hook DeviceViewModel.deviceEnd failed: " + t);
            }
        }
    }

    private static void hookDeviceCache(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_CACHE);
        if (cls == null) return;
        for (Method method : cls.getDeclaredMethods()) {
            if ("setDeviceUseBtnState".equals(method.getName()) && method.getParameterTypes().length == 1) {
                try {
                    m.hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object btnState = chain.getArg(0);
                            if (btnState != null) {
                                Object nameEnum = callNoArg(btnState, "getName");
                                if (nameEnum != null) {
                                    String name = nameEnum.toString();
                                    lastBtnStatus = name;
                                    if (state == State.IDLE && !manualStop
                                            && ("SETTLE_IMMEDIATELY".equals(name) || "RUNNING".equals(name))) {
                                        state = State.RUNNING;
                                        lastPollMs = SystemClock.elapsedRealtime();
                                        log("detected already-running session from button state: " + name);
                                        if (enabled && isSupportedWaterDevice()) {
                                            startKeepAlive(getAnyContext());
                                            startWatchdog();
                                        }
                                        refreshAllUi();
                                    }
                                }
                            }
                            return chain.proceed();
                        }
                    });
                } catch (Throwable t) {
                    log("hook DeviceCacheData.setDeviceUseBtnState failed: " + t);
                }
            } else if ("setDeviceInfo".equals(method.getName()) && method.getParameterTypes().length == 1) {
                try {
                    m.hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object info = chain.getArg(0);
                            if (info != null) {
                                Object nt = callNoArg(info, "getNtype");
                                if (nt instanceof Integer && (Integer) nt >= 0) {
                                    currentDeviceNtype = (Integer) nt;
                                    log("device ntype=" + currentDeviceNtype + " supported=" + isSupportedWaterDevice());
                                }
                            }
                            return chain.proceed();
                        }
                    });
                } catch (Throwable t) {
                    log("hook DeviceCacheData.setDeviceInfo failed: " + t);
                }
            }
        }
    }

    private static void hookEventBus(XposedModule m, ClassLoader cl) {
        Class<?> cls = loadClass(cl, CLS_BUS);
        if (cls == null) return;
        for (Method method : cls.getDeclaredMethods()) {
            if (!"e".equals(method.getName()) || method.getParameterTypes().length != 2) continue;
            try {
                m.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object ev = chain.getArg(1);
                        if (ev != null && ev.toString().startsWith("CloseAdPageEvent") && shouldSuppressClose()) {
                            log("suppressed EventBus CloseAdPageEvent during guard");
                            return null;
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable t) {
                log("hook EventBus.e failed: " + t);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core State Machine
    // -------------------------------------------------------------------------

    private static void onStatusPollApplied(Object deviceDetailData) {
        lastPollMs = SystemClock.elapsedRealtime();
        String btn = lastBtnStatus;
        boolean runningNow = "SETTLE_IMMEDIATELY".equals(btn) || "RUNNING".equals(btn);

        if (runningNow) {
            if (manualStop) return;
            boolean wasRestarting = (state == State.RESTARTING);
            boolean transitioned = (state != State.RUNNING);
            state = State.RUNNING;
            consecutiveFailures = 0;
            errorNote = null;
            MAIN.removeCallbacks(INITIAL_START_TIMEOUT_RUNNABLE);
            MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);

            if (transitioned) {
                log("session RUNNING confirmed (wasRestarting=" + wasRestarting + ", restarts=" + restartCount + ")");
                if (enabled && isSupportedWaterDevice()) {
                    startKeepAlive(getAnyContext());
                    startWatchdog();
                    final long confirmTime = SystemClock.elapsedRealtime();
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (state == State.RUNNING && enabled && !manualStop && isSupportedWaterDevice()
                                    && lastMonitorStartMs < confirmTime) {
                                log("re-arming continuous 2s monitor after RUNNING confirmation");
                                restartMonitorPolling(POLL_TIMES, POLL_INTERVAL_MS);
                            }
                        }
                    }, wasRestarting ? 600L : 3500L);
                }
                if (wasRestarting) {
                    Activity sa = stateActRef.get();
                    if (sa != null && !sa.isFinishing() && !sa.isDestroyed()) {
                        refreshStateActivityUi(sa);
                    }
                }
                updateGuardNotification();
                refreshAllUi();
            }
            return;
        }

        // Device is NOT running according to the server.
        if (state == State.RUNNING && enabled && !manualStop && isSupportedWaterDevice()) {
            int devStatus = extractDeviceStatus(deviceDetailData);
            log("machine-side stop detected! btn=" + btn + " devStatus=" + devStatus);

            state = State.RESTARTING;
            errorNote = null;
            MAIN.removeCallbacks(WATCHDOG_RUNNABLE);
            MAIN.removeCallbacks(RESTART_RUNNABLE);
            MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);

            Activity sa = stateActRef.get();
            if (sa != null && !sa.isFinishing() && !sa.isDestroyed()) {
                keepStateStopButtonVisible(sa);
            }
            updateGuardNotification();
            refreshAllUi();
            MAIN.postDelayed(RESTART_RUNNABLE, RESTART_DELAY_MS);
        } else if (state == State.RUNNING && !enabled) {
            resetSession("machine stop while guard disabled");
        }
    }

    private static void performAutoRestart() {
        if (!enabled || manualStop || state != State.RESTARTING) return;

        long now = SystemClock.elapsedRealtime();
        long wait = RESTART_COOLDOWN_MS - (now - lastRestartAttemptMs);
        if (wait > 0) {
            MAIN.postDelayed(RESTART_RUNNABLE, wait);
            return;
        }

        Activity detail = detailActRef.get();
        Object ctrl = ctrlHelperRef.get();
        if (detail == null || detail.isFinishing() || detail.isDestroyed() || ctrl == null) {
            log("cannot auto-restart: detail activity or DevControlHelper unavailable");
            resetSession("controller unavailable");
            return;
        }

        lastRestartAttemptMs = now;
        restartCount++;
        log("triggering auto-restart #" + restartCount + " via DevControlHelper.t(false)");
        updateGuardNotification();
        refreshAllUi();

        MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);
        MAIN.postDelayed(RESTART_TIMEOUT_RUNNABLE, RESTART_TIMEOUT_MS);

        try {
            Method startMethod = ctrl.getClass().getDeclaredMethod("t", Boolean.class);
            startMethod.setAccessible(true);
            inAutoRestartCall = true;
            try {
                startMethod.invoke(ctrl, Boolean.FALSE);
            } finally {
                inAutoRestartCall = false;
            }
        } catch (Throwable t) {
            log("DevControlHelper.t(false) threw: " + t);
            onAutoRestartError(-3, "调用开水失败");
        }
    }

    /** Returns true if a retry was scheduled; false if giving up. */
    private static boolean onAutoRestartError(Integer code, String msg) {
        MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);
        if (!enabled || manualStop || state != State.RESTARTING) return false;

        consecutiveFailures++;
        String text = msg == null ? "" : msg;
        boolean fatal = text.contains("余额") || text.contains("欠费") || text.contains("禁用")
                || text.contains("锁定") || text.contains("占用") || text.contains("他人")
                || text.contains("未登录") || text.contains("登录失效");

        log("auto-restart error #" + consecutiveFailures + " code=" + code + " msg=" + text + " fatal=" + fatal);

        if (fatal || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            errorNote = text.isEmpty() ? "续水失败，已停止保护" : ("已停止续水：" + text);
            state = State.IDLE;
            MAIN.removeCallbacks(RESTART_RUNNABLE);
            MAIN.removeCallbacks(WATCHDOG_RUNNABLE);
            stopKeepAlive(getAnyContext());
            refreshAllUi();
            showToast(errorNote);
            return false;
        }

        errorNote = "续水重试中(" + consecutiveFailures + "/" + MAX_CONSECUTIVE_FAILURES + ")"
                + (text.isEmpty() ? "" : "：" + text);
        updateGuardNotification();
        refreshAllUi();
        MAIN.removeCallbacks(RESTART_RUNNABLE);
        MAIN.postDelayed(RESTART_RUNNABLE, RETRY_DELAY_MS);
        return true;
    }

    private static void onManualStop(String source) {
        log("manual phone-side stop detected from " + source + " (prevState=" + state + ")");
        manualStop = true;
        state = State.IDLE;
        errorNote = null;
        consecutiveFailures = 0;
        MAIN.removeCallbacks(INITIAL_START_TIMEOUT_RUNNABLE);
        MAIN.removeCallbacks(RESTART_RUNNABLE);
        MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);
        MAIN.removeCallbacks(WATCHDOG_RUNNABLE);
        stopKeepAlive(getAnyContext());
        refreshAllUi();
    }

    private static void resetSession(String reason) {
        log("reset session: " + reason);
        state = State.IDLE;
        manualStop = false;
        errorNote = null;
        consecutiveFailures = 0;
        MAIN.removeCallbacks(INITIAL_START_TIMEOUT_RUNNABLE);
        MAIN.removeCallbacks(RESTART_RUNNABLE);
        MAIN.removeCallbacks(RESTART_TIMEOUT_RUNNABLE);
        MAIN.removeCallbacks(WATCHDOG_RUNNABLE);
        stopKeepAlive(getAnyContext());
        refreshAllUi();
    }

    private static void invokeRaceCleanupEnd() {
        Object ctrl = ctrlHelperRef.get();
        if (ctrl == null) return;
        try {
            Method endMethod = ctrl.getClass().getDeclaredMethod("i", Boolean.class);
            endMethod.setAccessible(true);
            inRaceEndCall = true;
            try {
                endMethod.invoke(ctrl, Boolean.FALSE);
            } finally {
                inRaceEndCall = false;
            }
        } catch (Throwable t) {
            log("race cleanup deviceEnd failed: " + t);
        }
    }

    private static void startWatchdog() {
        MAIN.removeCallbacks(WATCHDOG_RUNNABLE);
        MAIN.postDelayed(WATCHDOG_RUNNABLE, WATCHDOG_INTERVAL_MS);
    }

    private static void restartMonitorPolling(final int times, final long intervalMs) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Object sh = stateHelperRef.get();
                if (sh == null) return;
                Object cb = monitorCbRef.get();
                if (cb != null) {
                    for (Method m : sh.getClass().getDeclaredMethods()) {
                        if ("B".equals(m.getName()) && m.getParameterTypes().length == 4) {
                            try {
                                m.setAccessible(true);
                                m.invoke(sh, times, intervalMs, cb, null);
                                return;
                            } catch (Throwable t) {
                                log("invoke DevStateHelper.B failed: " + t);
                            }
                        }
                    }
                }
                try {
                    Method s = sh.getClass().getDeclaredMethod("s", int.class, long.class);
                    s.setAccessible(true);
                    s.invoke(sh, times, intervalMs);
                } catch (Throwable t) {
                    log("invoke DevStateHelper.s failed: " + t);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Keep-Alive: Foreground Service + WakeLock + WifiLock
    // -------------------------------------------------------------------------

    private static void startKeepAlive(Context ctx) {
        if (!isSessionKeepAliveAllowed()) return;
        acquireLocks(ctx);
        startSilentAudio();
        startGuardService(ctx);
    }

    private static void stopKeepAlive(Context ctx) {
        stopGuardService(ctx);
        stopSilentAudio();
        releaseLocks();
    }

    /**
     * Plays an inaudible all-zero PCM loop without requesting audio focus (so music/podcasts are
     * unaffected). Aggressive OEM freezers such as Xiaomi HyperOS GreezeManager still freeze
     * third-party dataSync foreground services on screen-off, but exempt processes with active
     * audio playback.
     */
    private static void startSilentAudio() {
        synchronized (LOCK) {
            if (silentAudioTrack != null) return;
            try {
                int sampleRate = 8000;
                int minBuf = AudioTrack.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int frames = Math.max(sampleRate, (Math.max(minBuf, 0) + 1) / 2);
                short[] silence = new short[frames];
                int bufBytes = frames * 2;
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(silence, 0, silence.length);
                track.setLoopPoints(0, frames, -1);
                track.setVolume(0.01f);
                track.play();
                silentAudioTrack = track;
                log("started silent AudioTrack keep-alive");
            } catch (Throwable t) {
                log("startSilentAudio failed: " + t);
            }
        }
    }

    private static void stopSilentAudio() {
        synchronized (LOCK) {
            if (silentAudioTrack == null) return;
            try {
                silentAudioTrack.pause();
                silentAudioTrack.flush();
                silentAudioTrack.stop();
            } catch (Throwable ignored) {
            }
            try {
                silentAudioTrack.release();
                log("stopped silent AudioTrack keep-alive");
            } catch (Throwable ignored) {
            }
            silentAudioTrack = null;
        }
    }

    private static void startGuardService(Context ctx) {
        if (fgsServiceClass == null || ctx == null) return;
        if (fgsRunning) {
            updateGuardNotification();
            return;
        }
        try {
            Context app = ctx.getApplicationContext();
            Intent intent = new Intent(app, fgsServiceClass);
            intent.setAction(ACTION_GUARD_START);
            intent.putExtra(EXTRA_GUARD_FGS, true);
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
            log("requested startForegroundService(" + fgsServiceClass.getSimpleName() + ")");
        } catch (Throwable t) {
            log("startGuardService failed: " + t);
        }
    }

    private static void stopGuardService(Context ctx) {
        Service svc = guardServiceRef.get();
        if (svc != null) {
            try {
                stopForegroundCompat(svc);
                svc.stopSelf();
                guardServiceRef = new WeakReference<>(null);
                fgsRunning = false;
                log("stopped guard FGS directly");
                return;
            } catch (Throwable ignored) {
            }
        }
        if (!fgsRunning && fgsServiceClass == null) return;
        fgsRunning = false;
        if (fgsServiceClass == null || ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            Intent intent = new Intent(app, fgsServiceClass);
            app.stopService(intent);
        } catch (Throwable ignored) {
        }
    }

    private static void updateGuardNotification() {
        if (!fgsRunning) return;
        Context ctx = guardServiceRef.get();
        if (ctx == null) ctx = getAnyContext();
        if (ctx == null) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildGuardNotification(ctx.getApplicationContext()));
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static Notification buildGuardNotification(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        NOTIF_CHANNEL_ID, "用水防误触守护", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("开启用水防误触时保持后台监测，避免息屏断网或被系统冻结");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }

        String title;
        String text;
        if (state == State.RESTARTING) {
            title = "798 防误触 · 正在自动续水…";
            text = "检测到机器端停水，正在重新下发开水指令";
        } else if (restartCount > 0) {
            title = "798 防误触守护中（已续水 " + restartCount + " 次）";
            text = "仅手机端「立即结算」关水生效，点此返回设备页";
        } else {
            title = "798 防误触守护中";
            text = "持续监测水控状态，机器误触停水将自动续开";
        }

        Intent openIntent = null;
        Activity target = stateActRef.get();
        if (target == null || target.isFinishing() || target.isDestroyed()) {
            target = detailActRef.get();
        }
        if (target != null && !target.isFinishing() && !target.isDestroyed()) {
            openIntent = new Intent(app, target.getClass());
            openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            openIntent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = openIntent == null ? null : PendingIntent.getActivity(app, 0, openIntent, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, NOTIF_CHANNEL_ID)
                : new Notification.Builder(app);
        int icon = app.getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.ic_lock_idle_lock;
        b.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (pi != null) b.setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private static void startForegroundCompat(Service svc, Notification notif) {
        if (Build.VERSION.SDK_INT >= 29 && fgsDeclaresDataSync) {
            svc.startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            svc.startForeground(NOTIF_ID, notif);
        }
    }

    @SuppressWarnings("deprecation")
    private static void stopForegroundCompat(Service svc) {
        if (Build.VERSION.SDK_INT >= 24) {
            svc.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            svc.stopForeground(true);
        }
    }

    @SuppressWarnings("deprecation")
    private static void acquireLocks(Context ctx) {
        synchronized (LOCK) {
            if (ctx == null) return;
            Context app = ctx.getApplicationContext();
            try {
                if (wakeLock == null || !wakeLock.isHeld()) {
                    PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "n798:AntiTouchGuard");
                        wakeLock.setReferenceCounted(false);
                        wakeLock.acquire(WAKELOCK_MAX_MS);
                        log("acquired partial WakeLock");
                    }
                }
            } catch (Throwable t) {
                log("acquire WakeLock failed: " + t);
            }
            try {
                if (wifiLock == null || !wifiLock.isHeld()) {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        int mode = Build.VERSION.SDK_INT >= 29
                                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                        wifiLock = wm.createWifiLock(mode, "n798:AntiTouchWifi");
                        wifiLock.setReferenceCounted(false);
                        wifiLock.acquire();
                        log("acquired WifiLock");
                    }
                }
            } catch (Throwable t) {
                log("acquire WifiLock failed: " + t);
            }
        }
    }

    private static void releaseLocks() {
        synchronized (LOCK) {
            if (wakeLock != null) {
                try {
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                        log("released partial WakeLock");
                    }
                } catch (Throwable ignored) {
                }
                wakeLock = null;
            }
            if (wifiLock != null) {
                try {
                    if (wifiLock.isHeld()) {
                        wifiLock.release();
                        log("released WifiLock");
                    }
                } catch (Throwable ignored) {
                }
                wifiLock = null;
            }
        }
    }

    private static Context getAnyContext() {
        Activity a = stateActRef.get();
        if (a != null && !a.isDestroyed()) return a;
        a = detailActRef.get();
        if (a != null && !a.isDestroyed()) return a;
        Service s = guardServiceRef.get();
        return s;
    }

    // -------------------------------------------------------------------------
    // UI Injection & Updates
    // -------------------------------------------------------------------------

    private static void scheduleAttachDetailUi(final Activity act) {
        final View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (decor == null) return;
        decor.postDelayed(new Runnable() {
            private int attempt;

            @Override
            public void run() {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (attachOrUpdateDetailUi(act)) return;
                if (++attempt < 15) decor.postDelayed(this, 200);
            }
        }, 120);
    }

    private static boolean attachOrUpdateDetailUi(final Activity act) {
        if (!isSupportedWaterDevice()) return true;
        int useId = act.getResources().getIdentifier("mDeviceDetailUse", "id", act.getPackageName());
        if (useId == 0) return false;
        View useView = act.findViewById(useId);
        if (!(useView instanceof LinearLayout)) return false;
        LinearLayout container = (LinearLayout) useView;

        View existing = container.findViewWithTag(TAG_DETAIL_BAR);
        if (existing != null) {
            updateDetailBarView(existing);
            return true;
        }

        LinearLayout bar = buildDetailBar(act);
        container.addView(bar, 0);
        updateDetailBarView(bar);
        log("attached anti-touch bar to DeviceCommonDetailActivity");
        return true;
    }

    private static LinearLayout buildDetailBar(final Activity act) {
        LinearLayout wrapper = new LinearLayout(act);
        wrapper.setTag(TAG_DETAIL_BAR);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(Color.WHITE);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(act);
        divider.setBackgroundColor(Color.parseColor("#EEF1F6"));
        wrapper.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEnabled(act, !enabled);
            }
        });

        LinearLayout textCol = new LinearLayout(act);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.rightMargin = dp(act, 12);
        textCol.setLayoutParams(textLp);

        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(act);
        title.setText("防误触");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#333333"));
        titleRow.addView(title);

        TextView badge = new TextView(act);
        badge.setTag("badge");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        badge.setPadding(dp(act, 6), dp(act, 1), dp(act, 6), dp(act, 1));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(act, 6);
        titleRow.addView(badge, badgeLp);

        TextView sub = new TextView(act);
        sub.setTag("sub");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setTextColor(Color.parseColor("#8A9099"));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(act, 2);
        textCol.addView(titleRow);
        textCol.addView(sub, subLp);

        View toggle = buildTogglePill(act);
        toggle.setTag("toggle");

        row.addView(textCol);
        row.addView(toggle);
        wrapper.addView(row);
        return wrapper;
    }

    private static void updateDetailBarView(View bar) {
        Context ctx = bar.getContext();
        TextView badge = bar.findViewWithTag("badge");
        TextView sub = bar.findViewWithTag("sub");
        View toggle = bar.findViewWithTag("toggle");

        updateTogglePill(ctx, toggle, enabled);
        if (badge != null) {
            if (enabled) {
                badge.setVisibility(View.VISIBLE);
                boolean active = (state == State.RUNNING || state == State.RESTARTING) && !manualStop;
                badge.setText(active ? "守护中" : "已开启");
                badge.setTextColor(Color.parseColor(active ? "#16A34A" : "#2B85FF"));
                badge.setBackground(roundedRect(ctx, active ? "#EAF9F0" : "#EBF3FF", 99, 0, null));
            } else {
                badge.setVisibility(View.GONE);
            }
        }
        if (sub != null) {
            sub.setText(buildDetailSubtitle());
            sub.setTextColor(Color.parseColor(errorNote != null ? "#DC2626" : "#8A9099"));
        }
    }

    private static void scheduleAttachStateUi(final Activity act) {
        final View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (decor == null) return;
        decor.postDelayed(new Runnable() {
            private int attempt;

            @Override
            public void run() {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (attachOrUpdateStateUi(act)) return;
                if (++attempt < 15) decor.postDelayed(this, 200);
            }
        }, 120);
    }

    private static boolean attachOrUpdateStateUi(final Activity act) {
        if (!isSupportedWaterDevice()) return true;
        int statusId = act.getResources().getIdentifier("mDeviceStatusLl", "id", act.getPackageName());
        if (statusId == 0) return false;
        View statusView = act.findViewById(statusId);
        if (statusView == null || !(statusView.getParent() instanceof LinearLayout)) return false;
        LinearLayout container = (LinearLayout) statusView.getParent();

        View existing = container.findViewWithTag(TAG_STATE_CARD);
        if (existing != null) {
            updateStateCardView(existing);
            return true;
        }

        LinearLayout card = buildStateCard(act);
        int insertIndex = Math.min(3, container.getChildCount());
        container.addView(card, insertIndex);
        updateStateCardView(card);
        log("attached anti-touch card to StateCommonActivity at index " + insertIndex);
        return true;
    }

    private static LinearLayout buildStateCard(final Activity act) {
        LinearLayout card = new LinearLayout(act);
        card.setTag(TAG_STATE_CARD);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEnabled(act, !enabled);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(act, 16);
        lp.rightMargin = dp(act, 16);
        lp.topMargin = dp(act, 18);
        card.setLayoutParams(lp);

        LinearLayout topRow = new LinearLayout(act);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(act);
        title.setText("防误触保护");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#333333"));
        topRow.addView(title);

        TextView badge = new TextView(act);
        badge.setTag("badge");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setPadding(dp(act, 7), dp(act, 2), dp(act, 7), dp(act, 2));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(act, 8);
        topRow.addView(badge, badgeLp);

        View spacer = new View(act);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        View toggle = buildTogglePill(act);
        toggle.setTag("toggle");
        topRow.addView(toggle);

        TextView sub = new TextView(act);
        sub.setTag("sub");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sub.setLineSpacing(dp(act, 2), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(act, 6);

        card.addView(topRow);
        card.addView(sub, subLp);
        return card;
    }

    private static void updateStateCardView(View card) {
        Context ctx = card.getContext();
        TextView badge = card.findViewWithTag("badge");
        TextView sub = card.findViewWithTag("sub");
        View toggle = card.findViewWithTag("toggle");

        updateTogglePill(ctx, toggle, enabled);

        if (errorNote != null) {
            card.setBackground(roundedRect(ctx, "#FEF2F2", 12, 1, "#FECACA"));
        } else if (enabled && state == State.RESTARTING) {
            card.setBackground(roundedRect(ctx, "#FFFBEB", 12, 1, "#FDE68A"));
        } else if (enabled) {
            card.setBackground(roundedRect(ctx, "#F3F8FF", 12, 1, "#C7DFFF"));
        } else {
            card.setBackground(roundedRect(ctx, "#FFFFFF", 12, 1, "#E8EEF5"));
        }

        if (badge != null) {
            if (!enabled) {
                badge.setText("未开启");
                badge.setTextColor(Color.parseColor("#8A9099"));
                badge.setBackground(roundedRect(ctx, "#F1F3F7", 99, 0, null));
            } else if (errorNote != null) {
                badge.setText("异常");
                badge.setTextColor(Color.parseColor("#DC2626"));
                badge.setBackground(roundedRect(ctx, "#FEE2E2", 99, 0, null));
            } else if (state == State.RESTARTING) {
                badge.setText("续水中");
                badge.setTextColor(Color.parseColor("#D97706"));
                badge.setBackground(roundedRect(ctx, "#FEF3C7", 99, 0, null));
            } else {
                badge.setText("守护中");
                badge.setTextColor(Color.parseColor("#16A34A"));
                badge.setBackground(roundedRect(ctx, "#DCFCE7", 99, 0, null));
            }
        }

        if (sub != null) {
            sub.setText(buildStateSubtitle());
            if (errorNote != null) {
                sub.setTextColor(Color.parseColor("#DC2626"));
            } else if (enabled && state == State.RESTARTING) {
                sub.setTextColor(Color.parseColor("#B45309"));
            } else if (enabled) {
                sub.setTextColor(Color.parseColor("#2B6CB0"));
            } else {
                sub.setTextColor(Color.parseColor("#8A9099"));
            }
        }
    }

    private static String buildDetailSubtitle() {
        if (errorNote != null) return errorNote;
        if (!enabled) return "开启后仅手机端关水生效，机器误触停水自动续开";
        if (state == State.RESTARTING) return "检测到机器停水，正在自动重新开水…";
        if (state == State.RUNNING && restartCount > 0) {
            return "守护中 · 已自动续水 " + restartCount + " 次，点击「立即结算」停止";
        }
        if (state == State.RUNNING) return "守护中 · 机器端停止计费将自动重新开水";
        return "已开启 · 手机开水后持续守护，直到手动关水或关闭开关";
    }

    private static String buildStateSubtitle() {
        if (errorNote != null) return errorNote;
        if (!enabled) return "开启后，即使机器端误触停止计费也会自动续水，仅手机端点击「立即结算」才会关水";
        if (state == State.RESTARTING) return "检测到机器停止计费，正在自动重新开水…（点击上方「立即结算」可手动关水）";
        if (restartCount > 0) return "已自动续水 " + restartCount + " 次 · 除非在手机端点击「立即结算」，否则将保持持续用水";
        return "除非在手机端点击「立即结算」，否则哪怕在机器上停止了计费也会自动续水";
    }

    private static View buildTogglePill(Context ctx) {
        FrameLayout track = new FrameLayout(ctx);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 25)));
        track.setPadding(dp(ctx, 2.5f), dp(ctx, 2.5f), dp(ctx, 2.5f), dp(ctx, 2.5f));

        View knob = new View(ctx);
        knob.setTag("knob");
        knob.setBackground(roundedRect(ctx, "#FFFFFF", 99, 0, null));
        FrameLayout.LayoutParams knobLp = new FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        knobLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        track.addView(knob, knobLp);
        return track;
    }

    private static void updateTogglePill(Context ctx, View track, boolean checked) {
        if (!(track instanceof FrameLayout)) return;
        track.setBackground(roundedRect(ctx, checked ? "#2B85FF" : "#D4D9E2", 99, 0, null));
        View knob = track.findViewWithTag("knob");
        if (knob != null && knob.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) knob.getLayoutParams();
            lp.gravity = (checked ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
            knob.setLayoutParams(lp);
        }
    }

    private static void keepStateStopButtonVisible(final Activity act) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (state != State.RESTARTING || !enabled || manualStop) return;
                try {
                    String pkg = act.getPackageName();
                    int btnId = act.getResources().getIdentifier("mClUser", "id", pkg);
                    int tvId = act.getResources().getIdentifier("mTvUser", "id", pkg);
                    int statusTvId = act.getResources().getIdentifier("mTvStatus", "id", pkg);
                    if (btnId != 0) {
                        View btn = act.findViewById(btnId);
                        if (btn != null) {
                            btn.setVisibility(View.VISIBLE);
                            btn.setEnabled(true);
                        }
                    }
                    if (tvId != 0) {
                        View tv = act.findViewById(tvId);
                        if (tv instanceof TextView) {
                            ((TextView) tv).setText("立即结算");
                        }
                    }
                    if (statusTvId != 0) {
                        View stv = act.findViewById(statusTvId);
                        if (stv instanceof TextView) {
                            ((TextView) stv).setText("自动续水中");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void refreshStateActivityUi(final Activity act) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (act.isFinishing() || act.isDestroyed()) return;
                callNoArg(act, "Q");
                attachOrUpdateStateUi(act);
            }
        });
    }

    private static void refreshAllUi() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity detail = detailActRef.get();
                if (detail != null && !detail.isFinishing() && !detail.isDestroyed()) {
                    attachOrUpdateDetailUi(detail);
                }
                Activity sa = stateActRef.get();
                if (sa != null && !sa.isFinishing() && !sa.isDestroyed()) {
                    attachOrUpdateStateUi(sa);
                }
            }
        });
    }

    private static void showToast(final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Context ctx = getAnyContext();
                if (ctx == null) return;
                try {
                    Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Reflection & Helper Utilities
    // -------------------------------------------------------------------------

    private static void captureHelpersFromDetail(Activity act) {
        try {
            Object ctrl = callNoArg(act, "c0");
            if (ctrl != null) ctrlHelperRef = new WeakReference<>(ctrl);
            Object sh = callNoArg(act, "d0");
            if (sh != null) stateHelperRef = new WeakReference<>(sh);
        } catch (Throwable ignored) {
        }
    }

    private static void captureControlHelper(Object ctrl) {
        if (ctrl == null) return;
        ctrlHelperRef = new WeakReference<>(ctrl);
        try {
            Field aField = ctrl.getClass().getDeclaredField("a");
            aField.setAccessible(true);
            Object baseAct = aField.get(ctrl);
            if (baseAct instanceof Activity) {
                detailActRef = new WeakReference<>((Activity) baseAct);
                Object sh = callNoArg(baseAct, "d0");
                if (sh != null) stateHelperRef = new WeakReference<>(sh);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int extractDeviceStatus(Object deviceDetailData) {
        if (deviceDetailData == null) return -1;
        Object dev = callNoArg(deviceDetailData, "getDevice");
        if (dev == null) return -1;
        Object st = callNoArg(dev, "getStatus");
        return st instanceof Integer ? (Integer) st : -1;
    }

    private static Class<?> loadClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethodBelowService(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Service.class && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static void hookExact(XposedModule m, Method method, XposedInterface.Hooker hooker) {
        try {
            m.hook(method).intercept(hooker);
        } catch (Throwable t) {
            log("hook " + method.getDeclaringClass().getSimpleName() + "#" + method.getName() + " failed: " + t);
        }
    }

    private static void hookMethod(XposedModule m, Class<?> cls, String name, Class<?>[] params,
                                   XposedInterface.Hooker hooker) {
        try {
            Method method = cls.getDeclaredMethod(name, params);
            m.hook(method).intercept(hooker);
        } catch (Throwable t) {
            log("hook " + cls.getSimpleName() + "#" + name + " failed: " + t);
        }
    }

    private static Object callNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getIdName(View v) {
        try {
            if (v.getId() != View.NO_ID) {
                return v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static GradientDrawable roundedRect(Context ctx, String fillHex, float radiusDp,
                                                float strokeDp, String strokeHex) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, radiusDp));
        d.setColor(Color.parseColor(fillHex));
        if (strokeDp > 0 && strokeHex != null) {
            d.setStroke(dp(ctx, strokeDp), Color.parseColor(strokeHex));
        }
        return d;
    }

    private static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
