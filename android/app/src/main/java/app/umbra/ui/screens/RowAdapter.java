package app.umbra.ui.screens;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import java.util.List;
import java.util.function.Function;

/** Virtualized list adapter: rows are only built for visible positions (long chats stay light). */
public final class RowAdapter<T> extends BaseAdapter {
    private final List<T> items; private final Function<T, View> render;
    public RowAdapter(List<T> items, Function<T, View> render) { this.items = items; this.render = render; }
    @Override public int getCount() { return items.size(); }
    @Override public T getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return false; } // Rows handle their own clicks.
    @Override public View getView(int position, View convertView, ViewGroup parent) { return render.apply(items.get(position)); }
}
