package io.github.internetbugs.helper798;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Drops the 积分 shortcut from the 我的 page.
 *
 * The shortcut is a cell built from an icon plus a title TextView (id mIvTitle) reading "积分".
 * It lives in a different container depending on login state — sometimes a RecyclerView
 * (mRvCommonFun), sometimes a plain LinearLayout — so cells are found by walking the view tree
 * for that title text instead of by container id or adapter position. That also means an app
 * update that reorders the grid cannot make us hide the wrong entry.
 *
 * The cell is detached, never the adapter: wrapping a BaseQuickAdapter breaks its own
 * onAttachedToRecyclerView bookkeeping. Sweeps are re-run on every activity resume and every
 * ViewPager2 page change, so the cell cannot come back when the page is rebuilt.
 *
 * Everything goes through View/ViewGroup so the module needs no androidx dependency; the
 * RecyclerView and ViewPager2 classes are only looked up by name.
 */
final class MeBlock {

    private static final String TAG = "N798";
    private static final String RV_CLASS = "androidx.recyclerview.widget.RecyclerView";
    private static final String VP_CLASS = "androidx.viewpager2.widget.ViewPager2";
    private static final String SCORE = "积分";
    private static final int SWEEPS = 15;
    private static final long SWEEP_GAP_MS = 200;

    private MeBlock() {
    }

    static void install(final XposedModule m, final ClassLoader cl) {
        // The packer appends its dex to the app's PathClassLoader only after startup, so at
        // onPackageLoaded neither androidx nor the host's own classes resolve yet.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 600; i++) {
                    try {
                        Class.forName(RV_CLASS, false, cl);
                        Class.forName(VP_CLASS, false, cl);
                        hookNow(m, cl);
                        m.log(4, TAG, "me block classes ready after " + (i * 100) + "ms");
                        return;
                    } catch (ClassNotFoundException e) {
                        // not yet
                    } catch (Throwable e) {
                        m.log(4, TAG, "me block hook failed: " + e);
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                m.log(4, TAG, "me block classes never appeared");
            }
        }, "n798-meblock");
        t.setDaemon(true);
        t.start();
    }

    private static void hookNow(final XposedModule m, ClassLoader cl) {
        try {
            Class<?> rv = Class.forName(RV_CLASS, false, cl);
            Class<?> adapter = Class.forName(RV_CLASS + "$Adapter", false, cl);
            m.hook(rv.getDeclaredMethod("setAdapter", adapter))
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            Object self = chain.getThisObject();
                            if (self instanceof View) sweepSoon(m, (View) self);
                            return result;
                        }
                    });

            Class<?> vp = Class.forName(VP_CLASS, false, cl);
            XposedInterface.Hooker pageChange = new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof View) sweepSoon(m, (View) self);
                    return result;
                }
            };
            m.hook(vp.getDeclaredMethod("setCurrentItem", int.class)).intercept(pageChange);
            m.hook(vp.getDeclaredMethod("setCurrentItem", int.class, boolean.class))
                    .intercept(pageChange);
            m.log(4, TAG, "me block hook installed");
        } catch (Throwable t) {
            m.log(4, TAG, "me block hook failed: " + t);
        }
    }

    /** Re-sweeps a few times: the page is inflated by a fragment transaction after the call. */
    static void sweepSoon(final XposedModule m, final View root) {
        for (int i = 0; i < SWEEPS; i++) {
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sweep(m, root)) m.log(4, TAG, "me shortcut " + SCORE + " removed");
                    } catch (Throwable t) {
                        m.log(4, TAG, "me sweep failed: " + t);
                    }
                }
            }, i * SWEEP_GAP_MS);
        }
    }

    private static boolean sweep(XposedModule m, View root) {
        return walk(m, root);
    }

    private static boolean walk(XposedModule m, View v) {
        if (v instanceof TextView && SCORE.equals(((TextView) v).getText().toString())) {
            return detach(m, v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                if (walk(m, g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * Climbs from the label to its cell: the highest ancestor that still has siblings, i.e. the
     * direct child of the row/grid that holds several cells. Both the 我的 icon row and the
     * bottom bar have that shape (a 2-child cell inside a 4-child container), so the same rule
     * finds the right node in either. The cell must look like a cell — a handful of children
     * including an icon — so a stray "积分" heading elsewhere cannot take a whole page with it.
     */
    private static boolean detach(XposedModule m, View title) {
        View cell = title;
        for (int depth = 0; depth < 6; depth++) {
            if (!(cell.getParent() instanceof ViewGroup)) return false;
            ViewGroup parent = (ViewGroup) cell.getParent();
            if (parent.getChildCount() >= 3) break;
            cell = parent;
        }
        if (!(cell.getParent() instanceof ViewGroup)) return false;
        ViewGroup grid = (ViewGroup) cell.getParent();
        if (grid.getChildCount() < 3) return false;
        if (cell instanceof ViewGroup) {
            ViewGroup c = (ViewGroup) cell;
            if (c.getChildCount() > 4 || !hasIcon(c)) return false;
        }

        grid.removeView(cell);
        if (grid instanceof LinearLayout) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                View child = grid.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (!(lp instanceof LinearLayout.LayoutParams)) continue;
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                llp.width = 0;
                llp.weight = 1f;
                child.setLayoutParams(llp);
            }
        }
        m.log(4, TAG, "detached cell " + SCORE + ", grid children now " + grid.getChildCount());
        return true;
    }

    private static boolean hasIcon(ViewGroup cell) {
        for (int i = 0; i < cell.getChildCount(); i++) {
            View child = cell.getChildAt(i);
            if (child instanceof android.widget.ImageView) return true;
            if (child instanceof ViewGroup && hasIcon((ViewGroup) child)) return true;
        }
        return false;
    }
}
