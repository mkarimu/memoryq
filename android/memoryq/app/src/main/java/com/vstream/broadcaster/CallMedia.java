package com.vstream.broadcaster;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The sound and picture of a call. Your microphone (and camera in a video call) is sent to the media server
 * (MediaMTX, WHIP), and each other person in the call is played from it (WHEP). The server's call state says
 * where to send and what to play; {@link #sync} is called with every state update.
 */
final class CallMedia {

    interface Listener {
        /** A person's picture arrived (video calls); key "" is your own camera. Called on the main thread. */
        void onRemoteVideo(String key, VideoTrack track);

        /** A person's picture is gone. Called on the main thread. */
        void onRemoteGone(String name);

        /** Whether we hear a person yet. Called on the main thread. */
        void onPeerLive(String name, boolean live);

        /** A short note for the screen, or "" to clear it. Called on the main thread. */
        void onNote(String text);
    }

    private static final String TAG = "CallMedia";
    private static final long RETRY_MS = 2500;

    private static EglBase egl;
    private static boolean initialized;

    private final Context ctx;
    private final boolean video;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule adm;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private PeerConnection publisher;
    private long publishRetryAt;
    private final Map<String, Reader> readers = new HashMap<>();
    private boolean muted;
    private boolean cameraOn = true;
    private boolean released;
    private int savedAudioMode = Integer.MIN_VALUE;
    private boolean speaker;

    private static final class Reader {
        PeerConnection pc;
        boolean live;
        long retryAt;
    }

    CallMedia(Context context, boolean video, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.video = video;
        this.listener = listener;
        this.speaker = video;
    }

    /** The shared drawing context for video views. */
    static synchronized EglBase egl() {
        if (egl == null) {
            egl = EglBase.create();
        }
        return egl;
    }

    /** Your own camera picture (null in a voice call or before the camera starts). */
    VideoTrack localVideo() {
        return videoTrack;
    }

    boolean isMuted() {
        return muted;
    }

    boolean isSpeaker() {
        return speaker;
    }

    boolean isCameraOn() {
        return cameraOn;
    }

    // ---- Called from the call screen ----

    /** Brings the media in line with the latest call state (the /api/calls/state answer). */
    void sync(final JSONObject state) {
        run(() -> doSync(state));
    }

    void setMuted(final boolean m) {
        muted = m;
        run(() -> {
            if (audioTrack != null) {
                audioTrack.setEnabled(!m);
            }
        });
    }

    void setCameraOn(final boolean on) {
        cameraOn = on;
        run(() -> {
            if (videoTrack != null) {
                videoTrack.setEnabled(on);
            }
        });
    }

    void switchCamera() {
        run(() -> {
            if (capturer != null) {
                capturer.switchCamera(null);
            }
        });
    }

    @SuppressWarnings("deprecation")
    void setSpeaker(boolean on) {
        speaker = on;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setSpeakerphoneOn(on);
        }
    }

    /** Stops everything. Safe to call more than once. */
    void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            worker.execute(this::doRelease);
        } catch (RejectedExecutionException e) {
            // already closed
        }
        worker.shutdown();
    }

    // ---- Work thread ----

    private void run(Runnable r) {
        if (released) {
            return;
        }
        try {
            worker.execute(() -> {
                if (released) {
                    return;
                }
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "media error", t);
                    note("Sound problem: " + t.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // closing
        }
    }

    private void note(final String text) {
        main.post(() -> listener.onNote(text));
    }

    private void doSync(JSONObject state) {
        JSONObject call = state.optJSONObject("call");
        JSONObject media = state.optJSONObject("media");
        if (call == null || call.optBoolean("ended") || !"JOINED".equals(call.optString("myState")) || media == null) {
            return;
        }
        ensureFactory();
        if (publisher == null && System.currentTimeMillis() >= publishRetryAt) {
            publish(media.optString("whipUrl"));
        }
        Set<String> want = new HashSet<>();
        JSONArray streams = state.optJSONArray("streams");
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String name = s.optString("name");
                String key = name.toLowerCase(Locale.ROOT);
                want.add(key);
                Reader r = readers.get(key);
                if (r == null || (r.pc == null && System.currentTimeMillis() >= r.retryAt)) {
                    read(name, s.optString("whepUrl"));
                }
            }
        }
        for (String key : new ArrayList<>(readers.keySet())) {
            if (!want.contains(key)) {
                dropReader(key);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void ensureFactory() {
        if (factory != null) {
            return;
        }
        synchronized (CallMedia.class) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx)
                        .createInitializationOptions());
                initialized = true;
            }
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            savedAudioMode = am.getMode();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(speaker);
        }
        adm = JavaAudioDeviceModule.builder(ctx)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();
        EglBase.Context ec = egl().getEglBaseContext();
        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(ec, true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(ec))
                .createPeerConnectionFactory();
    }

    private void startLocalTracks() {
        if (audioTrack == null) {
            MediaConstraints c = new MediaConstraints();
            c.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            c.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            c.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            audioSource = factory.createAudioSource(c);
            audioTrack = factory.createAudioTrack("mic", audioSource);
            audioTrack.setEnabled(!muted);
        }
        if (video && videoTrack == null) {
            Camera2Enumerator cams = new Camera2Enumerator(ctx);
            String pick = null;
            for (String name : cams.getDeviceNames()) {
                if (cams.isFrontFacing(name)) {
                    pick = name;
                    break;
                }
            }
            if (pick == null && cams.getDeviceNames().length > 0) {
                pick = cams.getDeviceNames()[0];
            }
            if (pick == null) {
                note("No camera found - sending sound only.");
                return;
            }
            capturer = cams.createCapturer(pick, null);
            textureHelper = SurfaceTextureHelper.create("camera", egl().getEglBaseContext());
            videoSource = factory.createVideoSource(false);
            capturer.initialize(textureHelper, ctx, videoSource.getCapturerObserver());
            capturer.startCapture(640, 480, 24);
            videoTrack = factory.createVideoTrack("cam", videoSource);
            videoTrack.setEnabled(cameraOn);
            final VideoTrack mine = videoTrack;
            main.post(() -> listener.onRemoteVideo("", mine));
        }
    }

    private PeerConnection newConnection(PeerConnection.Observer obs) {
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(new ArrayList<>());
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        return factory.createPeerConnection(cfg, obs);
    }

    private void publish(String whipUrl) {
        startLocalTracks();
        final CountDownLatch gathered = new CountDownLatch(1);
        final PeerConnection[] self = new PeerConnection[1];
        PeerConnection pc = newConnection(new Obs(gathered) {
            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                if (s == PeerConnection.PeerConnectionState.FAILED) {
                    run(() -> {
                        if (publisher != null && publisher == self[0]) {
                            publisher.dispose();
                            publisher = null;
                            publishRetryAt = System.currentTimeMillis() + RETRY_MS;
                        }
                    });
                }
            }
        });
        if (pc == null) {
            publishRetryAt = System.currentTimeMillis() + RETRY_MS;
            return;
        }
        self[0] = pc;
        java.util.List<String> ids = Collections.singletonList("me");
        pc.addTransceiver(audioTrack, new RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, ids));
        if (videoTrack != null) {
            pc.addTransceiver(videoTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, ids));
        }
        try {
            exchange(pc, gathered, whipUrl);
            publisher = pc;
            note("");
        } catch (Exception e) {
            pc.dispose();
            publishRetryAt = System.currentTimeMillis() + RETRY_MS;
            note("Connecting your " + (video ? "video" : "voice") + "... (" + e.getMessage() + ")");
        }
    }

    private void read(final String name, String whepUrl) {
        final String key = name.toLowerCase(Locale.ROOT);
        dropReader(key);
        final Reader r = new Reader();
        readers.put(key, r);
        final CountDownLatch gathered = new CountDownLatch(1);
        PeerConnection pc = newConnection(new Obs(gathered) {
            @Override
            public void onTrack(RtpTransceiver t) {
                MediaStreamTrack track = t.getReceiver().track();
                if (track instanceof VideoTrack) {
                    final VideoTrack vt = (VideoTrack) track;
                    main.post(() -> listener.onRemoteVideo(key, vt));
                }
            }

            @Override
            public void onConnectionChange(final PeerConnection.PeerConnectionState s) {
                final boolean live = s == PeerConnection.PeerConnectionState.CONNECTED;
                run(() -> {
                    if (readers.get(key) != r) {
                        return;
                    }
                    r.live = live;
                    if (s == PeerConnection.PeerConnectionState.FAILED) {
                        dropReader(key);
                    }
                });
                main.post(() -> listener.onPeerLive(key, live));
            }
        });
        if (pc == null) {
            r.retryAt = System.currentTimeMillis() + RETRY_MS;
            return;
        }
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        if (video) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        }
        try {
            exchange(pc, gathered, whepUrl);
            r.pc = pc;
        } catch (Exception e) {
            // Usually: they are not sending yet. Try again shortly.
            pc.dispose();
            r.retryAt = System.currentTimeMillis() + RETRY_MS;
        }
    }

    private void dropReader(String key) {
        Reader r = readers.remove(key);
        if (r != null && r.pc != null) {
            r.pc.dispose();
            final String k = key;
            main.post(() -> listener.onRemoteGone(k));
        }
    }

    /** Offer, wait for our network addresses, send the offer to the media server and apply its answer. */
    private void exchange(PeerConnection pc, CountDownLatch gathered, String url) throws Exception {
        SessionDescription offer = createOffer(pc);
        setLocal(pc, offer);
        gathered.await(3, TimeUnit.SECONDS);
        SessionDescription local = pc.getLocalDescription();
        String answer = postSdp(url, local == null ? offer.description : local.description);
        setRemote(pc, new SessionDescription(SessionDescription.Type.ANSWER, answer));
    }

    private static String postSdp(String url, String sdp) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/sdp");
        OutputStream os = c.getOutputStream();
        os.write(sdp.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            in.close();
        }
        c.disconnect();
        if (code != 200 && code != 201) {
            throw new Exception("media server said " + code);
        }
        return buf.toString("UTF-8");
    }

    private static SessionDescription createOffer(PeerConnection pc) throws Exception {
        final SessionDescription[] out = new SessionDescription[1];
        final String[] err = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        pc.createOffer(new Sdp() {
            @Override
            public void onCreateSuccess(SessionDescription d) {
                out[0] = d;
                done.countDown();
            }

            @Override
            public void onCreateFailure(String e) {
                err[0] = e;
                done.countDown();
            }
        }, new MediaConstraints());
        if (!done.await(5, TimeUnit.SECONDS) || out[0] == null) {
            throw new Exception(err[0] == null ? "no offer" : err[0]);
        }
        return out[0];
    }

    private static void setLocal(PeerConnection pc, SessionDescription d) throws Exception {
        setDesc(pc, d, true);
    }

    private static void setRemote(PeerConnection pc, SessionDescription d) throws Exception {
        setDesc(pc, d, false);
    }

    private static void setDesc(PeerConnection pc, SessionDescription d, boolean local) throws Exception {
        final String[] err = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        Sdp obs = new Sdp() {
            @Override
            public void onSetSuccess() {
                done.countDown();
            }

            @Override
            public void onSetFailure(String e) {
                err[0] = e;
                done.countDown();
            }
        };
        if (local) {
            pc.setLocalDescription(obs, d);
        } else {
            pc.setRemoteDescription(obs, d);
        }
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new Exception("timed out");
        }
        if (err[0] != null) {
            throw new Exception(err[0]);
        }
    }

    @SuppressWarnings("deprecation")
    private void doRelease() {
        for (String key : new ArrayList<>(readers.keySet())) {
            Reader r = readers.remove(key);
            if (r != null && r.pc != null) {
                r.pc.dispose();
            }
        }
        if (publisher != null) {
            publisher.dispose();
            publisher = null;
        }
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            capturer.dispose();
            capturer = null;
        }
        if (textureHelper != null) {
            textureHelper.dispose();
            textureHelper = null;
        }
        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (adm != null) {
            adm.release();
            adm = null;
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && savedAudioMode != Integer.MIN_VALUE) {
            am.setSpeakerphoneOn(false);
            am.setMode(savedAudioMode);
        }
    }

    // ---- Small adapters ----

    private abstract static class Sdp implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription d) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String e) {
        }

        @Override
        public void onSetFailure(String e) {
        }
    }

    private static class Obs implements PeerConnection.Observer {
        private final CountDownLatch gathered;

        Obs(CountDownLatch gathered) {
            this.gathered = gathered;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState s) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) {
                gathered.countDown();
            }
        }

        @Override
        public void onIceCandidate(IceCandidate c) {
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] c) {
        }

        @Override
        public void onAddStream(MediaStream s) {
        }

        @Override
        public void onRemoveStream(MediaStream s) {
        }

        @Override
        public void onDataChannel(DataChannel d) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver r, MediaStream[] s) {
        }
    }
}