package app.umbra.ui.screens;

import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import app.umbra.ui.design.Ui;
import java.util.List;
import java.util.function.Function;

/** ListView configured for UMBRA: no dividers/selector, content capture excluded, optional stack-from-bottom. */
final class Lists {
    private Lists() {}
    static <T> ListView of(Ui ui, List<T> items, Function<T, View> render, boolean chat) {
        ListView list = new ListView(ui.context());
        list.setDivider(null); list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, ui.dp(4), 0, ui.dp(12));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        if (chat) { list.setStackFromBottom(true); list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL); }
        list.setAdapter(new RowAdapter<>(items, render));
        return list;
    }
}
