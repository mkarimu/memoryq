package com.vstream.broadcaster;

import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WatchActivity extends AppCompatActivity {

    private String serverHost;
    private TextView status;
    private LinearLayout channelList;
    private PlayerView playerView;
    private ExoPlayer player;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        serverHost = getIntent().getStringExtra("serverHost");
        if (serverHost == null) serverHost = "";
        serverHost = serverHost.trim()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");

        executor = Executors.newSingleThreadExecutor();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());

        Button refresh = new Button(this);
        refresh.setText("Refresh channels");
        refresh.setOnClickListener(v -> loadChannels());

        toolbar.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbar.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(toolbar);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        root.addView(playerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView heading = new TextView(this);
        heading.setText("Available live channels");
        heading.setTextSize(18);
        heading.setPadding(0, dp(8), 0, dp(8));
        root.addView(heading);

        ScrollView scroll = new ScrollView(this);
        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(channelList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        player = new ExoPlayer.Builder(this).setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true).setHandleAudioBecomingNoisy(true).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("Playback failed: " + error.getMessage());
            }
        });

        loadChannels();
    }

    private void loadChannels() {
        if (serverHost.isEmpty()) {
            status.setText("Server address is empty. Return to the broadcaster screen.");
            return;
        }

        status.setText("Loading channels...");
        channelList.removeAllViews();

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(Api.base(serverHost) + "/channels");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);

                int code = connection.getResponseCode();
                InputStream input = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();

                if (input == null) throw new Exception("HTTP " + code);

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }

                if (code < 200 || code >= 300) {
                    throw new Exception("Server returned HTTP " + code);
                }

                String response = body.toString().trim();
                JSONArray channels;

                if (response.startsWith("[")) {
                    channels = new JSONArray(response);
                } else {
                    JSONObject wrapper = new JSONObject(response);
                    channels = wrapper.optJSONArray("channels");
                    if (channels == null) {
                        channels = wrapper.optJSONArray("liveChannels");
                    }
                    if (channels == null) {
                        throw new Exception("Unexpected /channels JSON: " + response);
                    }
                }

                JSONArray result = channels;
                runOnUiThread(() -> showChannels(result));

            } catch (Exception e) {
                runOnUiThread(() ->
                        status.setText("Channel list failed: " + e.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showChannels(JSONArray channels) {
        channelList.removeAllViews();
        int count = 0;

        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null) continue;
            if (!channel.optBoolean("live", true)) continue;

            String channelId = channel.optString("channelId", "");
            String broadcaster = channel.optString("broadcasterId", "");
            String address = channel.optString("streamAddress", "");
            String title = channel.optString("title", "");

            if (channelId.isEmpty()) continue;

            String playbackUrl = makePlaybackUrl(address, channelId);
            String label = title.isEmpty() ? channelId : title + " (" + channelId + ")";
            if (!broadcaster.isEmpty()) label += "\nBroadcaster: " + broadcaster;

            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText(label + "\nTap to watch");
            item.setOnClickListener(v -> playChannel(channelId, playbackUrl));

            channelList.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            count++;
        }

        status.setText(count == 0
                ? "No live channels found."
                : count + " live channel(s). Select one to watch.");
    }

    private String makePlaybackUrl(String streamAddress, String channelId) {
        String address = streamAddress == null ? "" : streamAddress.trim();

        if (address.isEmpty() || address.startsWith("rtmp://")) {
            return Api.hls(serverHost) + "/live/"
                    + channelId + "/index.m3u8";
        }

        address = address.replace("localhost", serverHost)
                .replace("127.0.0.1", serverHost)
                .replace("0.0.0.0", serverHost);

        address = Api.fixHls(address, serverHost);

        if (address.startsWith("/")) {
            address = Api.hls(serverHost) + address;
        }

        Uri uri = Uri.parse(address);
        String path = uri.getPath();

        if (path != null && !path.endsWith(".m3u8")) {
            address = address.replaceAll("/+$", "") + "/index.m3u8";
        }

        return address;
    }

    private void playChannel(String channelId, String url) {
        status.setText("Opening " + channelId + "...");
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        status.setText("Playing " + channelId);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}