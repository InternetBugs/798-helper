package io.github.internetbugs.helper798;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Removes the 积分 tab from the home bottom bar.
 *
 * com.cloudora.android.MainActivity builds the bar as a plain LinearLayout (id mLLBottomNav)
 * holding one clickable LinearLayout per tab: mLlHome / mLlScore / mLlService / mLlMe, each a
 * fixed 300 px wide on a 1200 px screen. We drop mLlScore and give the survivors weight 1 so
 * the remaining three split the bar evenly.
 *
 * Ids are resolved by name at runtime, so a resource renumbering in an app update does not
 * silently break the hook.
 */
final class NavBlock {

    private static final String TAG = "N798";
    private static final String ACTIVITY = "com.cloudora.android.MainActivity";
    private static final String NAV_ID = "mLLBottomNav";
    private static final String SCORE_ID = "mLlScore";

    private NavBlock() {
    }

    /** MainActivity is a packed class: it does not resolve until the packer has run. */
    static void install(final XposedModule m, final ClassLoader cl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 600; i++) {
                    try {
                        Class.forName(ACTIVITY, false, cl);
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    m.log(4, TAG, "nav classes ready after " + (i * 100) + "ms");
                    hookActivity(m, cl);
                    return;
                }
                m.log(4, TAG, "nav classes never appeared");
            }
        }, "n798-navblock").start();
    }

    private static void hookActivity(XposedModule m, ClassLoader cl) {
        Class<?> activity;
        try {
            activity = Class.forName(ACTIVITY, false, cl);
        } catch (Throwable t) {
            m.log(4, TAG, "no " + ACTIVITY + " (" + t + ")");
            return;
        }
        try {
            hook(m, activity.getDeclaredMethod("onCreate", android.os.Bundle.class));
        } catch (NoSuchMethodException ignored) {
        }
        try {
            hook(m, activity.getDeclaredMethod("onResume"));
        } catch (NoSuchMethodException ignored) {
        }
    }

    private static void hook(final XposedModule m, final Method method) {
        try {
            m.hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) strip(m, (Activity) self);
                    return result;
                }
            });
            m.log(4, TAG, "nav hook " + method.getName());
        } catch (Throwable t) {
            m.log(4, TAG, "nav hook " + method.getName() + " failed: " + t);
        }
    }

    private static void strip(final XposedModule m, final Activity activity) {
        final View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        MeBlock.sweepSoon(m, decor);
        // The bar is inflated by a fragment transaction after onCreate returns, so retry.
        decor.postDelayed(new Runnable() {
            private int attempt;

            @Override
            public void run() {
                try {
                    if (apply(m, activity)) {
                        m.log(4, TAG, "nav stripped on attempt " + attempt);
                        return;
                    }
                } catch (Throwable t) {
                    m.log(4, TAG, "nav strip failed: " + t);
                    return;
                }
                if (++attempt < 25) decor.postDelayed(this, 200);
            }
        }, 200);
    }

    /** Returns true once the bar has been found and the tab dropped. */
    private static boolean apply(XposedModule m, Activity activity) {
        String pkg = activity.getPackageName();
        int navId = activity.getResources().getIdentifier(NAV_ID, "id", pkg);
        if (navId == 0) {
            m.log(4, TAG, "no id " + NAV_ID + " in " + pkg);
            return false;
        }
        View navView = activity.findViewById(navId);
        if (!(navView instanceof ViewGroup)) return false;
        ViewGroup nav = (ViewGroup) navView;

        int scoreId = activity.getResources().getIdentifier(SCORE_ID, "id", pkg);
        View score = scoreId == 0 ? null : activity.findViewById(scoreId);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < nav.getChildCount(); i++) {
            View c = nav.getChildAt(i);
            kids.append(' ').append(Integer.toHexString(c.getId()));
        }
        m.log(4, TAG, "nav id=" + Integer.toHexString(navId) + " score id="
                + Integer.toHexString(scoreId) + " children=" + nav.getChildCount()
                + " scoreView=" + (score == null ? "null" : score.getClass().getSimpleName())
                + " parent=" + (score == null || score.getParent() == null
                        ? "null" : score.getParent().getClass().getSimpleName())
                + " ids=" + kids);
        if (score == null) return false;
        if (score.getParent() == nav) {
            nav.removeView(score);
            m.log(4, TAG, "removed tab " + SCORE_ID);
        } else if (scoreId != 0) {
            // The bar was rebuilt with a different child set; remove by matching id.
            for (int i = nav.getChildCount() - 1; i >= 0; i--) {
                if (nav.getChildAt(i).getId() == scoreId) {
                    nav.removeViewAt(i);
                    m.log(4, TAG, "removed tab by id at " + i);
                }
            }
        }

        for (int i = 0; i < nav.getChildCount(); i++) {
            View child = nav.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (!(lp instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
            if (params.width == 0 && params.weight == 1f) continue;
            params.width = 0;
            params.weight = 1f;
            child.setLayoutParams(params);
        }
        return true;
    }
}
