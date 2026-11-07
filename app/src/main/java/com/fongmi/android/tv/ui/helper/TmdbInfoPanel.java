package com.fongmi.android.tv.ui.helper;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

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
import com.fongmi.android.tv.utils.TmdbImageSelector;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 详情页 TMDB 信息区：只展示海报 / 评分 / 剧集信息 / 简介 / 演职员 / 相关推荐。
 * 播放完全交给宿主 VideoActivity，本类不碰播放器、不碰站点剧集列表。
 */
public class TmdbInfoPanel {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final View root;
    private final ImageView poster;
    private final TextView title;
    private final TextView rating;
    private final TextView episode;
    private final TextView overview;
    private final RecyclerView castList;
    private final RecyclerView relatedList;
    private final TmdbCastAdapter castAdapter;
    private final TmdbRailAdapter relatedAdapter;
    private volatile String token = "";

    public TmdbInfoPanel(Activity activity, View decor) {
        this.activity = activity;
        root = decor.findViewById(R.id.tmdbInfo);
        poster = root.findViewById(R.id.tmdbPoster);
        title = root.findViewById(R.id.tmdbTitle);
        rating = root.findViewById(R.id.tmdbRating);
        episode = root.findViewById(R.id.tmdbEpisode);
        overview = root.findViewById(R.id.tmdbOverview);
        castList = root.findViewById(R.id.tmdbCast);
        relatedList = root.findViewById(R.id.tmdbRelated);
        castAdapter = new TmdbCastAdapter();
        castAdapter.setOnItemClickListener(person -> TmdbPersonDialog.show(activity, person));
        castList.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        castList.setAdapter(castAdapter);
        relatedAdapter = new TmdbRailAdapter(item -> { });
        relatedList.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        relatedList.setAdapter(relatedAdapter);
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
