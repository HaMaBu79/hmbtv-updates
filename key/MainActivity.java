package com.haidaklak.iptv;

import android.Manifest;
import android.app.SearchManager;
import com.haidaklak.iptv.CryptoManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.common.C;


import java.io.BufferedReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    public static volatile boolean isAppInForeground = false;
    public static volatile MainActivity instance = null;
    public static volatile long ignoreBackUntil = 0;
    private long lastHandledTimestamp = 0;

    private PlayerView playerView;
    private TextView tvChannelInput, tvRefreshStatus;
    private ImageView ivVoiceMic;
    private LinearLayout llChannelList;
    private RecyclerView rvCategoryColumns, rvQuickSwitcher;
    private ExoPlayer player;

    private static final String CACHE_FILE_NAME = "playlist_cache.m3u";
    // CRYPTO: URL M3U lấy từ native lib (AES-ECB decrypt trong .so)
    // Lần đầu: decrypt từ native → encrypt AES-GCM → lưu Keystore
    // Các lần sau: đọc Keystore → decrypt → dùng
    private static final String PREF_M3U_URL_KEY = "enc_m3u_url";
    private String getM3uUrl() {
        CryptoManager crypto = CryptoManager.getInstance();
        android.content.SharedPreferences prefs = getSharedPreferences("IPTV_PREFS", MODE_PRIVATE);

        // Bước 1: Thử đọc từ Keystore cache trước — không cần gọi native
        String encUrl = prefs.getString(PREF_M3U_URL_KEY, null);
        if (encUrl != null && crypto.isEncrypted(encUrl)) {
            String decrypted = crypto.decrypt(this, encUrl);
            if (decrypted != null && decrypted.startsWith("http")) {
                return decrypted; // Cache hit ✅
            }
            // Cache corrupt → xóa và lấy lại
            prefs.edit().remove(PREF_M3U_URL_KEY).apply();
        }

        // Bước 2: Lấy URL từ native C++ (XOR decode trong .so — IDA không đọc được)
        String urlFromNative = SecurityHelper.getM3uUrl();
        if (urlFromNative == null || !urlFromNative.startsWith("http")) {
            // Fallback an toàn nếu native thất bại
            urlFromNative = "https://raw.githubusercontent.com/hai-crypto2026/listmytv/main/vip_full";
        }

        // Bước 3: Re-encrypt AES-256-GCM → lưu Keystore để cache lần sau
        String encrypted = crypto.encrypt(this, urlFromNative);
        if (encrypted != null) {
            prefs.edit().putString(PREF_M3U_URL_KEY, encrypted).apply();
        }
        return urlFromNative;
    }
    private static final String PREF_ETAG = "last_etag";
    private volatile List<Channel> allChannels = new ArrayList<>();
    private MediaSession mediaSession; // MediaSession — báo cho Google TV biết app đang phát gì // FIX8: volatile đảm bảo background thread thấy giá trị mới nhất
    private List<CategoryData> categoryDataList = new ArrayList<>();
    private int currentChannelIndex = 0;
    private static final String PREF_LAST_CHANNEL  = "last_channel_index";
    private static final String PREF_RECENT_LIST   = "recent_channels_json"; // HISTORY: lưu 10 kênh gần nhất
    private static final int    MAX_RECENT_CHANNELS = 10;
    private String pendingChannelToPlay = null;
    private Map<String, Integer> channelNameIndex = new HashMap<>();

    private String channelNumberInput = "";
    private Handler numberHandler = new Handler(Looper.getMainLooper());
    private static final int CHANNEL_INPUT_TIMEOUT = 3000;
    
    private Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private Runnable autoHideRunnable = this::toggleChannelList;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingVoiceRunnable = null;

    private SpeechRecognizer speechRecognizer;
    // Flag to track if we initiated voice recognition from our UI
    private boolean isVoiceRecognitionActive = false;
    private boolean isFirstLoad = true;
    private String currentPartialText = "";
    
    private static final Map<java.util.regex.Pattern, String> NUMBER_MAP = new HashMap<>();
    private static final java.util.regex.Pattern PATTERN_PREFIX_STRIP = java.util.regex.Pattern.compile("^((mở|bật|xem|vào|kênh|số|tivi|tv|chuyển|sang|qua|vê tê vê|v t v)\\\\s+)+");

    static {
        String[] words = {"một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười"};
        for (int i = 0; i < words.length; i++) {
            NUMBER_MAP.put(java.util.regex.Pattern.compile("(?<=\\\\s|^)" + words[i] + "(?=\\\\s|$)"), String.valueOf(i + 1));
        }
    }

    // SSL FIX: Bỏ trustAllCerts bypass — dùng SSL mặc định của Android
    // trustAllCerts cho phép MITM attack dễ dàng intercept toàn bộ traffic
    // Android mặc định đã validate certificate đúng cách, không cần override
    //
    // Nếu cần kết nối đến server có self-signed cert (test server nội bộ):
    // → Thêm cert vào res/raw/cert.pem + cấu hình network_security_config.xml
    // → KHÔNG dùng trustAllCerts trong production build
    private boolean hasSwitchedChannel = false;
    private int retryCount = 0;           // RETRY: số lần đã thử lại
    // P8 FIX: ExecutorService thay vì new Thread() — có thread pool quản lý, tránh tạo thread vô tội vạ
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();
    // URL CACHE: lưu kết quả resolve PHP/redirect — tránh HTTP round-trip mỗi lần chuyển kênh
    private final Map<String, String> resolvedUrlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_RETRY = 3; // RETRY: tối đa 3 lần
    private List<String> lastPartialResults = new ArrayList<>();

    private final int[] columnColors = {
            Color.parseColor("#2E7D32"), // Green
            Color.parseColor("#EF6C00"), // Orange
            Color.parseColor("#6A1B9A"), // Purple
            Color.parseColor("#1565C0")  // Blue
    };

    public static class Channel {
        String name, url, group, logoUrl, licenseKey, userAgent;
        int originalIndex;
        boolean isFavorite; // FEATURE3
        Channel(String name, String url, String group, String logoUrl, String licenseKey, String userAgent, int originalIndex) {
            this.name = name; this.url = url; this.group = group; this.logoUrl = logoUrl; this.licenseKey = licenseKey; this.userAgent = userAgent; this.originalIndex = originalIndex;
        }
    }

    private static class CategoryData {
        String name;
        List<Channel> channels;
        int color;
        CategoryData(String name, List<Channel> channels, int color) {
            this.name = name; this.channels = channels; this.color = color;
        }
    }

    private final Runnable playChannelRunnable = () -> {
        if (!channelNumberInput.isEmpty()) {
            try {
                int targetIndex = Integer.parseInt(channelNumberInput) - 1;
                if (targetIndex >= 0 && targetIndex < allChannels.size()) {
                    playChannel(targetIndex);
                } else {
                    Toast.makeText(this, "Không tìm thấy kênh số " + channelNumberInput, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) { e.printStackTrace(); }
            resetChannelInput();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        SecurityHelper.verifySignature(this);
        setContentView(R.layout.activity_main);

        // Xử lý ACTION_SEARCH khi app được launch thẳng từ Google Assistant
        if (Intent.ACTION_SEARCH.equals(getIntent().getAction())) {
            String query = getIntent().getStringExtra(SearchManager.QUERY);
            // Xóa ngay để handleVoiceIntent bên dưới không đụng lại
            getIntent().removeExtra(SearchManager.QUERY);
            getIntent().setAction(null);
            if (query != null && !query.trim().isEmpty()) {
                pendingChannelToPlay = query.trim();
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            java.util.List<String> perms = new java.util.ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.RECORD_AUDIO);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            
            if (!perms.isEmpty()) {
                ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 100);
            }
        }

        playerView = findViewById(R.id.player_view);
        tvChannelInput = findViewById(R.id.tv_channel_input);
        tvRefreshStatus = findViewById(R.id.tv_refresh_status);
        ivVoiceMic = findViewById(R.id.iv_voice_mic);
        llChannelList = findViewById(R.id.ll_channel_list);
        rvCategoryColumns = findViewById(R.id.rv_category_columns);
        rvQuickSwitcher = findViewById(R.id.rv_quick_switcher);

        rvCategoryColumns.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvQuickSwitcher.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        initPlayer();
        checkDatabaseAndLoad();
        // Chỉ gọi handleVoiceIntent nếu KHÔNG phải ACTION_SEARCH
        // Tránh double-process: ACTION_SEARCH đã được xử lý ở trên
        if (!Intent.ACTION_SEARCH.equals(getIntent().getAction())) {
            handleVoiceIntent(getIntent());
        }

        setupSpeechListener();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // ACTION_SEARCH: Google Assistant / Google TV gửi thẳng query vào đây
        // Không cần AccessibilityService, không delay, cực nhanh
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            // Xóa query khỏi Intent ngay sau khi đọc
            // Tránh replay khi onNewIntent được gọi lại (nhấn OK, xoay màn hình...)
            intent.removeExtra(SearchManager.QUERY);
            intent.setAction(null);
            if (query != null && !query.trim().isEmpty()) {
                if (!allChannels.isEmpty()) {
                    findAndPlayChannel(query.trim());
                } else {
                    pendingChannelToPlay = query.trim();
                }
                // Update MediaSession sau ACTION_SEARCH — báo cho Google TV đã handle
                if (mediaSession != null) {
                    updatePlaybackState(player != null && player.isPlaying()
                        ? PlaybackState.STATE_PLAYING
                        : PlaybackState.STATE_PAUSED);
                }
            }
            return;
        }

        handleVoiceIntent(intent);
    }

    private void handleVoiceIntent(Intent intent) {
        if (intent == null) return;
        
        if (intent.hasExtra("channel_name")) {
            String channelName = intent.getStringExtra("channel_name");
            long timestamp = intent.getLongExtra("cmd_timestamp", 0L);
            
            // Copy logic NgocLanTV 100%: Chỉ xử lý nếu Timestamp khác biệt, chống loop vô tận.
            if (timestamp == 0 || timestamp != lastHandledTimestamp) {
                lastHandledTimestamp = timestamp;
                if (!allChannels.isEmpty()) {
                    findAndPlayChannel(channelName);
                } else {
                    pendingChannelToPlay = channelName;
                }
                intent.removeExtra("channel_name");
            }
        } else {
            handleDeepLink(intent);
        }
    }

    private void initPlayer() {
        String userAgent = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
        androidx.media3.datasource.DataSource.Factory dataSourceFactory = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);

        // PERF FIX: Custom LoadControl — giảm bufferForPlaybackMs từ 2500ms (default) xuống 1500ms
        // Kênh bắt đầu phát nhanh hơn ~1s khi chuyển, đặc biệt rõ trên TV box RAM thấp
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000,   // minBufferMs: duy trì tối thiểu 15s buffer khi đang phát
                    50_000,   // maxBufferMs: tích tối đa 50s, tránh lãng phí RAM
                    1_500,    // bufferForPlaybackMs: chỉ cần 1.5s để bắt đầu phát lần đầu
                    3_000     // bufferForPlaybackAfterRebufferMs: sau buffering cần 3s mới tiếp tục
                )
                .setPrioritizeTimeOverSizeThresholds(true) // Ưu tiên thời gian buffer hơn kích thước
                .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build();
        
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build();
        player.setAudioAttributes(audioAttributes, true);
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                // Đồng bộ MediaSession state với ExoPlayer state
                if (playbackState == Player.STATE_READY) {
                    updatePlaybackState(player.isPlaying()
                        ? PlaybackState.STATE_PLAYING
                        : PlaybackState.STATE_PAUSED);
                } else if (playbackState == Player.STATE_BUFFERING) {
                    updatePlaybackState(PlaybackState.STATE_BUFFERING);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlaybackState(isPlaying
                    ? PlaybackState.STATE_PLAYING
                    : PlaybackState.STATE_PAUSED);
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                updatePlaybackState(PlaybackState.STATE_ERROR);
                // RETRY FIX: Tự động thử lại sau 3s thay vì chỉ show Toast
                // Xử lý mất mạng tạm thời, stream server khởi động lại...
                int errorCode = error.errorCode;
                boolean isNetworkError = (
                    errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                );
                if (isNetworkError && retryCount < MAX_RETRY) {
                    retryCount++;
                    int delaySec = retryCount * 3; // backoff: 3s, 6s, 9s
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Lỗi kết nối, thử lại sau " + delaySec + "s... (" + retryCount + "/" + MAX_RETRY + ")",
                        Toast.LENGTH_SHORT).show());
                    mainHandler.postDelayed(() -> {
                        if (player != null && currentChannelIndex >= 0) {
                            player.prepare();
                            player.play();
                        }
                    }, delaySec * 1000L);
                } else {
                    retryCount = 0;
                    String errorMsg = "Lỗi phát: " + error.getErrorCodeName();
                    if (error.getCause() != null) errorMsg += "\n" + error.getCause().getMessage();
                    final String finalMsg = errorMsg;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, finalMsg, Toast.LENGTH_LONG).show());
                }
            }
        });
        
        initSpeechRecognizer();
        initMediaSession();
    }

    private void initMediaSession() {
        // FIX3: Request AudioFocus — Google TV ưu tiên route lệnh thoại vào app đang giữ AudioFocus
        android.media.AudioManager audioMgr = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioMgr != null) {
            audioMgr.requestAudioFocus(
                focusChange -> {}, // không cần handle focus change cho TV
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            );
        }
        // MediaSession: báo cho Google TV / Google Assistant biết app đang active
        // Không có cái này → Google TV coi app im lặng → lệnh thoại bị xử lý sai (overlay âm lượng, YouTube...)
        mediaSession = new MediaSession(this, "THtv_MediaSession");

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(android.content.Intent mediaButtonIntent) {
                return false; // Cho phép hệ thống xử lý media button bình thường
            }

            @Override
            public void onPlay() {
                if (player != null) player.play();
                updatePlaybackState(PlaybackState.STATE_PLAYING);
            }

            @Override
            public void onPause() {
                if (player != null) player.pause();
                updatePlaybackState(PlaybackState.STATE_PAUSED);
            }

            @Override
            public void onSkipToNext() {
                // Chuyển kênh tiếp theo
                if (currentChannelIndex < allChannels.size() - 1) {
                    playChannel(currentChannelIndex + 1);
                }
            }

            @Override
            public void onSkipToPrevious() {
                // Chuyển kênh trước
                if (currentChannelIndex > 0) {
                    playChannel(currentChannelIndex - 1);
                }
            }

            @Override
            public void onPlayFromSearch(String query, android.os.Bundle extras) {
                // ACTION_PLAY_FROM_SEARCH: Google Assistant gọi thẳng vào đây khi nói tên kênh
                // Đây là cách NgocLanTV nhận lệnh thoại mượt mà — không qua overlay Assistant
                if (query != null && !query.trim().isEmpty()) {
                    updatePlaybackState(PlaybackState.STATE_BUFFERING);
                    if (!allChannels.isEmpty()) {
                        findAndPlayChannel(query.trim());
                    } else {
                        pendingChannelToPlay = query.trim();
                    }
                    // Đảm bảo MediaSession luôn có state sau khi xử lý query
                    // Tránh Google TV hiện overlay khi app không phản hồi
                    if (mediaSession != null) {
                        updatePlaybackState(player != null && player.isPlaying()
                            ? PlaybackState.STATE_PLAYING
                            : PlaybackState.STATE_PAUSED);
                    }
                }
            }
        });

        // Khai báo app hỗ trợ các action: play, pause, next, prev, play_from_search
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY |
                    PlaybackState.ACTION_PAUSE |
                    PlaybackState.ACTION_SKIP_TO_NEXT |
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackState.ACTION_PLAY_FROM_SEARCH  // ← quan trọng nhất!
                )
                // FIX: STATE_PAUSED thay vì STATE_NONE
                // Google TV bỏ qua session có STATE_NONE khi route lệnh thoại
                // Phải là PAUSED hoặc PLAYING thì Assistant mới gọi onPlayFromSearch
                .setState(PlaybackState.STATE_PAUSED, 0, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setActive(true);

        // FIX: Đăng ký token với Activity — Google TV dùng cái này để route lệnh thoại
        // Nếu không có dòng này, Google TV không biết Activity nào đang handle MediaSession
        setMediaController(new android.media.session.MediaController(this, mediaSession.getSessionToken()));
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        long position = (player != null) ? player.getCurrentPosition() : 0;
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY |
                    PlaybackState.ACTION_PAUSE |
                    PlaybackState.ACTION_SKIP_TO_NEXT |
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackState.ACTION_PLAY_FROM_SEARCH
                )
                .setState(state, position, 1.0f)
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private void updateMediaMetadata(String channelName) {
        if (mediaSession == null) return;
        // Cập nhật metadata — Google Assistant dùng để hiển thị "Đang phát: VTV3"
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, channelName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "THtv Live")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1) // -1 = live stream
                .build();
        mediaSession.setMetadata(metadata);
    }

    private void initSpeechRecognizer() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.content.ComponentName componentName = new android.content.ComponentName(
                    "com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
                );
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, componentName);
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
        } catch (Exception e) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }
    }

    private void loadAndPlayM3u(String source, boolean isLocalFile) {
        bgExecutor.execute(() -> {
            try {
                BufferedReader reader;
                String fetchedEtag = null;
                if (isLocalFile) {
                    reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(source), "UTF-8"));
                } else {
                    URL url = new URL(source);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    // Lấy ETag để so sánh lần sau (tiết kiệm băng thông)
                    fetchedEtag = connection.getHeaderField("ETag");
                    if (fetchedEtag == null) fetchedEtag = connection.getHeaderField("Last-Modified");
                    // BUG2 FIX: GitHub raw không trả ETag chuẩn, Last-Modified thay đổi mỗi push
                    // Dùng Content-Length làm fingerprint phụ để tránh reload không cần thiết
                    if (fetchedEtag == null) fetchedEtag = connection.getHeaderField("Content-Length");
                }

                List<Channel> parsedChannels = new ArrayList<>();
                StringBuilder cacheContent = new StringBuilder();
                String line;
                String currentName = "Kênh không tên", currentGroup = "Khác", currentLogo = "", currentLicenseKey = null, currentUA = null;

                while ((line = reader.readLine()) != null) {
                    if (!isLocalFile) cacheContent.append(line).append("\n");
                    line = line.trim();
                    if (line.isEmpty() || line.equals("#EXTM3U")) continue;

                    if (line.startsWith("#")) {
                        String cleanLine = line.substring(1).trim();
                        if (cleanLine.startsWith("EXTINF:")) {
                            currentName = "Channel"; currentLogo = ""; currentGroup = "Khác"; currentLicenseKey = null; currentUA = null;
                            currentGroup = extractTag(line, "group-title");
                            currentLogo = extractTag(line, "tvg-logo");
                            if (currentLogo.equals("Khác")) currentLogo = "";
                            int commaIndex = line.lastIndexOf(',');
                            if (commaIndex != -1) currentName = line.substring(commaIndex + 1).trim();
                        } else if (cleanLine.startsWith("KODIPROP:inputstream.adaptive.license_key=")) {
                            currentLicenseKey = cleanLine.substring(42).trim();
                        } else if (cleanLine.startsWith("EXTVLCOPT:http-user-agent=")) {
                            currentUA = cleanLine.substring(26).trim();
                        } else if (cleanLine.startsWith("EXTGRP:")) {
                            currentGroup = cleanLine.substring(7).trim();
                        }
                    } else if (line.contains("://")) {
                        if (currentName.equals("Kênh không tên")) {
                            String[] urlParts = line.split("/");
                            currentName = urlParts[urlParts.length - 1].split("\\?")[0];
                        }
                        parsedChannels.add(new Channel(currentName, line.trim(), currentGroup, currentLogo, currentLicenseKey, currentUA, parsedChannels.size()));
                        currentLicenseKey = null; currentUA = null;
                        currentName = "Kênh không tên";
                    }
                }
                reader.close();

                if (!isLocalFile && cacheContent.length() > 0) {
                    File cacheFile = new File(getFilesDir(), CACHE_FILE_NAME);
                    try (FileWriter writer = new FileWriter(cacheFile)) { writer.write(cacheContent.toString()); }
                }

                // LƯU VÀO ROOM DB (chạy trên background thread — OK)
                if (!isLocalFile && !parsedChannels.isEmpty()) {
                    AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                    List<ChannelEntity> entities = new ArrayList<>();
                    for (Channel c : parsedChannels) {
                        ChannelEntity e = new ChannelEntity();
                        e.name = c.name; e.url = c.url; e.groupTitle = c.group;
                        e.logoUrl = c.logoUrl; e.licenseKey = c.licenseKey;
                        e.userAgent = c.userAgent; e.originalIndex = c.originalIndex;
                        entities.add(e);
                    }
                    // Xóa URL cache khi M3U mới được tải — tránh dùng URL cũ đã hết hạn
                    invalidateUrlCache();
                    // BUG1 FIX: Atomic transaction — insert xong mới delete
                    // Nếu mạng đứt giữa chừng, DB cũ vẫn còn nguyên, app không bị trắng kênh
                    db.runInTransaction(() -> {
                        db.channelDao().deleteAll();
                        db.channelDao().insertAll(entities);
                    });
                    if (fetchedEtag != null) {
                        // CRYPTO: Encrypt ETag trước khi lưu
                    String encEtag = CryptoManager.getInstance().encrypt(MainActivity.this, fetchedEtag);
                    getSharedPreferences("IPTV_PREFS", MODE_PRIVATE)
                                .edit().putString(PREF_ETAG, encEtag != null ? encEtag : fetchedEtag).apply();
                    }
                }

                mainHandler.post(() -> { // FIX5: dùng mainHandler có sẵn
                    if (!parsedChannels.isEmpty()) {
                        // Tráo đổi tham chiếu an toàn cho đa luồng
                        MainActivity.this.allChannels = new ArrayList<>(parsedChannels);
                        categoryListDirty = true; // FIX4: đánh dấu cần rebuild khi mở menu
                        buildChannelIndex();
                        setupMultiColumnList();
                        
                        if (pendingChannelToPlay != null) {
                            final String pending = pendingChannelToPlay;
                            pendingChannelToPlay = null;
                            mainHandler.post(() -> processChannelParam(pending)); // FIX5
                        } else if (isFirstLoad) {
                            SharedPreferences prefs = getSharedPreferences("IPTV_PREFS", MODE_PRIVATE);
                            int lastIndex = prefs.getInt(PREF_LAST_CHANNEL, 0);
                            if (lastIndex >= allChannels.size()) lastIndex = 0;
                            playChannel(lastIndex);
                            isFirstLoad = false;
                        }
                        tvRefreshStatus.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this, "Đã cập nhật danh sách kênh!", Toast.LENGTH_SHORT).show();
                        
                        // Cập nhật ra màn hình chính Android TV trên UI thread để hiện thông báo cho phép
                        mainHandler.postDelayed(() -> { // FIX5
                            try {
                                HomeScreenHelper.updateHomeScreenChannels(MainActivity.this, allChannels);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, 2000);
                    } else {
                        // Fallback: Thử tải cache nếu có
                        if (!isLocalFile) {
                            File cacheFile = new File(getFilesDir(), CACHE_FILE_NAME);
                            if (cacheFile.exists()) {
                                Toast.makeText(MainActivity.this, "Lỗi server, đang dùng danh sách tạm...", Toast.LENGTH_SHORT).show();
                                loadAndPlayM3u(cacheFile.getAbsolutePath(), true);
                            } else {
                                Toast.makeText(MainActivity.this, "Không có dữ liệu kênh!", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                });
            } catch (Exception e) { 
                e.printStackTrace();
                final String errorMsg = e.getMessage();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi tải kênh: " + errorMsg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private boolean categoryListDirty = true; // FIX4: flag — true khi cần rebuild list

    private void setupMultiColumnList() {
        if (!categoryListDirty && !categoryDataList.isEmpty()) return; // Không rebuild nếu data chưa đổi
        categoryDataList.clear();
        Map<String, List<Channel>> map = new LinkedHashMap<>();
        for (Channel c : allChannels) {
            String g = (c.group == null || c.group.isEmpty()) ? "Khác" : c.group;
            if (!map.containsKey(g)) map.put(g, new ArrayList<>());
            map.get(g).add(c);
        }
        int colorIdx = 0;
        for (Map.Entry<String, List<Channel>> entry : map.entrySet()) {
            categoryDataList.add(new CategoryData(entry.getKey(), entry.getValue(), columnColors[colorIdx % columnColors.length]));
            colorIdx++;
        }
        rvCategoryColumns.setAdapter(new CategoryColumnAdapter(categoryDataList));
        categoryListDirty = false; // Reset sau khi build xong
    }

    private void checkDatabaseAndLoad() {
        bgExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            int count = db.channelDao().getCount();
            
            if (count > 0) {
                // Đã có data: Load ngay lập tức lên UI
                List<ChannelEntity> entities = db.channelDao().getAllChannels();
                List<Channel> cachedChannels = new ArrayList<>();
                for (ChannelEntity e : entities) {
                    Channel ch = new Channel(e.name, e.url, e.groupTitle, e.logoUrl, e.licenseKey, e.userAgent, e.originalIndex);
                    ch.isFavorite = e.isFavorite; // FEATURE3: sync favorite state
                    cachedChannels.add(ch);
                }
                
                mainHandler.post(() -> { // FIX5: dùng mainHandler có sẵn, không tạo Handler mới
                    // Tráo đổi tham chiếu an toàn cho đa luồng
                    MainActivity.this.allChannels = new ArrayList<>(cachedChannels);
                    categoryListDirty = true; // FIX4: đánh dấu cần rebuild
                    buildChannelIndex();
                    setupMultiColumnList();
                    tvRefreshStatus.setVisibility(View.GONE);
                    
                    if (pendingChannelToPlay != null) {
                        final String pending = pendingChannelToPlay;
                        pendingChannelToPlay = null;
                        processChannelParam(pending);
                    } else if (isFirstLoad) {
                        SharedPreferences prefs = getSharedPreferences("IPTV_PREFS", MODE_PRIVATE);
                        int lastIndex = prefs.getInt(PREF_LAST_CHANNEL, 0);
                        if (lastIndex >= allChannels.size()) lastIndex = 0;
                        playChannel(lastIndex);
                        isFirstLoad = false;
                    }
                    
                    // Âm thầm check cập nhật từ server bằng ETag
                    checkForUpdatesInBackground();
                });
            } else {
                // Lần đầu mở app: Hiện thông báo và tải từ server
                mainHandler.post(() -> tvRefreshStatus.setVisibility(View.VISIBLE)); // FIX5
                loadAndPlayM3u(getM3uUrl(), false);
            }
        });
    }

    private void invalidateUrlCache() {
        // Xóa cache khi M3U được tải lại — URL cũ có thể không còn valid
        resolvedUrlCache.clear();
    }

    private void checkForUpdatesInBackground() {
        bgExecutor.execute(() -> {
            // BUG3 FIX: Retry 1 lần nếu lần đầu fail (mạng chưa ổn định khi mới mở app)
            int maxRetry = 2;
            for (int attempt = 1; attempt <= maxRetry; attempt++) {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(getM3uUrl());
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("HEAD");
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000); // BUG3 FIX: thêm readTimeout, tránh treo thread vô thời hạn
                    connection.connect();

                    if (connection.getResponseCode() == 200) {
                        String serverEtag = connection.getHeaderField("ETag");
                        if (serverEtag == null) serverEtag = connection.getHeaderField("Last-Modified");
                        // BUG2 FIX: Content-Length fallback — tránh tải lại khi Last-Modified đổi nhưng nội dung giống nhau
                        if (serverEtag == null) serverEtag = connection.getHeaderField("Content-Length");

                        // CRYPTO: Decrypt ETag khi đọc
                    String rawEtag = getSharedPreferences("IPTV_PREFS", MODE_PRIVATE).getString(PREF_ETAG, "");
                    CryptoManager cryptoMgr = CryptoManager.getInstance();
                    String localEtag = (cryptoMgr.isEncrypted(rawEtag))
                            ? cryptoMgr.decrypt(MainActivity.this, rawEtag) : rawEtag;
                    if (localEtag == null) localEtag = "";

                        if (serverEtag != null && !serverEtag.equals(localEtag)) {
                            // Có bản cập nhật mới, tải lại ngầm
                            loadAndPlayM3u(getM3uUrl(), false);
                        }
                    }
                    connection.disconnect();
                    break; // Thành công, thoát retry loop
                } catch (Exception e) {
                    if (connection != null) try { connection.disconnect(); } catch (Exception ignored) {}
                    if (attempt < maxRetry) {
                        try { Thread.sleep(3000L * attempt); } catch (InterruptedException ignored) {} // Backoff 3s trước khi retry
                    }
                }
            }
        });
    }

    private void playChannel(int index) {
        if (allChannels.isEmpty() || index < 0 || index >= allChannels.size()) return;
        currentChannelIndex = index;

        // Ẩn voice UI ngay khi chuyển kênh thành công
        mainHandler.post(() -> {
            tvChannelInput.setVisibility(View.GONE);
            ivVoiceMic.setVisibility(View.GONE);
        });

        // Cập nhật MediaSession — Google TV biết app đang phát kênh nào
        retryCount = 0; // reset khi chủ động chuyển kênh
        updatePlaybackState(PlaybackState.STATE_BUFFERING);
        updateMediaMetadata(allChannels.get(index).name);
        // FEATURE4: Cập nhật WatchNext row trên Google TV home screen
        Channel wn = allChannels.get(index);
        bgExecutor.execute(() -> HomeScreenHelper.updateWatchNext(
            this, wn.name, wn.url, wn.logoUrl, index));

        // Lưu kênh cuối cùng đã xem + thêm vào lịch sử 10 kênh gần nhất
        getSharedPreferences("IPTV_PREFS", MODE_PRIVATE)
                .edit()
                .putInt(PREF_LAST_CHANNEL, index)
                .apply();
        saveToRecentChannels(index); // HISTORY: cập nhật danh sách gần đây

        playChannel(allChannels.get(index));
    }

    private void playChannel(Channel c) {
        final String originalUrl = c.url;
        final String name = c.name;
        final String licenseKey = c.licenseKey;
        final String channelUA = c.userAgent;
        
        bgExecutor.execute(() -> {
            String finalUrl = originalUrl;
            String userAgentToUse = "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K UR2 Build/QTZ2.201201.001.A1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            String refererToUse = null;
            
            // Tự động chọn User-Agent và Referer dựa trên provider
            if (channelUA != null && !channelUA.isEmpty()) {
                userAgentToUse = channelUA;
            } else if (originalUrl.contains("seenow.vn") || originalUrl.contains("vtvprime.vn") || originalUrl.contains("sctv")) {
                userAgentToUse = "Dalvik/2.1.0";
                if (originalUrl.contains("seenow.vn")) refererToUse = "https://seenow.vn/";
                else if (originalUrl.contains("vtvprime.vn")) refererToUse = "https://vtvprime.vn/";
                else if (originalUrl.contains("sctv")) refererToUse = "https://vtvprime.vn/";
            } else if (originalUrl.contains("mytvnet.vn")) {
                userAgentToUse = "Dalvik/2.1.0";
                refererToUse = "https://mytvnet.vn/";
            } else if (originalUrl.contains("freem3u.xyz")) {
                userAgentToUse = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
                refererToUse = "https://freem3u.xyz/";
            } else if (originalUrl.contains("ch-4k-top.org")) {
                userAgentToUse = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
                refererToUse = null;
            } else if (originalUrl.contains("fptplay")) {
                userAgentToUse = "FPTPlay/5.0";
                refererToUse = "https://fptplay.vn/";
            }
            
            final String finalUA = userAgentToUse;
            final String finalReferer = refererToUse;
            
            // URL CACHE: check cache trước khi resolve — tránh HTTP round-trip mỗi lần chuyển kênh
            if (resolvedUrlCache.containsKey(originalUrl)) {
                finalUrl = resolvedUrlCache.get(originalUrl);
            } else if (originalUrl.contains(".php") || originalUrl.contains("tv360") || originalUrl.contains("viettel")) {
                // Xử lý link PHP/Redirect — chỉ chạy lần đầu, kết quả được cache lại
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(originalUrl).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("User-Agent", userAgentToUse);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.connect();
                    String location = conn.getHeaderField("Location");
                    if (location != null && !location.isEmpty()) {
                        finalUrl = location;
                        resolvedUrlCache.put(originalUrl, finalUrl); // cache để lần sau dùng lại
                    }
                    conn.disconnect();
                } catch (Exception e) { e.printStackTrace(); }
            }

            final String streamUrl = finalUrl;
            runOnUiThread(() -> {
                String mimeType = null;
                if (streamUrl.contains(".m3u8")) mimeType = androidx.media3.common.MimeTypes.APPLICATION_M3U8;
                else if (streamUrl.contains(".mpd")) mimeType = androidx.media3.common.MimeTypes.APPLICATION_MPD;

                Map<String, String> requestHeaders = new HashMap<>();
                requestHeaders.put("Accept", "*/*");
                requestHeaders.put("Connection", "keep-alive");
                
                if (finalReferer != null) {
                    requestHeaders.put("Referer", finalReferer);
                    if (finalReferer.contains("seenow.vn")) {
                        requestHeaders.put("Origin", "https://seenow.vn");
                        requestHeaders.put("X-Requested-With", "vn.seenow.iptv");
                    }
                    else if (finalReferer.contains("vtvprime.vn")) requestHeaders.put("Origin", "https://vtvprime.vn");
                    else if (finalReferer.contains("mytvnet.vn")) requestHeaders.put("Origin", "https://mytvnet.vn");
                    else if (finalReferer.contains("fptplay.vn")) requestHeaders.put("Origin", "https://fptplay.vn");
                } else if (streamUrl.contains("fptplay")) {
                    requestHeaders.put("Referer", "https://fptplay.vn/");
                    requestHeaders.put("Origin", "https://fptplay.vn");
                } else if (streamUrl.contains("seenow.vn")) {
                    requestHeaders.put("Referer", "https://seenow.vn/");
                    requestHeaders.put("Origin", "https://seenow.vn");
                    requestHeaders.put("X-Requested-With", "vn.seenow.iptv");
                } else if (streamUrl.contains("mytvnet.vn")) {
                    requestHeaders.put("Referer", "https://mytvnet.vn/");
                    requestHeaders.put("Origin", "https://mytvnet.vn");
                } else if (streamUrl.contains("freem3u.xyz")) {
                    requestHeaders.put("Referer", "https://freem3u.xyz/");
                }

                androidx.media3.datasource.HttpDataSource.Factory httpDataSourceFactory = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setUserAgent(finalUA)
                        .setDefaultRequestProperties(requestHeaders)
                        .setAllowCrossProtocolRedirects(true);

                // Quan trọng: Sử dụng DefaultDataSource.Factory để hỗ trợ scheme "data:" cho ClearKey (nếu cần cho manifest)
                androidx.media3.datasource.DataSource.Factory dataSourceFactory = new androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory);

                androidx.media3.common.MediaItem.Builder mediaItemBuilder = new androidx.media3.common.MediaItem.Builder()
                        .setUri(android.net.Uri.parse(streamUrl));
                if (mimeType != null) mediaItemBuilder.setMimeType(mimeType);

                DrmSessionManager drmSessionManager = null;
                if (licenseKey != null && licenseKey.contains(":")) {
                    try {
                        String[] parts = licenseKey.split(":");
                        if (parts.length >= 2) {
                            String kid = parts[0];
                            String key = parts[1];
                            String kid64 = base64UrlNoPadding(kid);
                            String key64 = base64UrlNoPadding(key);
                            
                            String clearkeyJson = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"" + key64 + "\",\"kid\":\"" + kid64 + "\"}]}";
                            
                            // Sử dụng LocalMediaDrmCallback để nạp khóa trực tiếp, tránh lỗi "unknown protocol: data"
                            LocalMediaDrmCallback localCallback = new LocalMediaDrmCallback(clearkeyJson.getBytes());
                            drmSessionManager = new DefaultDrmSessionManager.Builder()
                                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                                    .build(localCallback);
                            
                            Toast.makeText(MainActivity.this, "VIP DRM: " + name, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                }

                final DrmSessionManager finalDrmManager = drmSessionManager;
                androidx.media3.exoplayer.source.MediaSource mediaSource;
                if (streamUrl.contains(".mpd")) {
                    androidx.media3.exoplayer.dash.DashMediaSource.Factory dashFactory = new androidx.media3.exoplayer.dash.DashMediaSource.Factory(dataSourceFactory);
                    if (finalDrmManager != null) dashFactory.setDrmSessionManagerProvider(unusedItem -> finalDrmManager);
                    mediaSource = dashFactory.createMediaSource(mediaItemBuilder.build());
                } else if (streamUrl.contains(".m3u8")) {
                    androidx.media3.exoplayer.hls.HlsMediaSource.Factory hlsFactory = new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSourceFactory);
                    if (finalDrmManager != null) hlsFactory.setDrmSessionManagerProvider(unusedItem -> finalDrmManager);
                    mediaSource = hlsFactory.createMediaSource(mediaItemBuilder.build());
                } else {
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory factory = new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                            .setDataSourceFactory(dataSourceFactory);
                    if (finalDrmManager != null) factory.setDrmSessionManagerProvider(unusedItem -> finalDrmManager);
                    mediaSource = factory.createMediaSource(mediaItemBuilder.build());
                }
                
                player.setMediaSource(mediaSource);
                player.prepare();
                player.play();
                Toast.makeText(this, "Đang phát: " + name, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String base64UrlNoPadding(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private String extractTag(String line, String tagName) {
        int idx = line.indexOf(tagName + "=\"");
        if (idx == -1) return "Khác";
        int start = idx + tagName.length() + 2;
        int end = line.indexOf("\"", start);
        return (start > 0 && end > start) ? line.substring(start, end) : "Khác";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // MIỄN NHIỄM VỚI PHÍM BACK LẠC: Nếu Service vừa ra lệnh xóa Overlay, ta chặn mọi phím Back trôi vào app trong 3 giây.
        if (keyCode == KeyEvent.KEYCODE_BACK && System.currentTimeMillis() < ignoreBackUntil) {
            android.util.Log.d("MainActivity", "Ignoring stray system BACK key");
            return true; 
        }

        if (llChannelList.getVisibility() == View.VISIBLE) {
            resetAutoHideTimer();
            if (keyCode == KeyEvent.KEYCODE_BACK) { toggleChannelList(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || 
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return super.onKeyDown(keyCode, event);
            }
            return true; 
        }
        
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            channelNumberInput += String.valueOf(keyCode - KeyEvent.KEYCODE_0);
            tvChannelInput.setText(channelNumberInput);
            tvChannelInput.setVisibility(View.VISIBLE);
            numberHandler.removeCallbacks(playChannelRunnable);
            numberHandler.postDelayed(playChannelRunnable, CHANNEL_INPUT_TIMEOUT);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && llChannelList.getVisibility() == View.GONE) {
            toggleChannelList();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (llChannelList.getVisibility() == View.VISIBLE) { toggleChannelList(); return true; }
            if (!channelNumberInput.isEmpty()) { resetChannelInput(); return true; }
            finish(); return true;
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            // KHÔNG gọi startVoiceRecognition() nội bộ — tránh tranh micro với Google Assistant.
            // VoiceInterceptorService sẽ bắt text từ overlay Google Assistant và xử lý kênh TV bình thường.
            // Nhờ đó Google Assistant nghe rõ âm thanh, hiện đúng list YouTube khi cần.
            return super.onKeyDown(keyCode, event);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            playChannel((currentChannelIndex + 1) % allChannels.size()); return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            int prev = currentChannelIndex - 1; if (prev < 0) prev = allChannels.size() - 1;
            playChannel(prev); return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!channelNumberInput.isEmpty()) { numberHandler.removeCallbacks(playChannelRunnable); playChannelRunnable.run(); }
            else startVoiceRecognition();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toggleChannelList() {
        if (llChannelList.getVisibility() == View.VISIBLE) {
            llChannelList.setVisibility(View.GONE);
            autoHideHandler.removeCallbacks(autoHideRunnable);
        } else {
            llChannelList.setVisibility(View.VISIBLE);
            // FIX3+4: Chỉ rebuild khi chưa có data HOẶC data vừa thay đổi (dirty=true)
            // Tránh rebuild tốn CPU mỗi lần nhấn menu khi kênh không đổi
            if (categoryDataList.isEmpty() || rvCategoryColumns.getAdapter() == null || categoryListDirty) {
                setupMultiColumnList();
            }
            
            // Tìm nhóm và vị trí của kênh đang phát
            int targetCatIdx = -1;
            int targetChanIdxInCat = -1;
            for (int i = 0; i < categoryDataList.size(); i++) {
                List<Channel> channels = categoryDataList.get(i).channels;
                for (int j = 0; j < channels.size(); j++) {
                    if (channels.get(j).originalIndex == currentChannelIndex) {
                        targetCatIdx = i;
                        targetChanIdxInCat = j;
                        break;
                    }
                }
                if (targetCatIdx != -1) break;
            }

            if (targetCatIdx != -1) {
                final int catIdx = targetCatIdx;
                final int chanIdx = targetChanIdxInCat;
                rvCategoryColumns.scrollToPosition(catIdx);
                
                mainHandler.postDelayed(() -> { // FIX5: dùng mainHandler thay vì new Handler()
                    RecyclerView.ViewHolder vh = rvCategoryColumns.findViewHolderForAdapterPosition(catIdx);
                    if (vh != null) {
                        RecyclerView rvSub = vh.itemView.findViewById(R.id.rv_column_channels);
                        if (rvSub != null) {
                            rvSub.scrollToPosition(chanIdx);
                            mainHandler.postDelayed(() -> { // FIX5
                                RecyclerView.ViewHolder subVh = rvSub.findViewHolderForAdapterPosition(chanIdx);
                                if (subVh != null) subVh.itemView.requestFocus();
                            }, 100);
                        }
                    } else {
                        rvCategoryColumns.requestFocus();
                    }
                }, 250);
            } else {
                rvCategoryColumns.requestFocus();
            }
            
            resetAutoHideTimer();
        }
    }

    private void resetAutoHideTimer() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
        if (llChannelList.getVisibility() == View.VISIBLE) autoHideHandler.postDelayed(autoHideRunnable, 5000);
    }

    private void resetChannelInput() { channelNumberInput = ""; tvChannelInput.setVisibility(View.GONE); }

    private void startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
        }
        // FIX: Reset state sạch trước khi bắt đầu nghe
        // Tránh stale result từ lần nhận dạng trước kích hoạt lại
        isVoiceRecognitionActive = false;
        if (pendingVoiceRunnable != null) {
            mainHandler.removeCallbacks(pendingVoiceRunnable);
            pendingVoiceRunnable = null;
        }
        currentPartialText = "";

        // FIX: Delay nhỏ 150ms trước khi start listen
        // Cho phép hệ thống giải phóng mic nếu vừa dùng xong
        // Đồng thời tránh nhận ACTION_SEARCH replay ngay khi nhấn OK
        mainHandler.postDelayed(() -> {
            if (!isVoiceRecognitionActive) { // Chỉ start nếu chưa có session nào
                ivVoiceMic.setVisibility(View.VISIBLE);
                Intent voiceIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
                voiceIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                voiceIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                isVoiceRecognitionActive = true;
                speechRecognizer.startListening(voiceIntent);
            }
        }, 150);
    }

    private void setupSpeechListener() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                // Mic sẵn sàng — reset hoàn toàn, chưa làm gì cả
                hasSwitchedChannel = false;
                currentPartialText = "";
                if (pendingVoiceRunnable != null) {
                    mainHandler.removeCallbacks(pendingVoiceRunnable);
                    pendingVoiceRunnable = null;
                }
            }

            @Override public void onBeginningOfSpeech() {
                // User bắt đầu nói — hiện UI mic
                runOnUiThread(() -> {
                    ivVoiceMic.setVisibility(View.VISIBLE);
                    tvChannelInput.setVisibility(View.VISIBLE);
                    tvChannelInput.setText("...");
                });
            }

            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override public void onError(int error) {
                // Hủy tất cả pending khi lỗi — tránh nhảy kênh sai
                if (pendingVoiceRunnable != null) {
                    mainHandler.removeCallbacks(pendingVoiceRunnable);
                    pendingVoiceRunnable = null;
                }
                runOnUiThread(() -> {
                    ivVoiceMic.setVisibility(View.GONE);
                    tvChannelInput.setVisibility(View.GONE);
                });
                currentPartialText = "";
                isVoiceRecognitionActive = false;
            }

            @Override public void onResults(Bundle results) {
                // Kết quả CUỐI CÙNG — độ chính xác cao nhất, ưu tiên tuyệt đối
                // Hủy debounce từ onPartialResults nếu còn đang chờ
                if (pendingVoiceRunnable != null) {
                    mainHandler.removeCallbacks(pendingVoiceRunnable);
                    pendingVoiceRunnable = null;
                }
                isVoiceRecognitionActive = false;

                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches == null || matches.isEmpty()) {
                    runOnUiThread(() -> {
                        ivVoiceMic.setVisibility(View.GONE);
                        tvChannelInput.setVisibility(View.GONE);
                    });
                    return;
                }

                final String recognizedText = matches.get(0).trim();

                // BUG2 FIX: Update UI TRƯỚC, nhảy kênh SAU (100ms) để text kịp hiện
                runOnUiThread(() -> {
                    ivVoiceMic.setVisibility(View.GONE);
                    if (!recognizedText.isEmpty()) {
                        tvChannelInput.setVisibility(View.VISIBLE);
                        tvChannelInput.setText(recognizedText);
                    } else {
                        tvChannelInput.setVisibility(View.GONE);
                    }
                });

                if (!recognizedText.isEmpty()) {
                    // Delay 100ms để UI hiện text trước khi nhảy kênh
                    // playChannel() sẽ tự ẩn text ngay khi chuyển kênh thành công
                    mainHandler.postDelayed(() -> findAndPlayChannel(recognizedText), 100);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {
                if (!isVoiceRecognitionActive) return;

                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial == null || partial.isEmpty()) return;

                final String text = partial.get(0).trim();

                // BUG1 FIX: Bỏ qua partial rỗng hoặc quá ngắn (<2 ký tự)
                // TV box hay fire onPartialResults với "" ngay khi start → gây nhảy VTV1
                if (text.length() < 2) return;

                currentPartialText = text;

                // Hiện text đang nhận dạng lên màn hình
                runOnUiThread(() -> tvChannelInput.setText(text));

                // Hủy debounce cũ, đặt debounce mới
                if (pendingVoiceRunnable != null) {
                    mainHandler.removeCallbacks(pendingVoiceRunnable);
                }

                pendingVoiceRunnable = () -> {
                    if (!isVoiceRecognitionActive) return;
                    // BUG3 FIX: 600ms thay vì 300ms — đủ thời gian nói từ thứ 2, thứ 3
                    // onResults sẽ cancel cái này nếu nhận được kết quả cuối sớm hơn
                    runOnUiThread(() -> {
                        ivVoiceMic.setVisibility(View.GONE);
                        tvChannelInput.setVisibility(View.GONE);
                    });
                    try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
                    findAndPlayChannel(text);
                    isVoiceRecognitionActive = false;
                    pendingVoiceRunnable = null;
                };
                // BUG3 FIX: 600ms debounce cho mic nội bộ (khác với ACTION_SEARCH không cần debounce)
                mainHandler.postDelayed(pendingVoiceRunnable, 600);
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    // FIX6: 2 hàm tìm kênh cũ đã được xóa, dùng findAndPlayChannel() duy nhất

    private String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
    }
    
    private void buildChannelIndex() {
        // FIX PERFORMANCE: Build comprehensive index cho O(1) lookup
        channelNameIndex.clear();
        for (int i = 0; i < allChannels.size(); i++) {
            String name = allChannels.get(i).name;
            String normName = normalize(name);
            String lowerName = name.toLowerCase().trim();

            // Index full name
            channelNameIndex.put(normName, i);
            channelNameIndex.put(lowerName, i);

            // Index từng token (ví dụ: "VTV3 HD" → index cả "vtv3" và "vtv3 hd")
            String[] tokens = normName.split("\\s+");
            if (tokens.length > 1) {
                // Index token đầu tiên nếu có nghĩa (>=3 ký tự)
                if (tokens[0].length() >= 3) {
                    channelNameIndex.putIfAbsent(tokens[0], i);
                }
                // Index 2 token đầu
                channelNameIndex.putIfAbsent(tokens[0] + " " + tokens[1], i);
            }

            // Index không dấu rút gọn (bỏ suffix " hd", " fhd", " 4k")
            String stripped = normName.replaceAll("\\s+(hd|fhd|4k|sd|full hd)$", "").trim();
            if (!stripped.equals(normName)) {
                channelNameIndex.putIfAbsent(stripped, i);
            }
        }
    }

    private void handleDeepLink(Intent intent) {
        if (intent != null) {
            if (intent.hasExtra("channel_name")) {
                pendingChannelToPlay = intent.getStringExtra("channel_name");
                return;
            }
            
            String data = null;
            
            if (intent.getData() != null) {
                data = intent.getData().toString();
            } else if (intent.getStringExtra("android.intent.extra.TEXT") != null) {
                data = intent.getStringExtra("android.intent.extra.TEXT");
            }
            
            if (data != null) {
                String param = null;
                if (data.startsWith("iptv://play/")) {
                    param = data.replace("iptv://play/", "");
                } else if (data.contains("play/")) {
                    param = data.substring(data.indexOf("play/") + 5);
                } else if (data.startsWith("intent://play/")) {
                    param = data.replace("intent://play/", "").replace("#Intent;scheme=iptv;package=com.haidaklak.iptv;end", "");
                }
                
                if (param != null) {
                    if (!allChannels.isEmpty()) {
                        processChannelParam(param.trim());
                    } else {
                        pendingChannelToPlay = param.trim();
                    }
                }
            }
        }
    }
    
    private void processChannelParam(String param) {
        try {
            int idx = Integer.parseInt(param.trim());
            playChannel(idx);
        } catch (Exception e) {
            try {
                String channelName = java.net.URLDecoder.decode(param.trim(), "UTF-8");
                findAndPlayChannel(channelName);
            } catch (Exception ex) {
                findAndPlayChannel(param.trim());
            }
        }
    }

    // ─── FEATURE3: Favorites ────────────────────────────────────────────────────
    private void toggleFavorite(Channel c) {
        c.isFavorite = !c.isFavorite;
        bgExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            db.channelDao().setFavorite(c.originalIndex, c.isFavorite);
        });
        String msg = c.isFavorite
            ? "Da them \"" + c.name + "\" vao yeu thich"
            : "Da xoa \"" + c.name + "\" khoi yeu thich";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public List<Channel> getFavoriteChannels() {
        List<Channel> favs = new ArrayList<>();
        for (Channel c : allChannels) { if (c.isFavorite) favs.add(c); }
        return favs;
    }
    // ─────────────────────────────────────────────────────────────────────────

    // ─── HISTORY: Lưu và đọc 10 kênh gần nhất ─────────────────────────────────
    private void saveToRecentChannels(int index) {
        if (index < 0 || index >= allChannels.size()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("IPTV_PREFS", MODE_PRIVATE);
        // Đọc danh sách hiện tại
        java.util.LinkedList<Integer> recent = new java.util.LinkedList<>(getRecentChannelIndices());
        // Xóa nếu đã có (tránh trùng lặp), đẩy lên đầu
        recent.remove(Integer.valueOf(index));
        recent.addFirst(index);
        // Giữ tối đa MAX_RECENT_CHANNELS
        while (recent.size() > MAX_RECENT_CHANNELS) recent.removeLast();
        // Serialize thành chuỗi "1,5,12,3,..."
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(recent.get(i));
        }
        prefs.edit().putString(PREF_RECENT_LIST, sb.toString()).apply();
    }

    public java.util.List<Integer> getRecentChannelIndices() {
        String raw = getSharedPreferences("IPTV_PREFS", MODE_PRIVATE)
                .getString(PREF_RECENT_LIST, "");
        java.util.List<Integer> list = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        for (String s : raw.split(",")) {
            try {
                int idx = Integer.parseInt(s.trim());
                if (idx >= 0 && idx < allChannels.size()) list.add(idx);
            } catch (Exception ignored) {}
        }
        return list;
    }

    public java.util.List<Channel> getRecentChannels() {
        java.util.List<Channel> result = new java.util.ArrayList<>();
        for (int idx : getRecentChannelIndices()) {
            result.add(allChannels.get(idx));
        }
        return result;
    }
    // ─────────────────────────────────────────────────────────────────────────

    public void findAndPlayChannel(String name) {
        String cleaned = name.toLowerCase().trim();
        for (Map.Entry<java.util.regex.Pattern, String> entry : NUMBER_MAP.entrySet()) {
            cleaned = entry.getKey().matcher(cleaned).replaceAll(entry.getValue());
        }
        cleaned = PATTERN_PREFIX_STRIP.matcher(cleaned).replaceAll("").trim();
        if (cleaned.isEmpty()) cleaned = name;

        String queryNorm = normalize(cleaned);
        String origNorm  = normalize(name);

        // PASS 1: HashMap O(1) lookup — cực nhanh cho exact match
        Integer fastIdx = channelNameIndex.get(queryNorm);
        if (fastIdx == null) fastIdx = channelNameIndex.get(origNorm);
        if (fastIdx == null) fastIdx = channelNameIndex.get(cleaned.trim());
        if (fastIdx != null) { playChannel(fastIdx); return; }

        // PASS 2 & 3 & 4: fallback linear scan (chỉ chạy khi không có trong index)
        // Dùng single-pass để tránh duyệt list 4 lần
        int matchStart = -1, matchContains = -1, matchReverse = -1;
        for (int i = 0; i < allChannels.size(); i++) {
            String cn = normalize(allChannels.get(i).name);
            if (matchStart < 0 && (cn.startsWith(queryNorm) || cn.startsWith(origNorm))) {
                matchStart = i;
            }
            if (matchContains < 0 && (cn.contains(queryNorm) || cn.contains(origNorm))) {
                matchContains = i;
            }
            if (matchReverse < 0) {
                boolean lengthOk = cn.length() >= queryNorm.length() * 0.9;
                if (lengthOk && (queryNorm.contains(cn) || origNorm.contains(cn))) {
                    matchReverse = i;
                }
            }
            // Early exit: nếu đã có cả 3 loại match thì dừng
            if (matchStart >= 0 && matchContains >= 0 && matchReverse >= 0) break;
        }

        if (matchStart >= 0)    { playChannel(matchStart);   return; }
        if (matchContains >= 0) { playChannel(matchContains); return; }
        if (matchReverse >= 0)  { playChannel(matchReverse);  return; }

        // KHÔNG TÌM THẤY KÊNH:
        // Giữ nguyên state hiện tại của player — KHÔNG reset về PAUSED
        // Nếu đang phát → giữ PLAYING, nếu không → PAUSED
        // Quan trọng: phải update để Google TV biết app đã handle lệnh, tránh overlay
        boolean isPlaying = (player != null && player.isPlaying());
        updatePlaybackState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        Toast.makeText(this, "Không tìm thấy kênh: " + name, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        isAppInForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
        updatePlaybackState(PlaybackState.STATE_PAUSED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && !player.isPlaying()) player.play();
        if (mediaSession != null) mediaSession.setActive(true);
        updatePlaybackState(PlaybackState.STATE_PLAYING);
    }

    @Override
    protected void onStop() {
        super.onStop();
        isAppInForeground = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // FIX1: Xóa tất cả pending callbacks để tránh memory leak sau khi Activity bị destroy
        mainHandler.removeCallbacksAndMessages(null);
        numberHandler.removeCallbacksAndMessages(null);
        autoHideHandler.removeCallbacksAndMessages(null);
        bgExecutor.shutdown(); // P8: dọn sạch thread pool
        if (instance == this) instance = null;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        if (player != null) player.release();
        if (speechRecognizer != null) speechRecognizer.destroy();
    }

    // Adapters
    private class CategoryColumnAdapter extends RecyclerView.Adapter<CategoryColumnAdapter.ViewHolder> {
        private List<CategoryData> list;
        CategoryColumnAdapter(List<CategoryData> list) { this.list = list; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_category_column, p, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            CategoryData d = list.get(pos);
            h.tvTitle.setText(d.name); h.tvTitle.setBackgroundColor(d.color);
            h.rvChannels.setLayoutManager(new LinearLayoutManager(MainActivity.this));
            h.rvChannels.setAdapter(new VerticalChannelAdapter(d.channels));
        }
        @Override public int getItemCount() { return list.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle; RecyclerView rvChannels;
            ViewHolder(View v) { super(v); tvTitle = v.findViewById(R.id.tv_column_title); rvChannels = v.findViewById(R.id.rv_column_channels); }
        }
    }

    private class VerticalChannelAdapter extends RecyclerView.Adapter<VerticalChannelAdapter.ViewHolder> {
        private List<Channel> list;
        VerticalChannelAdapter(List<Channel> list) { this.list = list; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_channel_vertical, p, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            Channel c = list.get(pos);
            h.tvName.setText((pos + 1) + ". " + c.name);

            // FEATURE2: Load logo kênh bằng Glide với cache
            if (h.ivLogo != null) {
                if (c.logoUrl != null && !c.logoUrl.isEmpty()) {
                    Glide.with(h.itemView.getContext())
                            .load(c.logoUrl)
                            .placeholder(R.drawable.ic_launcher)
                            .error(R.drawable.ic_launcher)
                            .transition(DrawableTransitionOptions.withCrossFade(150))
                            .into(h.ivLogo);
                } else {
                    h.ivLogo.setImageResource(R.drawable.ic_launcher);
                }
            }

            // FEATURE3: Đánh dấu yêu thích trên UI
            if (h.ivFav != null) {
                h.ivFav.setVisibility(c.isFavorite ? View.VISIBLE : View.GONE);
            }

            h.itemView.setOnClickListener(v -> { playChannel(c.originalIndex); toggleChannelList(); });
            h.itemView.setOnLongClickListener(v -> {
                // FEATURE3: Long press để toggle yêu thích
                toggleFavorite(c);
                notifyItemChanged(pos);
                return true;
            });
            h.itemView.setOnFocusChangeListener((v, f) -> {
                h.tvName.setBackgroundResource(f ? R.drawable.bg_list_item_focused : R.drawable.bg_list_item_selector);
                if (f) resetAutoHideTimer();
            });
        }
        @Override public int getItemCount() { return list.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            ImageView ivLogo, ivFav;
            ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_channel_name);
                ivLogo = v.findViewById(R.id.iv_channel_logo); // null nếu layout chưa có
                ivFav  = v.findViewById(R.id.iv_favorite);     // null nếu layout chưa có
            }
        }
    }
}