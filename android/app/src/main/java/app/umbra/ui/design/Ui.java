package app.umbra.ui.design;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import app.umbra.ui.model.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Reusable UMBRA components built from framework Views (the app has no AndroidX/Compose).
 * Screens compose these; they never set raw colors, sizes or typefaces of their own.
 *
 * <p>Accessibility contract of every interactive component: a real Button/ImageButton/Switch role,
 * touch target of at least 48dp, a text label or content description, and state expressed in
 * words (stateDescription) in addition to color.
 */
public final class Ui {
    public static final int TOUCH_MIN_DP = 48;
    private final Context context;
    private final float density;

    public Ui(Context context) { this.context = context; this.density = context.getResources().getDisplayMetrics().density; }

    public Context context() { return context; }
    public int dp(float value) { return Math.round(value * density); }

    // ---------------------------------------------------------------- tones
    /** Readable text/icon color for a tone (>= 4.5:1 on surfaces and on its container). */
    public static int toneColor(Tone tone) {
        return switch (tone) {
            case NEUTRAL -> UmbraColors.TEXT_SECONDARY;
            case ACCENT -> UmbraColors.ACCENT_MUTED;
            case SUCCESS -> UmbraColors.SUCCESS_FG;
            case WARNING -> UmbraColors.WARNING_FG;
            case DANGER -> UmbraColors.DANGER_FG;
            case OFFLINE -> UmbraColors.OFFLINE_FG;
            case VERIFIED -> UmbraColors.VERIFIED_FG;
            case IDENTITY -> UmbraColors.IDENTITY_CHANGED_FG;
            case BLOCKED -> UmbraColors.BLOCKED_FG;
        };
    }
    /** Dark background behind tone-colored content (banners, chips). */
    public static int toneContainer(Tone tone) {
        return switch (tone) {
            case NEUTRAL -> UmbraColors.SURFACE_ELEVATED;
            case ACCENT -> UmbraColors.ACCENT_CONTAINER;
            case SUCCESS -> UmbraColors.SUCCESS_CONTAINER;
            case WARNING -> UmbraColors.WARNING_CONTAINER;
            case DANGER -> UmbraColors.DANGER_CONTAINER;
            case OFFLINE -> UmbraColors.OFFLINE_CONTAINER;
            case VERIFIED -> UmbraColors.VERIFIED_CONTAINER;
            case IDENTITY -> UmbraColors.IDENTITY_CHANGED_CONTAINER;
            case BLOCKED -> UmbraColors.BLOCKED_CONTAINER;
        };
    }
    /** Palette base color of a tone, used for borders and outlines. */
    public static int toneBase(Tone tone) {
        return switch (tone) {
            case NEUTRAL -> UmbraColors.BORDER_DEFAULT;
            case ACCENT -> UmbraColors.ACCENT_PRIMARY;
            case SUCCESS -> UmbraColors.SUCCESS;
            case WARNING -> UmbraColors.WARNING;
            case DANGER -> UmbraColors.DANGER;
            case OFFLINE -> UmbraColors.ACCENT_SECONDARY;
            case VERIFIED -> UmbraColors.VERIFIED;
            case IDENTITY -> UmbraColors.IDENTITY_CHANGED;
            case BLOCKED -> UmbraColors.BLOCKED;
        };
    }

    // ---------------------------------------------------------------- primitives
    public GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }
    public GradientDrawable outlined(int fill, int stroke, float radiusDp) {
        GradientDrawable d = shape(fill, radiusDp); d.setStroke(dp(1), stroke); return d;
    }
    public Drawable pressable(Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(0xFFFFFFFF, radiusDp));
    }
    public Drawable icon(Glyph glyph, int color, int sizeDp) {
        Drawable d = context.getDrawable(Icons.res(glyph)).mutate();
        d.setTint(color); d.setBounds(0, 0, dp(sizeDp), dp(sizeDp)); return d;
    }
    public ImageView iconView(Glyph glyph, int color, int sizeDp) {
        ImageView v = new ImageView(context);
        v.setImageDrawable(icon(glyph, color, sizeDp));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    public LinearLayout column() { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); return l; }
    public LinearLayout row() { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    public static LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    public static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    public static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    public LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int top, int bottom) { p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p; }
    public View space(int heightDp) { View v = new View(context); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp))); return v; }
    public View divider() {
        View v = new View(context); v.setBackgroundColor(UmbraColors.OUTLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.75f))));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return v;
    }

    // ---------------------------------------------------------------- text
    public TextView text(UmbraType type, CharSequence value) {
        TextView t = new TextView(context); type.apply(t); t.setText(value); return t;
    }
    public TextView text(UmbraType type, CharSequence value, int color) { TextView t = text(type, value); t.setTextColor(color); return t; }
    public TextView heading(UmbraType type, CharSequence value) { TextView t = text(type, value); t.setAccessibilityHeading(true); return t; }
    /** Monospace block for safety codes; long codes wrap by blocks, never scroll horizontally. */
    public TextView code(String grouped) {
        TextView t = text(UmbraType.MONOSPACE, grouped); t.setTextIsSelectable(false); t.setLongClickable(false);
        t.setContentDescription(Fingerprints.spoken(grouped)); return t;
    }

    // ---------------------------------------------------------------- buttons
    public enum ButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE, GHOST }

    public Button button(ButtonKind kind, String label, Glyph glyph, Runnable onClick) {
        Button b = new Button(context);
        b.setText(label); b.setAllCaps(false); UmbraType.LABEL.apply(b);
        int fg, bg; Drawable background;
        switch (kind) {
            case PRIMARY -> { fg = UmbraColors.ON_ACCENT; bg = UmbraColors.ACCENT_STRONG; background = outlined(bg, UmbraColors.ACCENT_PRIMARY, 14); }
            case DESTRUCTIVE -> { fg = UmbraColors.DANGER_FG; bg = UmbraColors.DANGER_CONTAINER; background = outlined(bg, UmbraColors.DANGER, 14); }
            case GHOST -> { fg = UmbraColors.ACCENT_MUTED; bg = 0x00000000; background = shape(bg, 14); }
            default -> { fg = UmbraColors.TEXT_PRIMARY; bg = UmbraColors.SURFACE_ELEVATED; background = outlined(bg, UmbraColors.BORDER_DEFAULT, 14); }
        }
        b.setTextColor(fg);
        b.setBackground(pressable(background, 14));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(52)); b.setMinimumHeight(dp(52)); b.setMinWidth(dp(TOUCH_MIN_DP));
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        b.setGravity(Gravity.CENTER);
        if (glyph != null) { b.setCompoundDrawablesRelative(icon(glyph, fg, 20), null, null, null); b.setCompoundDrawablePadding(dp(10)); }
        b.setFilterTouchesWhenObscured(true);
        if (onClick != null) b.setOnClickListener(v -> onClick.run());
        b.setLayoutParams(margins(match(), 6, 6));
        return b;
    }
    /** Disabled control that states why, instead of silently doing nothing. */
    public Button disabled(Button b, String reason) {
        b.setEnabled(false); b.setAlpha(0.5f);
        b.setContentDescription(b.getText() + ". No disponible: " + reason);
        return b;
    }
    public ImageButton iconButton(Glyph glyph, String description, Runnable onClick) {
        ImageButton b = new ImageButton(context);
        b.setImageDrawable(icon(glyph, UmbraColors.TEXT_PRIMARY, 24));
        b.setContentDescription(description);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, shape(0xFFFFFFFF, 24)));
        b.setMinimumWidth(dp(TOUCH_MIN_DP)); b.setMinimumHeight(dp(TOUCH_MIN_DP));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFilterTouchesWhenObscured(true);
        b.setTooltipText(description);
        if (onClick != null) b.setOnClickListener(v -> onClick.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(TOUCH_MIN_DP), dp(TOUCH_MIN_DP)));
        return b;
    }
    /** Round control for calls (mic, speaker, voice, end). Label is always visible below. */
    public LinearLayout callControl(Glyph glyph, String label, String state, boolean active, boolean danger, boolean enabled, Runnable onClick) {
        LinearLayout box = column(); box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageButton b = new ImageButton(context);
        int fg = danger ? UmbraColors.ON_DANGER : active ? UmbraColors.ON_ACCENT : UmbraColors.TEXT_PRIMARY;
        int bg = danger ? UmbraColors.DANGER : active ? UmbraColors.ACCENT_STRONG : UmbraColors.SURFACE_SOFT;
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(bg);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), circle, null));
        b.setImageDrawable(icon(glyph, fg, 26)); b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(label); b.setStateDescription(state);
        b.setEnabled(enabled); if (!enabled) b.setAlpha(0.45f);
        b.setFilterTouchesWhenObscured(true);
        if (onClick != null) b.setOnClickListener(v -> onClick.run());
        box.addView(b, new LinearLayout.LayoutParams(dp(danger ? 72 : 60), dp(danger ? 72 : 60)));
        TextView t = text(UmbraType.CAPTION, label, UmbraColors.TEXT_PRIMARY); t.setGravity(Gravity.CENTER);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(t, margins(wrap(), 8, 0));
        if (state != null && !state.isEmpty()) {
            TextView s = text(UmbraType.CAPTION, state, UmbraColors.TEXT_SECONDARY); s.setGravity(Gravity.CENTER);
            s.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); box.addView(s);
        }
        return box;
    }

    // ---------------------------------------------------------------- containers
    public LinearLayout card() {
        LinearLayout c = column(); c.setBackground(shape(UmbraColors.SURFACE, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(14)); c.setLayoutParams(margins(match(), 6, 6)); return c;
    }
    public LinearLayout elevatedCard() { LinearLayout c = card(); c.setBackground(outlined(UmbraColors.SURFACE_ELEVATED, UmbraColors.OUTLINE, 18)); return c; }

    /** Status banner: icon + title + optional body + optional action. Announced politely when shown. */
    public LinearLayout banner(Tone tone, Glyph glyph, String title, String body, String action, Runnable onAction) {
        LinearLayout b = row(); b.setGravity(Gravity.TOP);
        b.setBackground(outlined(toneContainer(tone), toneBase(tone), 16));
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        ImageView i = iconView(glyph, toneColor(tone), 22);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22)); ip.topMargin = dp(1); b.addView(i, ip);
        LinearLayout texts = column(); texts.setPadding(dp(12), 0, 0, 0);
        texts.addView(text(UmbraType.LABEL, title, toneColor(tone)));
        if (body != null) texts.addView(text(UmbraType.CAPTION, body, UmbraColors.TEXT_PRIMARY), margins(match(), 2, 0));
        if (action != null && onAction != null) {
            Button a = button(ButtonKind.GHOST, action, null, onAction);
            a.setTextColor(toneColor(tone)); a.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); a.setPadding(0, dp(8), dp(8), dp(8));
            texts.addView(a, margins(wrap(), 2, 0));
        }
        b.addView(texts, weight());
        b.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        b.setLayoutParams(margins(match(), 6, 6));
        return b;
    }

    public TextView chip(Tone tone, Glyph glyph, String label) {
        TextView c = text(UmbraType.CAPTION, label, toneColor(tone));
        c.setTypeface(UmbraType.LABEL.typeface());
        c.setBackground(shape(toneContainer(tone), 100));
        c.setPadding(dp(10), dp(5), dp(12), dp(5));
        c.setGravity(Gravity.CENTER_VERTICAL);
        if (glyph != null) { c.setCompoundDrawablesRelative(icon(glyph, toneColor(tone), 16), null, null, null); c.setCompoundDrawablePadding(dp(6)); }
        c.setSingleLine(false);
        LinearLayout.LayoutParams p = wrap(); p.setMarginEnd(dp(6)); p.topMargin = dp(3); p.bottomMargin = dp(3); c.setLayoutParams(p);
        return c;
    }
    /** "UI preparada · Backend pendiente" marker used on every non-functional entry point. */
    public TextView pendingChip() { return chip(Tone.NEUTRAL, Glyph.INFO, FeatureAvailability.label(FeatureAvailability.Status.PENDING_BACKEND)); }

    public TextView badge(int count) {
        TextView b = text(UmbraType.CAPTION, count > 99 ? "99+" : String.valueOf(count), UmbraColors.ON_ACCENT);
        b.setTypeface(UmbraType.LABEL.typeface()); b.setGravity(Gravity.CENTER);
        b.setBackground(shape(UmbraColors.ACCENT_STRONG, 100)); b.setMinWidth(dp(22)); b.setPadding(dp(6), dp(1), dp(6), dp(1));
        b.setContentDescription(count == 1 ? "1 mensaje sin leer" : count + " mensajes sin leer");
        return b;
    }

    /** Circle for people, rounded square with a group glyph for groups: distinguishable without color. */
    public FrameLayout avatar(String name, boolean group, int sizeDp) {
        FrameLayout f = new FrameLayout(context);
        TextView initial = text(UmbraType.HEADING, Fingerprints.initial(name), group ? UmbraColors.ACCENT_SECONDARY : UmbraColors.ACCENT_MUTED);
        initial.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeDp * 0.42f); // Decorative; name is read by the row.
        initial.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(group ? UmbraColors.BACKGROUND_TERTIARY : UmbraColors.SURFACE_SOFT);
        if (group) bg.setStroke(dp(1), UmbraColors.BORDER_DEFAULT);
        if (group) bg.setCornerRadius(dp(sizeDp * 0.3f)); else bg.setShape(GradientDrawable.OVAL);
        initial.setBackground(bg);
        f.addView(initial, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (group) {
            ImageView g = iconView(Glyph.GROUP, UmbraColors.TEXT_PRIMARY, 14);
            g.setBackground(shape(UmbraColors.SURFACE_ELEVATED, 100)); g.setPadding(dp(2), dp(2), dp(2), dp(2));
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.END); f.addView(g, gp);
        }
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    /** Icon + label trust indicator; readable by TalkBack as one phrase. */
    public TextView trustBadge(TrustPresentation trust) {
        TextView t = chip(trust.tone(), trust.glyph(), trust.label());
        t.setContentDescription("Estado de seguridad: " + trust.label());
        return t;
    }
    /** Connection indicator: connected (cloud), offline edition (bluetooth) or paused. */
    public TextView connectionChip(boolean offlineEdition, boolean networkPaused) {
        if (offlineEdition) return chip(Tone.OFFLINE, Glyph.BLUETOOTH, "Modo offline · Bluetooth");
        if (networkPaused) return chip(Tone.NEUTRAL, Glyph.CLOUD_OFF, "Internet en pausa");
        return chip(Tone.ACCENT, Glyph.CLOUD, "Conectado · servidor privado");
    }

    // ---------------------------------------------------------------- lists
    public LinearLayout listRow(View leading, String title, String subtitle, View trailing, Runnable onClick) {
        LinearLayout r = row(); r.setMinimumHeight(dp(64)); r.setPadding(dp(4), dp(10), dp(4), dp(10));
        if (leading != null) { r.addView(leading); }
        LinearLayout texts = column(); texts.setPadding(leading == null ? 0 : dp(14), 0, dp(8), 0);
        TextView t = text(UmbraType.HEADING, title); t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END); texts.addView(t);
        if (subtitle != null) { TextView s = text(UmbraType.CAPTION, subtitle); s.setMaxLines(3); texts.addView(s, margins(match(), 2, 0)); }
        r.addView(texts, weight());
        if (trailing != null) r.addView(trailing);
        if (onClick != null) {
            r.setBackground(pressable(shape(0x00000000, 14), 14));
            r.setClickable(true); r.setFocusable(true); r.setFilterTouchesWhenObscured(true);
            r.setOnClickListener(v -> onClick.run());
        }
        r.setContentDescription(subtitle == null ? title : title + ". " + subtitle);
        r.setLayoutParams(match());
        return r;
    }
    public View chevron() { ImageView v = iconView(Glyph.CHEVRON, UmbraColors.TEXT_TERTIARY, 20); return v; }
    public LinearLayout iconTile(Glyph glyph, Tone tone) {
        LinearLayout f = row(); f.setGravity(Gravity.CENTER);
        f.setBackground(shape(toneContainer(tone), 12));
        f.addView(iconView(glyph, toneColor(tone), 22));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return f;
    }
    public TextView sectionHeader(String label) {
        TextView t = heading(UmbraType.SECURITY_LABEL, label); t.setPadding(dp(4), dp(18), dp(4), dp(6)); return t;
    }

    // ---------------------------------------------------------------- states
    public LinearLayout emptyState(Glyph glyph, String title, String body, String action, Runnable onAction) {
        LinearLayout e = column(); e.setGravity(Gravity.CENTER_HORIZONTAL); e.setPadding(dp(12), dp(40), dp(12), dp(24));
        LinearLayout tile = iconTile(glyph, Tone.ACCENT); tile.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
        tile.setBackground(outlined(UmbraColors.ACCENT_CONTAINER, UmbraColors.BORDER_SUBTLE, 22)); e.addView(tile);
        TextView t = heading(UmbraType.TITLE, title); t.setGravity(Gravity.CENTER); e.addView(t, margins(match(), 18, 6));
        TextView b = text(UmbraType.BODY_SECONDARY, body); b.setGravity(Gravity.CENTER); e.addView(b, match());
        if (action != null) e.addView(button(ButtonKind.PRIMARY, action, Glyph.ADD, onAction));
        e.setLayoutParams(match());
        return e;
    }
    /**
     * Error view. Critical errors stay on screen; "Detalles técnicos" only appears when the caller
     * allows it (debug builds) and a sanitized diagnostic exists.
     */
    public LinearLayout errorState(ErrorPresentation error, Runnable retry, boolean allowTechnical) {
        LinearLayout box = column();
        box.addView(banner(error.tone(), error.glyph(), error.title(), error.body(), null, null));
        if (retry != null && error.retryable()) box.addView(button(ButtonKind.SECONDARY, "Reintentar", Glyph.RETRY, retry));
        if (allowTechnical && error.technical() != null) {
            TextView details = text(UmbraType.MONOSPACE, error.technical(), UmbraColors.TEXT_SECONDARY);
            details.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13); details.setVisibility(View.GONE);
            details.setBackground(shape(UmbraColors.BACKGROUND_SECONDARY, 12)); details.setPadding(dp(12), dp(10), dp(12), dp(10));
            Button toggle = button(ButtonKind.GHOST, "Detalles técnicos", Glyph.INFO, null);
            toggle.setOnClickListener(v -> { boolean show = details.getVisibility() != View.VISIBLE; details.setVisibility(show ? View.VISIBLE : View.GONE); toggle.setStateDescription(show ? "Visibles" : "Ocultos"); });
            toggle.setStateDescription("Ocultos");
            box.addView(toggle); box.addView(details, match());
        }
        box.setLayoutParams(match());
        return box;
    }
    /** Loading placeholder. The pulse follows the system animator scale (off when animations are off). */
    public LinearLayout skeleton(int rows) {
        LinearLayout box = column(); box.setContentDescription("Cargando"); box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < rows; i++) {
            LinearLayout r = row(); r.setPadding(0, dp(10), 0, dp(10));
            View circle = new View(context); GradientDrawable c = new GradientDrawable(); c.setShape(GradientDrawable.OVAL); c.setColor(UmbraColors.SURFACE_ELEVATED);
            circle.setBackground(c); r.addView(circle, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout lines = column(); lines.setPadding(dp(14), 0, 0, 0);
            View a = new View(context); a.setBackground(shape(UmbraColors.SURFACE_ELEVATED, 6)); lines.addView(a, new LinearLayout.LayoutParams(dp(140), dp(14)));
            View b = new View(context); b.setBackground(shape(UmbraColors.SURFACE, 6)); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(200), dp(12)); bp.topMargin = dp(8); lines.addView(b, bp);
            r.addView(lines, weight()); r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            box.addView(r);
        }
        ObjectAnimator pulse = ObjectAnimator.ofFloat(box, View.ALPHA, 1f, 0.55f);
        pulse.setDuration(900); pulse.setRepeatMode(ValueAnimator.REVERSE); pulse.setRepeatCount(ValueAnimator.INFINITE);
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { pulse.start(); }
            @Override public void onViewDetachedFromWindow(View v) { pulse.cancel(); }
        });
        box.setLayoutParams(match());
        return box;
    }

    // ---------------------------------------------------------------- inputs
    /** Text input without autofill, personalized learning or state saving (no content in bundles). */
    public EditText field(String hint) {
        EditText e = new EditText(context); UmbraType.BODY.apply(e);
        e.setHint(hint); e.setHintTextColor(UmbraColors.TEXT_TERTIARY);
        e.setBackground(outlined(UmbraColors.SURFACE, UmbraColors.OUTLINE, 14));
        e.setPadding(dp(14), dp(12), dp(14), dp(12)); e.setMinHeight(dp(52));
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setSaveEnabled(false); e.setSaveFromParentEnabled(false);
        e.setFilterTouchesWhenObscured(true);
        e.setLayoutParams(margins(match(), 6, 6));
        return e;
    }
    public LinearLayout labeledField(String label, EditText field) {
        LinearLayout box = column(); TextView l = text(UmbraType.CAPTION, label); l.setLabelFor(generateId(field));
        box.addView(l, margins(match(), 8, 0)); box.addView(field); return box;
    }
    private int generateId(View view) { if (view.getId() == View.NO_ID) view.setId(View.generateViewId()); return view.getId(); }
    /** Password input with explicit show/hide; never autofilled or saved in instance state. */
    public LinearLayout passwordField(String hint, Consumer<EditText> created) {
        LinearLayout box = row();
        box.setBackground(outlined(UmbraColors.SURFACE, UmbraColors.OUTLINE, 14));
        EditText e = field(hint); e.setBackground(null);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTypeface(UmbraType.BODY.typeface());
        box.addView(e, weight());
        ImageButton toggle = iconButton(Glyph.EYE, "Mostrar contraseña", null);
        toggle.setOnClickListener(v -> {
            boolean hidden = (e.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            e.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            e.setTypeface(UmbraType.BODY.typeface()); e.setSelection(e.getText().length());
            toggle.setImageDrawable(icon(hidden ? Glyph.EYE_OFF : Glyph.EYE, UmbraColors.TEXT_PRIMARY, 24));
            toggle.setContentDescription(hidden ? "Ocultar contraseña" : "Mostrar contraseña");
        });
        box.addView(toggle);
        box.setLayoutParams(margins(match(), 6, 6));
        if (created != null) created.accept(e);
        return box;
    }

    /** Two-or-more option control with radio semantics; the selection is also spoken. */
    public LinearLayout segmented(String[] options, int selected, boolean[] enabled, IntConsumer onSelect) {
        LinearLayout box = row(); box.setBackground(outlined(UmbraColors.BACKGROUND_SECONDARY, UmbraColors.BORDER_SUBTLE, 16)); box.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Button b = new Button(context); b.setText(options[i]); b.setAllCaps(false); UmbraType.LABEL.apply(b);
            boolean on = i == selected;
            b.setTextColor(on ? UmbraColors.ON_ACCENT : UmbraColors.TEXT_PRIMARY);
            b.setBackground(pressable(shape(on ? UmbraColors.ACCENT_STRONG : 0x00000000, 12), 12));
            b.setStateListAnimator(null); b.setMinHeight(dp(TOUCH_MIN_DP)); b.setMinimumHeight(dp(TOUCH_MIN_DP));
            b.setSelected(on); b.setStateDescription(on ? "Seleccionado" : "No seleccionado");
            b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(RadioButton.class.getName()); info.setCheckable(true); info.setChecked(host.isSelected());
                }
            });
            boolean allowed = enabled == null || enabled[i];
            b.setEnabled(allowed); if (!allowed) b.setAlpha(0.45f);
            b.setFilterTouchesWhenObscured(true);
            b.setOnClickListener(v -> onSelect.accept(index));
            box.addView(b, weight());
        }
        box.setLayoutParams(margins(match(), 6, 6));
        return box;
    }

    /**
     * Settings row with a Switch. When {@code pendingNote} is set the switch is disabled and the note
     * says why, so no control pretends to work before its implementation exists.
     */
    public LinearLayout switchRow(String title, String body, boolean checked, String pendingNote, Consumer<Boolean> onChange) {
        LinearLayout r = row(); r.setPadding(dp(4), dp(10), dp(4), dp(10)); r.setMinimumHeight(dp(64));
        LinearLayout texts = column();
        texts.addView(text(UmbraType.HEADING, title));
        if (body != null) texts.addView(text(UmbraType.CAPTION, body), margins(match(), 2, 0));
        if (pendingNote != null) { TextView p = pendingChip(); p.setText(pendingNote); texts.addView(p); }
        r.addView(texts, weight());
        Switch s = new Switch(context); s.setChecked(checked); s.setContentDescription(title);
        s.setMinWidth(dp(TOUCH_MIN_DP)); s.setMinHeight(dp(TOUCH_MIN_DP));
        s.setThumbTintList(ColorStateList.valueOf(checked ? UmbraColors.ACCENT_MUTED : UmbraColors.TEXT_SECONDARY));
        s.setTrackTintList(ColorStateList.valueOf(checked ? UmbraColors.ACCENT_STRONG : UmbraColors.SURFACE_SOFT));
        s.setFilterTouchesWhenObscured(true);
        if (pendingNote != null || onChange == null) { s.setEnabled(false); s.setAlpha(0.6f); }
        else s.setOnCheckedChangeListener((v, value) -> onChange.accept(value));
        r.addView(s);
        r.setLayoutParams(match());
        return r;
    }

    // ---------------------------------------------------------------- navigation chrome
    /** Top bar: optional back, a title block and trailing actions. */
    public LinearLayout topBar(Runnable back, View titleBlock, View... actions) {
        LinearLayout bar = row(); bar.setMinimumHeight(dp(56)); bar.setPadding(0, dp(4), 0, dp(4));
        if (back != null) bar.addView(iconButton(Glyph.BACK, "Volver", back));
        LinearLayout.LayoutParams tp = weight(); tp.setMarginStart(dp(back == null ? 4 : 6)); bar.addView(titleBlock, tp);
        for (View a : actions) if (a != null) bar.addView(a);
        bar.setLayoutParams(match());
        return bar;
    }
    public LinearLayout titleBlock(String title, View status) {
        LinearLayout t = column();
        TextView h = heading(UmbraType.TITLE, title); h.setMaxLines(2); h.setEllipsize(TextUtils.TruncateAt.END); t.addView(h);
        if (status != null) t.addView(status);
        return t;
    }
    public record NavItem(String label, Glyph glyph) {}
    public LinearLayout bottomNav(List<NavItem> items, int selected, IntConsumer onSelect) {
        LinearLayout nav = row(); nav.setBackgroundColor(UmbraColors.BACKGROUND_SECONDARY);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6));
        for (int i = 0; i < items.size(); i++) {
            final int index = i; boolean on = i == selected; NavItem item = items.get(i);
            LinearLayout cell = column(); cell.setGravity(Gravity.CENTER); cell.setMinimumHeight(dp(56));
            LinearLayout pill = row(); pill.setGravity(Gravity.CENTER); pill.setPadding(dp(16), dp(4), dp(16), dp(4));
            if (on) pill.setBackground(shape(UmbraColors.SURFACE_SOFT, 100));
            pill.addView(iconView(item.glyph(), on ? UmbraColors.ACCENT_MUTED : UmbraColors.TEXT_SECONDARY, 22));
            cell.addView(pill);
            TextView l = text(UmbraType.CAPTION, item.label(), on ? UmbraColors.TEXT_PRIMARY : UmbraColors.TEXT_SECONDARY);
            if (on) l.setTypeface(UmbraType.LABEL.typeface());
            l.setMaxLines(1); l.setEllipsize(TextUtils.TruncateAt.END); l.setGravity(Gravity.CENTER); cell.addView(l);
            cell.setContentDescription(item.label()); cell.setSelected(on); cell.setStateDescription(on ? "Pestaña actual" : null);
            cell.setClickable(true); cell.setFocusable(true); cell.setFilterTouchesWhenObscured(true);
            cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, shape(0xFFFFFFFF, 16)));
            cell.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); }
            });
            cell.setOnClickListener(v -> onSelect.accept(index));
            nav.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        nav.setLayoutParams(match());
        return nav;
    }

    // ---------------------------------------------------------------- messages
    /** Chat bubble for text/file/image/system messages. Group messages show the sender above. */
    public LinearLayout bubble(MessageItem m, Runnable onClick, Runnable onRetry) {
        LinearLayout line = row(); line.setGravity(m.kind() == MessageItem.Kind.SYSTEM ? Gravity.CENTER : m.outgoing() ? Gravity.END : Gravity.START);
        if (m.kind() == MessageItem.Kind.SYSTEM) {
            TextView s = chip(Tone.NEUTRAL, Glyph.INFO, m.text()); line.addView(s); line.setPadding(0, dp(8), 0, dp(8)); line.setLayoutParams(match()); return line;
        }
        LinearLayout b = column();
        b.setBackground(shape(m.outgoing() ? UmbraColors.BUBBLE_OUTGOING : UmbraColors.BUBBLE_INCOMING, 18));
        b.setPadding(dp(14), dp(10), dp(14), dp(8));
        if (m.sender() != null && !m.outgoing()) b.addView(text(UmbraType.CAPTION, m.sender(), UmbraColors.ACCENT_MUTED));
        StringBuilder spoken = new StringBuilder(m.outgoing() ? "Enviado" : m.sender() != null ? m.sender() : "Recibido");
        switch (m.kind()) {
            case FILE, IMAGE -> {
                LinearLayout f = row();
                f.addView(iconTile(m.kind() == MessageItem.Kind.IMAGE ? Glyph.PHOTO : Glyph.FILE, Tone.ACCENT));
                LinearLayout meta = column(); meta.setPadding(dp(12), 0, 0, 0);
                TextView name = text(UmbraType.LABEL, m.fileName()); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.MIDDLE); meta.addView(name);
                meta.addView(text(UmbraType.CAPTION, (m.kind() == MessageItem.Kind.IMAGE ? "Imagen · " : "Archivo · ") + m.sizeLabel() + " · toca para exportar"));
                f.addView(meta, weight()); b.addView(f);
                spoken.append(". ").append(m.kind() == MessageItem.Kind.IMAGE ? "Imagen " : "Archivo ").append(m.fileName()).append(", ").append(m.sizeLabel());
            }
            default -> { TextView t = text(UmbraType.BODY, m.text()); b.addView(t); spoken.append(". ").append(m.text()); }
        }
        LinearLayout meta = row(); meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView time = text(UmbraType.CAPTION, m.time(), UmbraColors.TEXT_SECONDARY); meta.addView(time);
        if (m.outgoing() && m.delivery() != null && !m.delivery().label().isEmpty()) {
            TextView d = text(UmbraType.CAPTION, " · " + m.delivery().label(), m.delivery().tone() == Tone.NEUTRAL ? UmbraColors.TEXT_SECONDARY : toneColor(m.delivery().tone()));
            d.setCompoundDrawablesRelative(null, null, icon(m.delivery().glyph(), toneColor(m.delivery().tone()), 14), null); d.setCompoundDrawablePadding(dp(4));
            meta.addView(d);
            spoken.append(". ").append(m.delivery().label());
        }
        spoken.append(". ").append(m.time());
        b.addView(meta, margins(match(), 4, 0));
        b.setContentDescription(spoken.toString());
        if (onClick != null) { b.setClickable(true); b.setFocusable(true); b.setFilterTouchesWhenObscured(true); b.setOnClickListener(v -> onClick.run()); }
        if (onRetry != null) { b.setClickable(true); b.setOnClickListener(v -> onRetry.run()); }
        LinearLayout.LayoutParams p = wrap();
        int side = dp(44); if (m.outgoing()) p.setMarginStart(side); else p.setMarginEnd(side);
        line.addView(b, p);
        line.setPadding(0, dp(3), 0, dp(3)); // Padding, not margins: list rows get AbsListView params.
        line.setLayoutParams(match());
        return line;
    }
    /** Location card inside a conversation: who shares, precision, freshness, and a stop control if ours. */
    public LinearLayout locationCard(String title, String detail, boolean live, boolean outgoing, Runnable stop) {
        LinearLayout c = elevatedCard();
        LinearLayout head = row(); head.addView(iconTile(Glyph.LOCATION, live ? Tone.ACCENT : Tone.NEUTRAL));
        LinearLayout t = column(); t.setPadding(dp(12), 0, 0, 0);
        t.addView(text(UmbraType.LABEL, title)); t.addView(text(UmbraType.CAPTION, detail));
        head.addView(t, weight()); c.addView(head);
        if (live) c.addView(chip(Tone.ACCENT, Glyph.TIMER, outgoing ? "Estás compartiendo en vivo" : "Ubicación en vivo"));
        if (stop != null) c.addView(button(ButtonKind.DESTRUCTIVE, "DETENER UBICACIÓN", Glyph.STOP, stop));
        c.setContentDescription(title + ". " + detail);
        return c;
    }
}
