package com.fongmi.android.tv.ui.helper;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbConfig;
import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.bean.TmdbPerson;
import com.fongmi.android.tv.service.TmdbService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.TmdbCastAdapter;
import com.fongmi.android.tv.ui.adapter.TmdbRailAdapter;
import com.fongmi.android.tv.ui.dialog.TmdbPersonDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TmdbImageSelector;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 详情页 TMDB 信息区：纯代码创建，零 layout 文件。
 * 所有控件 / 色值 / 样式均取自 app 现成资源，不新增任何 drawable / color / style / 自定义控件。
 * 播放完全交给宿主 VideoActivity，本类不碰播放器、不碰站点剧集列表。
 */
public class TmdbInfoPanel {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final LinearLayoutCompat root;
    private final ImageView poster;
    private final MaterialTextView title;
    private final MaterialTextView rating;
    private final MaterialTextView episode;
    private final MaterialTextView overview;
    private final RecyclerView castList;
    private final RecyclerView relatedList;
    private final TmdbCastAdapter castAdapter;
    private final TmdbRailAdapter relatedAdapter;
    private volatile String token = "";

    /** @param anchor 宿主详情区里作为插入基准的控件（两变体均为 mBinding.flag），插到它前面 */
    public TmdbInfoPanel(Activity activity, View anchor) {
        this.activity = activity;

        root = new LinearLayoutCompat(activity);
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.black_20));
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.setFocusable(false);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(10), ResUtil.dp2px(16), ResUtil.dp2px(10));
        root.setVisibility(View.GONE);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayoutCompat header = new LinearLayoutCompat(activity);
        header.setOrientation(LinearLayoutCompat.HORIZONTAL);
        header.setLayoutParams(ll());

        poster = new AppCompatImageView(activity);
        LinearLayoutCompat.LayoutParams posterLp = new LinearLayoutCompat.LayoutParams(ResUtil.dp2px(72), ResUtil.dp2px(104));
        posterLp.setMarginEnd(ResUtil.dp2px(12));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setLayoutParams(posterLp);
        header.addView(poster);

        LinearLayoutCompat texts = new LinearLayoutCompat(activity);
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        texts.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title = text(R.color.white, 15);
        title.setMaxLines(2);
        rating = text(R.color.white_70, 13);
        episode = text(R.color.white_70, 13);
        texts.addView(title);
        texts.addView(rating, topMargin(4));
        texts.addView(episode, topMargin(4));
        header.addView(texts);
        root.addView(header);

        overview = text(R.color.white_70, 13);
        overview.setMaxLines(3);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(overview, topMargin(8));

        root.addView(section(R.string.tmdb_info_cast));
        castList = rail();
        root.addView(castList, topMargin(6));
        root.addView(section(R.string.tmdb_info_related));
        relatedList = rail();
        root.addView(relatedList, topMargin(6));

        castAdapter = new TmdbCastAdapter();
        castAdapter.setOnItemClickListener(person -> TmdbPersonDialog.show(activity, person));
        castList.setAdapter(castAdapter);
        relatedAdapter = new TmdbRailAdapter(item -> { });
        relatedList.setAdapter(relatedAdapter);

        ViewGroup parent = (ViewGroup) anchor.getParent();
        parent.addView(root, parent.indexOfChild(anchor));
    }

    /** 复刻现成 @style/Video.ContextText 的 4 个 item：shadowColor/Dx/Dy/Radius */
    private MaterialTextView text(int colorRes, int sp) {
        MaterialTextView tv = new MaterialTextView(activity);
        tv.setTextColor(ContextCompat.getColor(activity, colorRes));
        tv.setTextSize(sp);
        tv.setShadowLayer(3, 0, 1, ContextCompat.getColor(activity, R.color.black_90));
        tv.setLayoutParams(ll());
        return tv;
    }

    private MaterialTextView section(int strRes) {
        MaterialTextView tv = text(R.color.white, 14);
        tv.setText(strRes);
        LinearLayoutCompat.LayoutParams lp = topMargin(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private RecyclerView rail() {
        RecyclerView rv = new RecyclerView(activity);
        rv.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setFocusable(false);
        rv.setLayoutParams(ll());
        return rv;
    }

    private LinearLayoutCompat.LayoutParams ll() {
        return new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayoutCompat.LayoutParams topMargin(int dp) {
        LinearLayoutCompat.LayoutParams lp = ll();
        lp.topMargin = ResUtil.dp2px(dp);
        return lp;
    }

    public void load(String name) {
        if (TextUtils.isEmpty(name) || !Setting.isTmdbReady()) {
            hide();
            return;
        }
        final String key = name.trim();
        token = key;
        EXECUTOR.execute(() -> {
            try {
                TmdbConfig config = TmdbConfig.effectiveCurrent();
                if (!config.isReady()) return;
                TmdbService service = new TmdbService();
                TmdbItem item = new TmdbMatcher(service, config).searchAndMatch(key);
                if (item == null || !key.equals(token)) return;
                JsonObject detail = service.detail(item, config, false);
                List<TmdbPerson> cast = service.cast(detail, config);
                List<TmdbItem> related = service.recommendations(detail, config);
                String posterUrl = TmdbImageSelector.poster(detail, config.getImageBase(), item.getPosterUrl());
                String rate = TmdbRatingFormatter.format(item);
                String intro = service.translatedOverview(detail, config);
                if (TextUtils.isEmpty(intro)) intro = item.getOverview();
                String episodeText = TmdbEpisodeInfo.from(item.getMediaType(), detail, 1).compactText(activity);
                App.post(() -> {
                    if (!key.equals(token) || activity.isFinishing() || activity.isDestroyed()) return;
                    title.setText(item.getTitle());
                    rating.setText(rate);
                    episode.setText(episodeText);
                    overview.setText(intro);
                    ImgUtil.load(key, posterUrl, poster);
                    castAdapter.setItems(cast);
                    relatedAdapter.setItems(related);
                    root.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                App.post(this::hide);
            }
        });
    }

    public void hide() {
        root.setVisibility(View.GONE);
    }

    public void cancel() {
        token = "";
        hide();
    }
}
