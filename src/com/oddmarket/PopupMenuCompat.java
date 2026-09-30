package com.oddmarket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class PopupMenuCompat {

    private PopupMenuCompat() {
    }

    public static boolean show(final Activity activity) {
        try {
            if (activity.isFinishing()) return true;

            final MenuImpl menu = new MenuImpl(activity);
            if (!activity.onCreateOptionsMenu(menu.proxy)) return true;
            activity.onPrepareOptionsMenu(menu.proxy);

            final List<ItemImpl> shown = new ArrayList<ItemImpl>();
            for (int i = 0; i < menu.items.size(); i++) {
                ItemImpl it = menu.items.get(i);
                if (it.visible && it.enabled && it.title != null && it.title.length() > 0) {
                    shown.add(it);
                }
            }
            if (shown.isEmpty()) return true;

            CharSequence[] titles = new CharSequence[shown.size()];
            for (int i = 0; i < titles.length; i++) titles[i] = shown.get(i).title;

            AlertDialog.Builder b = newBuilder(activity);
            b.setItems(titles, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    try {
                        activity.onOptionsItemSelected(shown.get(which).proxy);
                    } catch (Exception e) {
                        FileLogger.w(Utils.TAG, "PopupMenuCompat: menu action failed", e);
                    }
                }
            });

            AlertDialog dialog = b.create();
            dialog.setCanceledOnTouchOutside(true);
            dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP) d.dismiss();
                    return true;
                }
            });
            dialog.show();
            return true;
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "PopupMenuCompat.show failed, falling back to openOptionsMenu", e);
            return false;
        }
    }

    private static AlertDialog.Builder newBuilder(Activity activity) {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk >= 11) {

            boolean dark = Theme.isDark();
            int theme = (sdk >= 14) ? (dark ? 4 : 5) : (dark ? 2 : 3);
            try {
                return AlertDialog.Builder.class.getConstructor(Context.class, int.class)
                        .newInstance(activity, Integer.valueOf(theme));
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "PopupMenuCompat: themed builder unavailable", e);
            }
        }
        return new AlertDialog.Builder(activity);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0f);
        if (type == double.class) return Double.valueOf(0d);
        if (type == char.class) return Character.valueOf('\0');
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == byte.class) return Byte.valueOf((byte) 0);
        return null;
    }

    private static Object objectMethod(Object proxy, Method m, Object[] args) {
        String n = m.getName();
        if ("equals".equals(n)) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
        if ("hashCode".equals(n)) return Integer.valueOf(System.identityHashCode(proxy));
        return "PopupMenuCompat@" + Integer.toHexString(System.identityHashCode(proxy));
    }

    private static CharSequence text(Context c, Object o) {
        if (o instanceof Integer) return c.getText(((Integer) o).intValue());
        return (CharSequence) o;
    }

    private static final class ItemImpl implements InvocationHandler {
        final int id;
        final int group;
        final int order;
        CharSequence title;
        boolean visible = true;
        boolean enabled = true;
        boolean checkable = false;
        boolean checked = false;
        final Context context;
        MenuItem proxy;

        ItemImpl(Context context, int group, int id, int order, CharSequence title) {
            this.context = context;
            this.group = group;
            this.id = id;
            this.order = order;
            this.title = title;
        }

        public Object invoke(Object p, Method m, Object[] a) throws Throwable {
            if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, a);
            String n = m.getName();
            if ("getItemId".equals(n)) return Integer.valueOf(id);
            if ("getGroupId".equals(n)) return Integer.valueOf(group);
            if ("getOrder".equals(n)) return Integer.valueOf(order);
            if ("getTitle".equals(n)) return title;
            if ("setTitle".equals(n) && a != null && a.length == 1) {
                title = text(context, a[0]);
                return p;
            }
            if ("isVisible".equals(n)) return Boolean.valueOf(visible);
            if ("setVisible".equals(n)) {
                visible = ((Boolean) a[0]).booleanValue();
                return p;
            }
            if ("isEnabled".equals(n)) return Boolean.valueOf(enabled);
            if ("setEnabled".equals(n)) {
                enabled = ((Boolean) a[0]).booleanValue();
                return p;
            }
            if ("isCheckable".equals(n)) return Boolean.valueOf(checkable);
            if ("setCheckable".equals(n)) {
                checkable = ((Boolean) a[0]).booleanValue();
                return p;
            }
            if ("isChecked".equals(n)) return Boolean.valueOf(checked);
            if ("setChecked".equals(n)) {
                checked = ((Boolean) a[0]).booleanValue();
                return p;
            }
            if (m.getReturnType() == MenuItem.class) return p;
            return defaultValue(m.getReturnType());
        }
    }

    private static final class MenuImpl implements InvocationHandler {
        final Context context;
        final List<ItemImpl> items = new ArrayList<ItemImpl>();
        final Menu proxy;

        MenuImpl(Context context) {
            this.context = context;
            this.proxy = (Menu) Proxy.newProxyInstance(PopupMenuCompat.class.getClassLoader(),
                    new Class[]{Menu.class}, this);
        }

        private MenuItem add(int group, int id, int order, CharSequence title) {
            ItemImpl it = new ItemImpl(context, group, id, order, title);
            it.proxy = (MenuItem) Proxy.newProxyInstance(PopupMenuCompat.class.getClassLoader(),
                    new Class[]{MenuItem.class}, it);
            int pos = items.size();
            while (pos > 0 && items.get(pos - 1).order > order) pos--;
            items.add(pos, it);
            return it.proxy;
        }

        private ItemImpl find(int id) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == id) return items.get(i);
            }
            return null;
        }

        public Object invoke(Object p, Method m, Object[] a) throws Throwable {
            if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, a);
            String n = m.getName();
            if ("add".equals(n)) {
                if (a.length == 1) return add(0, 0, 0, text(context, a[0]));
                return add(((Integer) a[0]).intValue(), ((Integer) a[1]).intValue(),
                        ((Integer) a[2]).intValue(), text(context, a[3]));
            }
            if ("findItem".equals(n)) {
                ItemImpl it = find(((Integer) a[0]).intValue());
                return it == null ? null : it.proxy;
            }
            if ("size".equals(n)) return Integer.valueOf(items.size());
            if ("getItem".equals(n)) return items.get(((Integer) a[0]).intValue()).proxy;
            if ("clear".equals(n)) {
                items.clear();
                return null;
            }
            if ("removeItem".equals(n)) {
                ItemImpl it = find(((Integer) a[0]).intValue());
                if (it != null) items.remove(it);
                return null;
            }
            if ("removeGroup".equals(n)) {
                int g = ((Integer) a[0]).intValue();
                for (int i = items.size() - 1; i >= 0; i--) {
                    if (items.get(i).group == g) items.remove(i);
                }
                return null;
            }
            if ("setGroupVisible".equals(n) || "setGroupEnabled".equals(n)) {
                int g = ((Integer) a[0]).intValue();
                boolean v = ((Boolean) a[1]).booleanValue();
                for (int i = 0; i < items.size(); i++) {
                    ItemImpl it = items.get(i);
                    if (it.group != g) continue;
                    if ("setGroupVisible".equals(n)) it.visible = v; else it.enabled = v;
                }
                return null;
            }
            if ("hasVisibleItems".equals(n)) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).visible) return Boolean.TRUE;
                }
                return Boolean.FALSE;
            }
            return defaultValue(m.getReturnType());
        }
    }
}
