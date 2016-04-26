/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.liguang.musicplayer.playback;

import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.common.images.WebImage;
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager;
import com.google.android.libraries.cast.companionlibrary.cast.callbacks.VideoCastConsumerImpl;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.CastException;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.NoConnectionException;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.TransientNetworkDisconnectionException;
import com.liguang.musicplayer.model.MusicProvider;
import com.liguang.musicplayer.model.MusicProviderSource;
import com.liguang.musicplayer.utils.LogHelper;
import com.liguang.musicplayer.utils.MediaIDHelper;

import org.json.JSONException;
import org.json.JSONObject;

import static android.support.v4.media.session.MediaSessionCompat.QueueItem;

/**
 * An implementation of Playback that talks to Cast.
 */
public class CastPlayback implements Playback {

    private static final String TAG = LogHelper.makeLogTag(CastPlayback.class);

    private static final String MIME_TYPE_AUDIO_MPEG = "audio/mpeg";
    private static final String ITEM_ID = "itemId";

    private final MusicProvider mMusicProvider;
    private final VideoCastConsumerImpl mCastConsumer = new VideoCastConsumerImpl() {

        @Override
        public void onRemoteMediaPlayerMetadataUpdated() {
            LogHelper.d(TAG, "onRemoteMediaPlayerMetadataUpdated");
            setMetadataFromRemote();
        }

        @Override
        public void onRemoteMediaPlayerStatusUpdated() {
            LogHelper.d(TAG, "onRemoteMediaPlayerStatusUpdated");
            updatePlaybackState();
        }
    };

    /** The current PlaybackState*/
    private int mState;
    /** Callback for making completion/error calls on */
    private Callback mCallback;
    private volatile int mCurrentPosition;
    private volatile String mCurrentMediaId;

    public CastPlayback(MusicProvider musicProvider) {
        this.mMusicProvider = musicProvider;
    }

    @Override
    public void start() {
        VideoCastManager.getInstance().addVideoCastConsumer(mCastConsumer);
    }

    @Override
    public void stop(boolean notifyListeners) {
        VideoCastManager.getInstance().removeVideoCastConsumer(mCastConsumer);
        mState = PlaybackStateCompat.STATE_STOPPED;
        if (notifyListeners && mCallback != null) {
            mCallback.onPlaybackStatusChanged(mState);
        }
    }

    @Override
    public void setState(int state) {
        this.mState = state;
    }

    @Override
    public int getCurrentStreamPosition() {
        if (!VideoCastManager.getInstance().isConnected()) {
            return mCurrentPosition;
        }
        try {
            return (int) VideoCastManager.getInstance().getCurrentMediaPosition();
        } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
            LogHelper.e(TAG, e, "Exception getting media position");
        }
        return -1;
    }

    @Override
    public void setCurrentStreamPosition(int pos) {
        this.mCurrentPosition = pos;
    }

    @Override
    public void updateLastKnownStreamPosition() {
        mCurrentPosition = getCurrentStreamPosition();
    }

    @Override
    public void play(QueueItem item) {
        try {
            loadMedia(item.getDescription().getMediaId(), true);
            mState = PlaybackStateCompat.STATE_BUFFERING;
            if (mCallback != null) {
                mCallback.onPlaybackStatusChanged(mState);
            }
        } catch (TransientNetworkDisconnectionException | NoConnectionException
                | JSONException | IllegalArgumentException e) {
            LogHelper.e(TAG, "Exception loading media ", e, null);
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void pause() {
        try {
            VideoCastManager manager = VideoCastManager.getInstance();
            if (manager.isRemoteMediaLoaded()) {
                manager.pause();
                mCurrentPosition = (int) manager.getCurrentMediaPosition();
            } else {
                loadMedia(mCurrentMediaId, false);
            }
        } catch (JSONException | CastException | TransientNetworkDisconnectionException
                | NoConnectionException | IllegalArgumentException e) {
            LogHelper.e(TAG, e, "Exception pausing cast playback");
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void seekTo(int position) {
        if (mCurrentMediaId == null) {
            if (mCallback != null) {
                mCallback.onError("seekTo cannot be calling in the absence of mediaId.");
            }
            return;
        }
        try {
            if (VideoCastManager.getInstance().isRemoteMediaLoaded()) {
                VideoCastManager.getInstance().seek(position);
                mCurrentPosition = position;
            } else {
                mCurrentPosition = position;
                loadMedia(mCurrentMediaId, false);
            }
        } catch (TransientNetworkDisconnectionException | NoConnectionException |
                JSONException | IllegalArgumentException e) {
            LogHelper.e(TAG, e, "Exception pausing cast playback");
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void setCurrentMediaId(String mediaId) {
        this.mCurrentMediaId = mediaId;
    }

    @Override
    public String getCurrentMediaId() {
        return mCurrentMediaId;
    }

    @Override
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public boolean isConnected() {
        return VideoCastManager.getInstance().isConnected();
    }

    @Override
    public boolean isPlaying() {
        try {
            return VideoCastManager.getInstance().isConnected() &&
                    VideoCastManager.getInstance().isRemoteMediaPlaying();
        } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
            LogHelper.e(TAG, e, "Exception calling isRemoteMoviePlaying");
        }
        return false;
    }

    @Override
    public int getState() {
        return mState;
    }

    private void loadMedia(String mediaId, boolean autoPlay) throws
            TransientNetworkDisconnectionException, NoConnectionException, JSONException {
        String musicId = MediaIDHelper.extractMusicIDFromMediaID(mediaId);
        MediaMetadataCompat track = mMusicProvider.getMusic(musicId);
        if (track == null) {
            throw new IllegalArgumentException("Invalid mediaId " + mediaId);
        }
        if (!TextUtils.equals(mediaId, mCurrentMediaId)) {
            mCurrentMediaId = mediaId;
            mCurrentPosition = 0;
        }
        JSONObject customData = new JSONObject();
        customData.put(ITEM_ID, mediaId);
        MediaInfo media = toCastMediaMetadata(track, customData);
        VideoCastManager.getInstance().loadMedia(media, autoPlay, mCurrentPosition, customData);
    }

    /**
     * Helper method to convert a {@link android.media.MediaMetadata} to a
     * {@link com.google.android.gms.cast.MediaInfo} used for sending media to the receiver app.
     *
     * @param track {@link com.google.android.gms.cast.MediaMetadata}
     * @param customData custom data specifies the local mediaId used by the player.
     * @return mediaInfo {@link com.google.android.gms.cast.MediaInfo}
     */
    private static MediaInfo toCastMediaMetadata(MediaMetadataCompat track,
                                                 JSONObject customData) {
        MediaMetadata mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        mediaMetadata.putString(MediaMetadata.KEY_TITLE,
                track.getDescription().getTitle() == null ? "" :
                        track.getDescription().getTitle().toString());
        mediaMetadata.putString(MediaMetadata.KEY_SUBTITLE,
                track.getDescription().getSubtitle() == null ? "" :
                    track.getDescription().getSubtitle().toString());
        mediaMetadata.putString(MediaMetadata.KEY_ALBUM_ARTIST,
                track.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST));
        mediaMetadata.putString(MediaMetadata.KEY_ALBUM_TITLE,
                track.getString(MediaMetadataCompat.METADATA_KEY_ALBUM));
        WebImage image = new WebImage(
                new Uri.Builder().encodedPath(
                        track.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI))
                        .build());
        // First image is used by the receiver for showing the audio album art.
        mediaMetadata.addImage(image);
        // Second image is used by Cast Companion Library on the full screen activity that is shown
        // when the cast dialog is clicked.
        mediaMetadata.addImage(image);

        //noinspection ResourceType
        return new MediaInfo.Builder(track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE))
                .setContentType(MIME_TYPE_AUDIO_MPEG)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(mediaMetadata)
                .setCustomData(customData)
                .build();
    }

    private void setMetadataFromRemote() {
        // Sync: We get the customData from the remote media information and update the local
        // metadata if it happens to be different from the one we are currently using.
        // This can happen when the app was either restarted/disconnected + connected, or if the
        // app joins an existing session while the Chromecast was playing a queue.
        try {
            MediaInfo mediaInfo = VideoCastManager.getInstance().getRemoteMediaInformation();
            if (mediaInfo == null) {
                return;
            }
            JSONObject customData = mediaInfo.getCustomData();

            if (customData != null && customData.has(ITEM_ID)) {
                String remoteMediaId = customData.getString(ITEM_ID);
                if (!TextUtils.equals(mCurrentMediaId, remoteMediaId)) {
                    mCurrentMediaId = remoteMediaId;
                    if (mCallback != null) {
                        mCallback.setCurrentMediaId(remoteMediaId);
                    }
                    updateLastKnownStreamPosition();
                }
            }
        } catch (TransientNetworkDisconnectionException | NoConnectionException | JSONException e) {
            LogHelper.e(TAG, e, "Exception processing update metadata");
        }

    }

    private void updatePlaybackState() {
        int status = VideoCastManager.getInstance().getPlaybackStatus();
        int idleReason = VideoCastManager.getInstance().getIdleReason();

        LogHelper.d(TAG, "onRemoteMediaPlayerStatusUpdated ", status);

        // Convert the remote playback states to media playback states.
        switch (status) {
            case MediaStatus.PLAYER_STATE_IDLE:
                if (idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    if (mCallback != null) {
                        mCallback.onCompletion();
                    }
                }
                break;
            case MediaStatus.PLAYER_STATE_BUFFERING:
                mState = PlaybackStateCompat.STATE_BUFFERING;
                if (mCallback != null) {
                    mCallback.onPlaybackStatusChanged(mState);
                }
                break;
            case MediaStatus.PLAYER_STATE_PLAYING:
                mState = PlaybackStateCompat.STATE_PLAYING;
                setMetadataFromRemote();
                if (mCallback != null) {
                    mCallback.onPlaybackStatusChanged(mState);
                }
                break;
            case MediaStatus.PLAYER_STATE_PAUSED:
                mState = PlaybackStateCompat.STATE_PAUSED;
                setMetadataFromRemote();
                if (mCallback != null) {
                    mCallback.onPlaybackStatusChanged(mState);
                }
                break;
            default: // case unknown
                LogHelper.d(TAG, "State default : ", status);
                break;
        }
    }
}
