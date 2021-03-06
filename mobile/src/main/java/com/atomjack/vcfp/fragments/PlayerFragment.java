package com.atomjack.vcfp.fragments;

import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.view.GestureDetectorCompat;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atomjack.shared.Intent;
import com.atomjack.shared.NewLogger;
import com.atomjack.shared.PlayerState;
import com.atomjack.shared.Preferences;
import com.atomjack.shared.WearConstants;
import com.atomjack.vcfp.Feedback;
import com.atomjack.vcfp.MediaOptionsDialog;
import com.atomjack.vcfp.R;
import com.atomjack.vcfp.VoiceControlForPlexApplication;
import com.atomjack.vcfp.activities.MainActivity;
import com.atomjack.vcfp.interfaces.ActiveConnectionHandler;
import com.atomjack.vcfp.interfaces.ActivityListener;
import com.atomjack.vcfp.interfaces.InputStreamHandler;
import com.atomjack.vcfp.model.Connection;
import com.atomjack.vcfp.model.PlexClient;
import com.atomjack.vcfp.model.PlexMedia;
import com.atomjack.vcfp.model.PlexTrack;
import com.atomjack.vcfp.model.PlexVideo;
import com.atomjack.vcfp.model.Stream;
import com.atomjack.vcfp.net.PlexHttpClient;
import com.atomjack.vcfp.services.PlexSearchService;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;

import butterknife.ButterKnife;
import butterknife.OnClick;
import cz.fhucho.android.util.SimpleDiskCache;

public abstract class PlayerFragment extends Fragment
        implements SeekBar.OnSeekBarChangeListener {
  protected NewLogger logger;
  protected PlexMedia nowPlayingMedia;
  protected ArrayList<? extends PlexMedia> nowPlayingPlaylist = new ArrayList<>();
  protected int currentMediaIndex = 0; // the index of the currently playing media in the playlist
  protected PlexClient client;

  private View mainView;

  protected Feedback feedback;

  private boolean doingMic = false;

  protected PlayerState currentState = PlayerState.STOPPED;

  protected int position = -1;

  int screenWidth = -1;
  int screenHeight = -1;

  SimpleDiskCache simpleDiskCache;

  // UI Elements
  protected boolean resumePlayback;
//  @Bind(R.id.playButton)
  protected ImageButton playButton;
//  @Bind(R.id.pauseButton)
  protected ImageButton pauseButton;
//  @Bind(R.id.playPauseSpinner)
  protected ProgressBar playPauseSpinner;
  protected boolean isSeeking = false;
  protected SeekBar seekBar;
  protected TextView currentTimeDisplay;
  protected TextView durationDisplay;
  protected ImageView nowPlayingPoster;

  protected boolean fromWear = false;

  protected GestureDetectorCompat mDetector;

  protected MediaOptionsDialog mediaOptionsDialog;

  private int layout = -1;

  private LayoutInflater inflater;

  protected ActivityListener activityListener;

  @Nullable
  @Override
  public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    if(savedInstanceState != null) {
      logger.d("onSavedInstanceState is not null");
      nowPlayingMedia = savedInstanceState.getParcelable(Intent.EXTRA_MEDIA);
      nowPlayingPlaylist = savedInstanceState.getParcelableArrayList(Intent.EXTRA_PLAYLIST);
//      mediaContainer = savedInstanceState.getParcelable(Intent.EXTRA_ALBUM);
      fromWear = savedInstanceState.getBoolean(WearConstants.FROM_WEAR, false);
      layout = savedInstanceState.getInt(Intent.EXTRA_LAYOUT);
      client = savedInstanceState.getParcelable(Intent.EXTRA_CLIENT);
      currentState = (PlayerState) savedInstanceState.getSerializable(Intent.EXTRA_CURRENT_STATE);
      logger.d("got current state: %s", currentState);
    }
    logger.d("layout: %d", layout);

    if(layout == -1) { // Layout can't be found, so alert activity something went wrong so it closes fragment out
      mainView = inflater.inflate(R.layout.player_fragment, container, false);
      activityListener.onLayoutNotFound();
    } else {
      mainView = inflater.inflate(layout, container, false);

      ButterKnife.bind(getActivity(), mainView);

      this.inflater = inflater;

      showNowPlaying();
      setState(currentState); // Call this right away so that the play/pause spinner gets changed to the appropriate button, when orientation is changing while playing or paused
      seekBar = (SeekBar) mainView.findViewById(R.id.seekBar);
      seekBar.setOnSeekBarChangeListener(this);
      seekBar.setMax(nowPlayingMedia.duration / 1000);
      if(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.RESUME, false))
        seekBar.setProgress(Integer.parseInt(nowPlayingMedia.viewOffset) / 1000);
      else
        seekBar.setProgress(0);

      setCurrentTimeDisplay(getOffset(nowPlayingMedia));
      durationDisplay.setText(VoiceControlForPlexApplication.secondsToTimecode(nowPlayingMedia.duration / 1000));
    }
    return mainView;
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putParcelable(Intent.EXTRA_MEDIA, nowPlayingMedia);
    outState.putParcelableArrayList(Intent.EXTRA_PLAYLIST, nowPlayingPlaylist);
    outState.putInt(Intent.EXTRA_LAYOUT, layout);
    outState.putParcelable(Intent.EXTRA_CLIENT, client);
    outState.putSerializable(Intent.EXTRA_CURRENT_STATE, currentState);
  }


  public PlayerFragment() {
    simpleDiskCache = VoiceControlForPlexApplication.getInstance().mSimpleDiskCache;
    logger = new NewLogger(this);
  }

  public void init(int layout, PlexClient client, PlexMedia media, ArrayList<? extends PlexMedia> playlist, boolean fromWear) {
    this.layout = layout;
    this.client = client;
    nowPlayingMedia = media;
    nowPlayingPlaylist = playlist == null ? new ArrayList<PlexMedia>() : playlist;
    this.fromWear = fromWear;
    currentMediaIndex = 0;
  }

  public void mediaChanged(PlexMedia media) {
    nowPlayingMedia = media;
    if(mainView != null) { // Can't do anything with UI elements until mainView is defined and set
      showNowPlaying();
      setCurrentTimeDisplay(getOffset(nowPlayingMedia));
      seekBar.setMax(nowPlayingMedia.duration / 1000);
      seekBar.setProgress(Integer.parseInt(nowPlayingMedia.viewOffset) / 1000);
      durationDisplay.setText(VoiceControlForPlexApplication.secondsToTimecode(nowPlayingMedia.duration / 1000));
    }
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);

    feedback = ((MainActivity)context).feedback;
    try {
      activityListener = (ActivityListener) context;
    } catch (ClassCastException e) {
      throw new ClassCastException(context.toString()
              + " must implement ActivityListener");
    }
  }

  public PlexMedia getNowPlayingMedia() {
    return nowPlayingMedia;
  }

  public void showNowPlaying() {
    if(mainView == null)
      return;
    if (nowPlayingMedia instanceof PlexVideo) {
      PlexVideo video = (PlexVideo)nowPlayingMedia;
      if(video.isMovie() || video.isClip()) {
        TextView title = (TextView) mainView.findViewById(R.id.nowPlayingTitle);
        title.setText(video.title);
      } else {
        TextView showTitle = (TextView)mainView.findViewById(R.id.nowPlayingShowTitle);
        showTitle.setText(video.grandparentTitle);
        showTitle.setSelected(true);
        TextView episodeTitle = (TextView)mainView.findViewById(R.id.nowPlayingEpisodeTitle);
        if(video.parentIndex != null && video.index != null) {
          episodeTitle.setText(String.format("%s (s%02de%02d)", video.title, Integer.parseInt(video.parentIndex), Integer.parseInt(video.index)));
        } else {
          episodeTitle.setText(video.title);
        }
        episodeTitle.setSelected(true);
      }

    } else if (nowPlayingMedia instanceof PlexTrack) {
      PlexTrack track = (PlexTrack)nowPlayingMedia;

      TextView artist = (TextView)mainView.findViewById(R.id.nowPlayingArtist);
      logger.d("Setting artist to %s", track.grandparentTitle);
      artist.setText(track.grandparentTitle);
      TextView album = (TextView)mainView.findViewById(R.id.nowPlayingAlbum);
      album.setText(track.parentTitle);
      TextView title = (TextView)mainView.findViewById(R.id.nowPlayingTitle);
      title.setText(track.title);
    }
    TextView nowPlayingOnClient = (TextView)mainView.findViewById(R.id.nowPlayingOnClient);
    nowPlayingOnClient.setText(getResources().getString(R.string.now_playing_on) + " " + client.name);

    // Hide stream options on chromecast, for now
    if (mainView.findViewById(R.id.mediaOptionsButton) != null && nowPlayingMedia.getStreams(Stream.SUBTITLE).size() == 0 && nowPlayingMedia.getStreams(Stream.AUDIO).size() == 0) {
      mainView.findViewById(R.id.mediaOptionsButton).setVisibility(View.GONE);
    }

    logger.d("Setting thumb in showNowPlaying");
    attachUIElements();

    final FrameLayout nowPlayingPosterContainer = (FrameLayout)mainView.findViewById(R.id.nowPlayingPosterContainer);
    logger.d("nowPlayingPosterContainer: %s", nowPlayingPosterContainer);
    if(nowPlayingPosterContainer != null) {
      if(screenWidth != -1) {
        setThumb(screenWidth, screenHeight);
      } else {
        ViewTreeObserver vto = nowPlayingPosterContainer.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
          @Override
          public void onGlobalLayout() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
              nowPlayingPosterContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            screenWidth = nowPlayingPosterContainer.getMeasuredWidth();
            screenHeight = nowPlayingPosterContainer.getMeasuredHeight();
            String[] prefs = VoiceControlForPlexApplication.getMediaPosterPrefs(nowPlayingMedia);

            if (VoiceControlForPlexApplication.getInstance().prefs.get(prefs[0], -1) == -1) {
              VoiceControlForPlexApplication.getInstance().prefs.put(prefs[0], screenWidth);
              VoiceControlForPlexApplication.getInstance().prefs.put(prefs[1], screenHeight);
            }
            logger.d("Found dimensions: %d/%d", screenWidth, screenHeight);
            setThumb(screenWidth, screenHeight);
          }
        });
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    logger.d("onResume");
    if(doingMic) {
      doPlay();
      doingMic = false;
    }
  }

  private void setThumb(final byte[] bytes) {
    logger.d("Setting thumb with %d bytes", bytes.length);

    if(getActivity() != null) {
      getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          Bitmap posterBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
          nowPlayingPoster.setImageBitmap(posterBitmap);
        }
      });
    }
  }

  protected void setThumb(int maxWidth, int maxHeight) {
    String thumb = nowPlayingMedia.thumb;

    logger.d("setThumb: %s", thumb);
    if(nowPlayingMedia instanceof PlexVideo) {
      PlexVideo video = (PlexVideo)nowPlayingMedia;
      thumb = video.isMovie() || video.isClip() ? video.thumb : video.grandparentThumb;
      logger.d("orientation: %s, type: %s", VoiceControlForPlexApplication.getOrientation(), video.type);
      if(video.isClip()) {

      }

      if(VoiceControlForPlexApplication.getOrientation() == Configuration.ORIENTATION_LANDSCAPE) {
        thumb = video.art;
      }
    } else if(nowPlayingMedia instanceof PlexTrack) {
      PlexTrack track = (PlexTrack)nowPlayingMedia;
      if(VoiceControlForPlexApplication.getOrientation() == Configuration.ORIENTATION_LANDSCAPE)
        thumb = track.art;
      else
        thumb = track.thumb;
    }

    if(thumb != null && thumb.equals("")) {
      thumb = null;
    }
    logger.d("thumb: %s", thumb);

    SimpleDiskCache.InputStreamEntry thumbEntry = null;
    try {
      thumbEntry = simpleDiskCache.getInputStream(nowPlayingMedia.getCacheKey(thumb != null ? thumb : nowPlayingMedia.key));
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    if (thumbEntry != null) {
      logger.d("Using cached thumb: %s", nowPlayingMedia.getCacheKey(thumb));
      try {
        setThumb(IOUtils.toByteArray(thumbEntry.getInputStream()));
      } catch (Exception e) { e.printStackTrace(); }
    } else {
      logger.d("Downloading thumb");
      getThumb(maxWidth, maxHeight, thumb, nowPlayingMedia);
    }
  }

  private void getThumb(final int maxWidth, final int maxHeight, final String thumb, final PlexMedia media) {
    if(thumb == null) {
      InputStream is = getResources().openRawResource(+ R.drawable.ic_launcher);
      try {
        InputStream iss = new ByteArrayInputStream(IOUtils.toByteArray(is));
        iss.reset();
        simpleDiskCache.put(media.getCacheKey(media.key), iss);
        setThumb(IOUtils.toByteArray(iss));
      } catch (IOException e) {
        logger.d("Exception getting/saving thumb");
        e.printStackTrace();
      }
    } else {
      media.server.findServerConnection(new ActiveConnectionHandler() {
        @Override
        public void onSuccess(Connection connection) {
          String path = String.format("/photo/:/transcode?width=%d&height=%d&url=%s", maxWidth, maxHeight, Uri.encode(String.format("http://127.0.0.1:32400%s", thumb)));
          String url = media.server.buildURL(connection, path);
          logger.d("thumb url: %s", url);

          PlexHttpClient.getThumb(url, new InputStreamHandler() {
            @Override
            public void onSuccess(InputStream is) {
              try {
                simpleDiskCache.put(media.getCacheKey(thumb), is);
                setThumb(maxWidth, maxHeight);
              } catch (Exception e) {
                logger.d("Exception getting/saving thumb");
                e.printStackTrace();
              }
            }
          });
        }

        @Override
        public void onFailure(int statusCode) {

        }
      });
    }
  }

  private void attachUIElements() {
    nowPlayingPoster = (ImageView) mainView.findViewById(R.id.nowPlayingPoster);

    ImageButton rewindButton = (ImageButton)mainView.findViewById(R.id.rewindButton);
    if(rewindButton != null)
      rewindButton.setOnClickListener(v -> doRewind());

    ImageButton forwardButton = (ImageButton)mainView.findViewById(R.id.forwardButton);
    if(forwardButton != null)
      forwardButton.setOnClickListener(v -> doForward());

    ImageButton previousButton = (ImageButton)mainView.findViewById(R.id.previousButton);
    if(previousButton != null) {
      if(nowPlayingPlaylist == null || nowPlayingPlaylist.size() == 1) {
        previousButton.setVisibility(View.GONE);
      } else {
        previousButton.setAlpha(1.0f);
        previousButton.setOnClickListener(v -> doPrevious());
      }
    }

    playButton = (ImageButton)mainView.findViewById(R.id.playButton);
    pauseButton = (ImageButton)mainView.findViewById(R.id.pauseButton);
    playPauseSpinner = (ProgressBar)mainView.findViewById(R.id.playPauseSpinner);

    ImageButton nextButton = (ImageButton)mainView.findViewById(R.id.nextButton);
    if(nextButton != null) {
      if(nowPlayingPlaylist == null || nowPlayingPlaylist.size() == 1) {
        nextButton.setVisibility(View.GONE);
      } else {
        nextButton.setAlpha(1.0f);
        nextButton.setOnClickListener(new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            doNext();
          }
        });
      }
    }

//    List<? extends PlexMedia> list = null;
//    if(mediaContainer.videos.size() > 0) {
//      list = mediaContainer.videos;
//    } else if(mediaContainer.tracks.size() > 0) {
//      list = mediaContainer.tracks;
//    }
    if(nowPlayingPlaylist != null) {
      int index = 0;
      for(PlexMedia m : nowPlayingPlaylist) {
        if(m.key.equals(nowPlayingMedia.key))
          break;
        index++;
      }
      logger.d("Index: %d", index);
      if(index == 0)
        previousButton.setAlpha(0.4f);
      else if(index+1 == nowPlayingPlaylist.size())
        nextButton.setAlpha(0.4f);
    }

    ImageButton mediaOptionsButton = (ImageButton)mainView.findViewById(R.id.mediaOptionsButton);
    if(mediaOptionsButton != null) {
      mediaOptionsButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          doMediaOptions();
        }
      });
    }

    ImageButton micButton = (ImageButton)mainView.findViewById(R.id.micButton);
    if(micButton != null) {
      micButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          doMic();
        }
      });
    }

    ImageButton stopButton = (ImageButton)mainView.findViewById(R.id.stopButton);
    stopButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        doStop();
      }
    });

    currentTimeDisplay = (TextView)mainView.findViewById(R.id.currentTimeView);
    durationDisplay = (TextView)mainView.findViewById(R.id.durationView);

    mDetector = new GestureDetectorCompat(getActivity(), new TouchGestureListener());
    LinearLayout target = (LinearLayout)mainView.findViewById(R.id.nowPlayingTapTarget);
    if(target != null) {
      target.setOnTouchListener(new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
          return mDetector.onTouchEvent(event);
        }
      });
    }
  }


  class TouchGestureListener extends GestureDetector.SimpleOnGestureListener {
    @Override
    public boolean onDown(MotionEvent e) {
      return true;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
      logger.d("Single tap.");
      if(currentState == PlayerState.PLAYING)
        doPause();
      else
        doPlay();
      return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
      float SWIPE_SPEED_THRESHOLD = 2000;

      try {
        float diffY = e2.getY() - e1.getY();
        float diffX = e2.getX() - e1.getX();
        if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(velocityX) >= SWIPE_SPEED_THRESHOLD) {

          if (diffX > 0) {
            logger.d("Doing forward via fling right");
            doForward();
          } else {
            logger.d("Doing back via fling left");
            doRewind();
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
      return true;
    }
  }

  // The follow methods are defined in the PlexPlayerFragment and CastPlayerFragment subclasses
  protected abstract void doRewind();
  protected abstract void doForward();
  @OnClick(R.id.playButton)
  protected abstract void doPlay();
  @OnClick(R.id.pauseButton)
  protected abstract void doPause();
  protected abstract void doStop();
  protected abstract void doNext();
  protected abstract void doPrevious();

  protected void doMic() {
    if(nowPlayingMedia.server != null) {
      if(currentState == PlayerState.PLAYING) {
        doingMic = true;
        doPause();
      }
      android.content.Intent serviceIntent = new android.content.Intent(getActivity(), PlexSearchService.class);

      serviceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_SERVER, VoiceControlForPlexApplication.gsonWrite.toJson(nowPlayingMedia.server));
      serviceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLIENT, VoiceControlForPlexApplication.gsonWrite.toJson(client));
      serviceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_RESUME, resumePlayback);
      serviceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_FROM_MIC, true);

      SecureRandom random = new SecureRandom();
      serviceIntent.setData(Uri.parse(new BigInteger(130, random).toString(32)));
      PendingIntent resultsPendingIntent = PendingIntent.getService(getActivity(), 0, serviceIntent, PendingIntent.FLAG_ONE_SHOT);

      android.content.Intent listenerIntent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
      listenerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
      listenerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, "voice.recognition.test");
      listenerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
      listenerIntent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, resultsPendingIntent);
      listenerIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, getResources().getString(R.string.voice_prompt));

      startActivity(listenerIntent);
    }
  }

  protected void doMediaOptions() {
    logger.d("doMediaOptions!!!");

    if(nowPlayingMedia == null) {
      return;
    }
    mediaOptionsDialog = new MediaOptionsDialog(getActivity(), nowPlayingMedia, client);
    mediaOptionsDialog.setStreamChangeListener(stream -> activityListener.setStream(stream));
    mediaOptionsDialog.show();
  }

  protected void setCurrentTimeDisplay(long seconds) {
    currentTimeDisplay.setText(VoiceControlForPlexApplication.secondsToTimecode(seconds));
  }

  protected int getOffset(PlexMedia media) {
    logger.d("getting offset, mediaoffset: %s", media.viewOffset);
    if((VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.RESUME, false) || resumePlayback) && media.viewOffset != null)
      return Integer.parseInt(media.viewOffset) / 1000;
    else
      return 0;
  }

  @Override
  public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    setCurrentTimeDisplay(progress);
  }

  @Override
  public void onStartTrackingTouch(SeekBar seekBar) {
    isSeeking = true;
  }

  public void setState(PlayerState newState) {
//    logger.d("setState: %s, current state: %s", newState, currentState);
    currentState = newState;
    if (playPauseSpinner != null && playButton != null && pauseButton != null) {
      playPauseSpinner.setVisibility(currentState == PlayerState.BUFFERING ? View.VISIBLE : View.INVISIBLE);
      playButton.setVisibility(currentState == PlayerState.PAUSED ? View.VISIBLE : View.INVISIBLE);
      pauseButton.setVisibility(currentState == PlayerState.PLAYING ? View.VISIBLE : View.INVISIBLE);
    }
  }

  public void setPosition(int position) {
    if(!isSeeking) {
      this.position = position;
      if (seekBar != null)
        seekBar.setProgress(position);
      else
        logger.d("Seekbar is null");
      if (currentTimeDisplay != null)
        setCurrentTimeDisplay(position);
    }
  }
}
