/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.exoplayer2.source.rtsp;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Handler;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManagerProvider;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider;
import com.google.android.exoplayer2.offline.FilteringManifestParser;
import com.google.android.exoplayer2.offline.StreamKey;
import com.google.android.exoplayer2.source.BaseMediaSource;
import com.google.android.exoplayer2.source.DefaultCompositeSequenceableLoaderFactory;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSourceEventListener;
import com.google.android.exoplayer2.source.MediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.MediaSourceFactory;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.source.rtsp.core.Client;
import com.google.android.exoplayer2.source.rtsp.media.MediaType;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;

import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static com.google.android.exoplayer2.C.TCP;
import static com.google.android.exoplayer2.source.rtsp.core.Client.RTSP_AUTO_DETECT;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

public final class RtspMediaSource extends BaseMediaSource implements Client.EventListener {

    static {
        ExoPlayerLibraryInfo.registerModule("goog.exo.rtsp");
    }

    /** Factory for {@link RtspMediaSource}. */
    public static final class Factory implements MediaSourceFactory {
        private boolean isLive;
        private boolean isCreateCalled =false;


        private final Client.Factory<? extends Client> factory;

        /**
         * Creates a factory for {@link RtspMediaSource}s.
         *
         * @param factory The factory from which read the media will
         *     be obtained.
         */
        public Factory(Client.Factory<? extends Client> factory) {
            this.factory = Assertions.checkNotNull(factory);
        }

        /**
         * Creates a new factory for {@link DashMediaSource}s.
         *
         * @param dataSourceFactory A factory for {@link DataSource} instances that will be used to load
         *     manifest and media data.
         */
        public Factory(DataSource.Factory dataSourceFactory) {
            this.factory = RtspDefaultClient.factory();
            this.factory.setDataSourceFactory(dataSourceFactory);
            this.factory.setFlags(Client.FLAG_ENABLE_RTCP_SUPPORT);
            this.factory.setNatMethod(Client.RTSP_NAT_DUMMY);
        }

        public Factory setIsLive(boolean isLive) {
            this.isLive = isLive;
            return this;
        }

        @Override
        public MediaSourceFactory setDrmSessionManagerProvider(
            @Nullable DrmSessionManagerProvider drmSessionManagerProvider) {
            return null;
        }

        @Override
        public MediaSourceFactory setDrmSessionManager(
            @Nullable DrmSessionManager drmSessionManager) {
            return null;
        }

        @Override
        public MediaSourceFactory setDrmHttpDataSourceFactory(
            @Nullable HttpDataSource.Factory drmHttpDataSourceFactory) {
            return null;
        }

        @Override
        public MediaSourceFactory setDrmUserAgent(@Nullable String userAgent) {
            return null;
        }

        @Override
        public MediaSourceFactory setLoadErrorHandlingPolicy(
            @Nullable LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            return null;
        }

        @Override
        public int[] getSupportedTypes() {
            return new int[0];
        }

        @Override
        public RtspMediaSource createMediaSource(MediaItem mediaItem) {
            checkNotNull(mediaItem);
            RtspMediaSource obj = new RtspMediaSource(mediaItem, this.factory, true);
            return obj;
        }

    }


    private final Uri uri;
    private final Client.Factory<? extends Client> factory;
    private EventDispatcher eventDispatcher;
    private MediaItem currentMediaItem = null;

    private Client client;
    private boolean isLive;
    private int prepareCount;

    private @C.TransportProtocol
    int transportProtocol;

    private DataSource dataSource;
    private Loader loader;
    private @Nullable TransferListener transferListener;
    private Handler handler;

    private RtspMediaSource(MediaItem mediaItem, Client.Factory<? extends Client> factory, boolean isLive) {
        checkNotNull(mediaItem.playbackProperties);

        currentMediaItem = mediaItem;
        this.uri = mediaItem.playbackProperties.uri;
        this.isLive = isLive;
        this.factory = factory;

        transportProtocol = TCP;
    }


    public boolean isTcp() { return transportProtocol == TCP; }

    public boolean isLive() {
        return isLive;
    }

    // MediaTrackSource implementation

    @Override
    public MediaItem getMediaItem() {
        return currentMediaItem;
    }


    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
        if (client == null) {
            throw new IOException();
        }
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        eventDispatcher = createEventDispatcher(id);
        return new RtspMediaPeriod(this,
                client,
                transferListener,
                eventDispatcher,
                allocator, null);
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
        ((RtspMediaPeriod) mediaPeriod).release();
    }

    protected void prepareSourceInternal(@Nullable TransferListener transferListener) {
        this.transferListener = transferListener;

        client = new Client.Builder(factory)
            .setUri(uri)
            .setListener(this)
            .setPlayer(getPlayer())
            .build();
        factory.getDataSourceFactory().createDataSource();
        eventDispatcher = createEventDispatcher(null);

        try {
            if (factory.getMode() == RTSP_AUTO_DETECT) {
                if (prepareCount++ > 0) {
                    client.retry();
                } else {
                    client.open();
                }

            } else {
                if (prepareCount == 0) {
                    client.open();
                }
            }
        } catch (IOException e) {
            eventDispatcher.loadError(null,
            C.DATA_TYPE_MEDIA_INITIALIZATION,
            0,
            null,
            0,
            null,
            0,
            0,
            e,
            false);
        }

    }

    @Override
    public void releaseSourceInternal() {
        if (client != null) {
            client.release();
            client = null;
        }
    }


    // Client.EventListener implementation

    @Override
    public void onMediaDescriptionInfoRefreshed(long durationUs) {
        refreshSourceInfo(new SinglePeriodTimeline(durationUs,
                durationUs != C.TIME_UNSET, false, true, null, getMediaItem()));

    }

    @Override
    public void onMediaDescriptionTypeUnSupported(MediaType mediaType) {
        if (eventDispatcher != null) {
            eventDispatcher.loadError(null,
                C.DATA_TYPE_MANIFEST,
                0,
                null,
                0,
                null,
                0,
                0,
                new IOException("Media Description Type [" + mediaType + "] is not supported"),
                false);

        }
    }

    @Override
    public void onTransportProtocolChanged(@C.TransportProtocol int protocol) {
        transportProtocol = protocol;
    }

    @Override
    public void onClientError(Throwable throwable) {
        if (eventDispatcher != null) {
            eventDispatcher.loadError(null,
                C.DATA_TYPE_MEDIA,
                0,
                null,
                0,
                null,
                0,
                0,
                (IOException)throwable,
                false);

        }
    }
}
