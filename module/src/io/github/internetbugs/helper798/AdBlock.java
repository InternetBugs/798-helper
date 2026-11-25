package io.github.internetbugs.helper798;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Ad removal for com.cloudora.android.
 *
 * The host renders every ad through com.ilife.lib.common.ad.util.*, one helper per ad format.
 * Each helper exposes a single "load and show" entry (Kotlin, so also a $default synthetic):
 *
 *   BannerAdHelper.loadAndShow()                 feed banner on the home page
 *   FeedExpressAdHelper.loadAndShow()            in-feed native ad
 *   MallAdHelper.loadAndShow()                   mall ad strip
 *   MediationAdHelper.loadAndShow(String)        reward video / interstitial
 *   SplashAdHelper.loadAndShow(SplashAdListener) cold-start splash
 *   FullAdHelper.load(...) / show(Activity)      full-screen ad
 *
 * We no-op those, then fire the helper's own failure callback so the caller's UI moves on
 * instead of waiting for an ad that will never arrive. AdManager.isShowAd is a policy gate
 * that the callers consult first; forcing it false keeps the ad containers unallocated.
 */
final class AdBlock {

    private static final String TAG = "N798";
    private static final String PKG = "com.ilife.lib.common.ad.util.";

    private AdBlock() {
    }

    /**
     * The packer decrypts its dex after onPackageLoaded, so the ad classes do not resolve yet.
     * Poll until they do, then hook once.
     */
    static void install(final XposedModule m, final ClassLoader cl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 600; i++) {
                    if (resolvable(cl, "com.ilife.lib.common.ad.AdManager")
                            || resolvable(cl, PKG + "SplashAdHelper")) {
                        m.log(4, TAG, "ad classes ready after " + (i * 100) + "ms");
                        hookAll(m, cl);
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                m.log(4, TAG, "ad classes never appeared");
            }
        }, "n798-adblock").start();
    }

    private static boolean resolvable(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void hookAll(XposedModule m, ClassLoader cl) {
        // Splash first: it is what the user sees on every cold start.
        block(m, cl, PKG + "SplashAdHelper", "loadAndShow", new Notify() {
            @Override
            public void onBlocked(Object self, Object[] args) {
                if (args.length > 0 && args[0] != null) {
                    call(args[0], "onAdLoadFail");
                }
            }
        });

        block(m, cl, PKG + "BannerAdHelper", "loadAndShow", null);
        block(m, cl, PKG + "FeedExpressAdHelper", "loadAndShow", null);
        block(m, cl, PKG + "MallAdHelper", "loadAndShow", null);

        block(m, cl, PKG + "MediationAdHelper", "loadAndShow", new Notify() {
            @Override
            public void onBlocked(Object self, Object[] args) {
                Object listener = call(self, "getListener");
                if (listener != null) call(listener, "onAdError");
            }
        });

        block(m, cl, PKG + "FullAdHelper", "load", null);
        block(m, cl, PKG + "FullAdHelper", "show", null);
        block(m, cl, PKG + "FullAdHelper", "showAd", null);

        // Policy gate: callers ask this before building any ad container.
        block(m, cl, "com.ilife.lib.common.ad.AdManager", "isShowAd", null, false);
        block(m, cl, "com.ilife.lib.common.ad.AdManager", "shouldRecommend", null, false);
    }

    /** Called after an ad entry point was suppressed. */
    interface Notify {
        void onBlocked(Object self, Object[] args);
    }

    private static void block(XposedModule m, ClassLoader cl, String className, String methodName,
                              Notify notify) {
        block(m, cl, className, methodName, notify, true);
    }

    /**
     * Hooks every method named {@code methodName} (and its Kotlin {@code $default} synthetic) on
     * {@code className}. When {@code proceed} is false the original body is skipped and the hook
     * returns null, which is what a boolean gate needs.
     */
    private static void block(final XposedModule m, ClassLoader cl, final String className,
                              final String methodName, final Notify notify, final boolean proceed) {
        Class<?> c;
        try {
            c = Class.forName(className, false, cl);
        } catch (Throwable t) {
            m.log(4, TAG, "no " + className + " (" + t + ")");
            return;
        }
        int hooked = 0;
        for (final Method method : c.getDeclaredMethods()) {
            String n = method.getName();
            if (!n.equals(methodName) && !n.equals(methodName + "$default")) continue;
            try {
                m.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object self = chain.getThisObject();
                        if (notify != null) {
                            try {
                                notify.onBlocked(self, chain.getArgs().toArray());
                            } catch (Throwable ignored) {
                            }
                        }
                        if (!proceed) return Boolean.FALSE;
                        return null;
                    }
                });
                hooked++;
            } catch (Throwable t) {
                m.log(4, TAG, "hook " + className + "#" + n + " failed: " + t);
            }
        }
        m.log(4, TAG, "blocked " + className + "#" + methodName + " x" + hooked);
    }

    private static Object call(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
