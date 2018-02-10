package com.afollestad.materialcamera.internal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialcamera.CameraUriInterface;
import com.afollestad.materialcamera.MaterialCamera;
import com.afollestad.materialcamera.R;
import com.afollestad.materialcamera.util.CameraUtil;
import com.afollestad.materialcamera.util.Degrees;
import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import java.io.File;

import static android.app.Activity.RESULT_CANCELED;
import static com.afollestad.materialcamera.internal.BaseCaptureActivity.CAMERA_POSITION_BACK;
import static com.afollestad.materialcamera.internal.BaseCaptureActivity.FLASH_MODE_ALWAYS_ON;
import static com.afollestad.materialcamera.internal.BaseCaptureActivity.FLASH_MODE_AUTO;
import static com.afollestad.materialcamera.internal.BaseCaptureActivity.FLASH_MODE_OFF;

/**
 * @author Aidan Follestad (afollestad)
 */
abstract class BaseCameraFragment extends Fragment implements CameraUriInterface, View.OnClickListener {
  private int mLastRotation;
  protected ImageButton mButtonFacing;
  protected ImageButton mButtonFlash;
  protected ImageButton mButtonStillshot;
  protected ImageButton mButtonVideo;
  protected OrientationEventListener mOrientationEventListener;
  protected TextView mDelayStartCountdown;
  protected TextView mRecordDuration;

  private boolean mIsRecording;
  protected String mOutputUri;
  protected BaseCaptureInterface mInterface;
  protected Handler mPositionHandler;
  protected MediaRecorder mMediaRecorder;
  @Nullable
  protected String externalLocalAudioPath;

  protected static void LOG(Object context, String message) {
    Log.d(
        context instanceof Class<?>
            ? ((Class<?>) context).getSimpleName()
            : context.getClass().getSimpleName(),
        message);
  }

  private final Runnable mPositionUpdater =
      new Runnable() {
        @Override
        public void run() {
          if (mInterface == null || mRecordDuration == null) {
            return;
          }
          final long mRecordStart = mInterface.getRecordingStart();
          final long mRecordEnd = mInterface.getRecordingEnd();
          if (mRecordStart == -1 && mRecordEnd == -1) {
            return;
          }
          final long now = System.currentTimeMillis();
          if (mRecordEnd != -1) {
            if (now >= mRecordEnd) {
              stopRecordingVideo(true);
              Toast.makeText(getContext(), R.string.time_limit_exceeded, Toast.LENGTH_LONG).show();
            } else {
              final long diff = now - mRecordStart;
              mRecordDuration.setText(String.format("%s", CameraUtil.getDurationString(diff)));
            }
          } else {
            mRecordDuration.setText(CameraUtil.getDurationString(now - mRecordStart));
          }
          if (mPositionHandler != null) {
            mPositionHandler.postDelayed(this, 1000);
          }
        }
      };

  @Override
  public final View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    if (mOrientationEventListener == null) {
      mOrientationEventListener = new OrientationEventListener(getActivity(), SensorManager.SENSOR_DELAY_UI) {
        @Override
        public void onOrientationChanged(int angle) {
          // Check if current rotation is landscape but different than before
          int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
          if ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && rotation != mLastRotation
              && (mLastRotation == Surface.ROTATION_270 || mLastRotation == Surface.ROTATION_90)) {
            if (!mIsRecording) {
              Log.d("OrientationEvent", "angle changed" + rotation);
              // Reset camera
              mLastRotation = rotation;
              reset();
            }
          }
        }
      };
    }
    return inflater.inflate(R.layout.mcam_fragment_videocapture, container, false);
  }

  protected abstract void reset();

  protected void setImageRes(ImageView iv, @DrawableRes int res) {
    iv.setImageDrawable(ContextCompat.getDrawable(getActivity(), res));
  }

  @SuppressLint("SetTextI18n")
  @Override
  public void onViewCreated(View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    mDelayStartCountdown = view.findViewById(R.id.delayStartCountdown);
    mButtonVideo = view.findViewById(R.id.video);
    mButtonStillshot = view.findViewById(R.id.stillshot);
    mRecordDuration = view.findViewById(R.id.recordDuration);
    mButtonFacing = view.findViewById(R.id.facing);
    if (mInterface.shouldHideCameraFacing() || CameraUtil.isChromium()) {
      mButtonFacing.setVisibility(View.GONE);
    } else {
      setImageRes(
          mButtonFacing,
          mInterface.getCurrentCameraPosition() == CAMERA_POSITION_BACK
              ? mInterface.iconFrontCamera()
              : mInterface.iconRearCamera());
    }

    mButtonFlash = view.findViewById(R.id.flash);
    setupFlashMode();

    mButtonVideo.setOnClickListener(this);
    mButtonStillshot.setOnClickListener(this);
    mButtonFacing.setOnClickListener(this);
    mButtonFlash.setOnClickListener(this);

    if (mMediaRecorder != null && mIsRecording) {
      setImageRes(mButtonVideo, mInterface.iconStop());
    } else {
      setImageRes(mButtonVideo, mInterface.iconRecord());
      mInterface.setDidRecord(false);
    }

    if (savedInstanceState != null) {
      mOutputUri = savedInstanceState.getString("output_uri");
    }

    if (mInterface.useStillshot()) {
      mButtonVideo.setVisibility(View.GONE);
      mRecordDuration.setVisibility(View.GONE);
      mButtonStillshot.setVisibility(View.VISIBLE);
      setImageRes(mButtonStillshot, mInterface.iconStillshot());
      mButtonFlash.setVisibility(View.VISIBLE);
    }

    if (mInterface.autoRecordDelay() < 1000) {
      mDelayStartCountdown.setVisibility(View.GONE);
    } else {
      mDelayStartCountdown.setText(Long.toString(mInterface.autoRecordDelay() / 1000));
    }

    setupExternalLocalAudioContent();
  }

  protected void onFlashModesLoaded() {
    if (getCurrentCameraPosition() != BaseCaptureActivity.CAMERA_POSITION_FRONT) {
      invalidateFlash(false);
    }
  }

  private boolean mDidAutoRecord = false;
  private Handler mDelayHandler;
  private int mDelayCurrentSecond = -1;

  protected void onCameraOpened() {
    if (mDidAutoRecord
        || mInterface == null
        || mInterface.useStillshot()
        || mInterface.autoRecordDelay() < 0
        || getActivity() == null) {
      mDelayStartCountdown.setVisibility(View.GONE);
      mDelayHandler = null;
      return;
    }
    mDidAutoRecord = true;
    mButtonFacing.setVisibility(View.GONE);

    if (mInterface.autoRecordDelay() == 0) {
      mDelayStartCountdown.setVisibility(View.GONE);
      mIsRecording = startRecordingVideo();
      mDelayHandler = null;
      return;
    }

    mDelayHandler = new Handler();
    mButtonVideo.setEnabled(false);

    if (mInterface.autoRecordDelay() < 1000) {
      // Less than a second delay
      mDelayStartCountdown.setVisibility(View.GONE);
      mDelayHandler.postDelayed(
          new Runnable() {
            @Override
            public void run() {
              if (!isAdded() || getActivity() == null || mIsRecording) {
                return;
              }
              mButtonVideo.setEnabled(true);
              mIsRecording = startRecordingVideo();
              mDelayHandler = null;
            }
          },
          mInterface.autoRecordDelay());
      return;
    }

    mDelayStartCountdown.setVisibility(View.VISIBLE);
    mDelayCurrentSecond = (int) mInterface.autoRecordDelay() / 1000;
    mDelayHandler.postDelayed(
        new Runnable() {
          @SuppressLint("SetTextI18n")
          @Override
          public void run() {
            if (!isAdded() || getActivity() == null || mIsRecording) {
              return;
            }
            mDelayCurrentSecond -= 1;
            mDelayStartCountdown.setText(Integer.toString(mDelayCurrentSecond));

            if (mDelayCurrentSecond == 0) {
              mDelayStartCountdown.setVisibility(View.GONE);
              mButtonVideo.setEnabled(true);
              mIsRecording = startRecordingVideo();
              mDelayHandler = null;
              return;
            }

            mDelayHandler.postDelayed(this, 1000);
          }
        },
        1000);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    mButtonVideo = null;
    mButtonStillshot = null;
    mButtonFacing = null;
    mButtonFlash = null;
    mRecordDuration = null;
  }

  @Override
  public void onResume() {
    super.onResume();
    if (mInterface != null && mInterface.hasLengthLimit()) {
      if (mInterface.countdownImmediately() || mInterface.getRecordingStart() > -1) {
        if (mInterface.getRecordingStart() == -1) {
          mInterface.setRecordingStart(System.currentTimeMillis());
        }
        startCounter();
      }
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public final void onAttach(Activity activity) {
    super.onAttach(activity);
    mInterface = (BaseCaptureInterface) activity;
  }

  @NonNull
  protected final File getOutputMediaFile() {
    return CameraUtil.makeTempFile(
        getActivity(), getArguments().getString(CameraIntentKey.SAVE_DIR), "VID_", ".mp4");
  }

  @NonNull
  protected final File getOutputPictureFile() {
    return CameraUtil.makeTempFile(
        getActivity(), getArguments().getString(CameraIntentKey.SAVE_DIR), "IMG_", ".jpg");
  }

  public void openCamera() {
    mOrientationEventListener.enable();
    mLastRotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
  }

  public abstract void closeCamera();

  public void cleanup() {
    closeCamera();
    releaseRecorder();
    stopCounter();
    mOrientationEventListener.disable();
  }

  public abstract void takeStillshot();

  public abstract void onPreferencesUpdated();

  @Override
  public void onPause() {
    super.onPause();
    cleanup();
  }

  @Override
  public final void onDetach() {
    super.onDetach();
    mInterface = null;
  }

  public final void startCounter() {
    if (mPositionHandler == null) {
      mPositionHandler = new Handler();
    } else {
      mPositionHandler.removeCallbacks(mPositionUpdater);
    }
    mPositionHandler.post(mPositionUpdater);
  }

  @BaseCaptureActivity.CameraPosition
  public final int getCurrentCameraPosition() {
    if (mInterface == null) {
      return BaseCaptureActivity.CAMERA_POSITION_UNKNOWN;
    }
    return mInterface.getCurrentCameraPosition();
  }

  public final int getCurrentCameraId() {
    if (mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK) {
      return (Integer) mInterface.getBackCamera();
    } else {
      return (Integer) mInterface.getFrontCamera();
    }
  }

  public final void stopCounter() {
    if (mPositionHandler != null) {
      mPositionHandler.removeCallbacks(mPositionUpdater);
      mPositionHandler = null;
    }
  }

  public final void releaseRecorder() {
    if (mMediaRecorder != null) {
      if (mIsRecording) {
        try {
          mMediaRecorder.stop();
        } catch (Throwable t) {
          //noinspection ResultOfMethodCallIgnored
          new File(mOutputUri).delete();
          t.printStackTrace();
        }
        mIsRecording = false;
      }
      mMediaRecorder.reset();
      mMediaRecorder.release();
      mMediaRecorder = null;
      if (soundPool != null) {
        stopExternalLocalAudio();
        soundPool.release();
      }
    }
  }

  private static final int MAX_SOUND_POOL_STREAMS = 1;
  private static final int NORMAL_PRIORITY = 1;
  private int mySoundId;
  @Nullable
  SoundPool soundPool;

  public void setupExternalLocalAudioContent() {
    String extraAudioFullPath = getArguments().getString(CameraIntentKey.EXTERNAL_LOCAL_AUDIO_PATH);
    if (extraAudioFullPath != null && new File(extraAudioFullPath).exists()) {
      this.externalLocalAudioPath = extraAudioFullPath;

      soundPool = new SoundPool(MAX_SOUND_POOL_STREAMS, AudioManager.STREAM_MUSIC, 100);
      if (getActivity() != null) {
        this.mySoundId = this.soundPool.load(extraAudioFullPath, 1);
      }
    }
  }

  private void playExternalLocalAudio() {
    if (soundPool != null) {
      AudioManager mgr = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);

      int streamVolume = 1;
      if (mgr != null) {
        streamVolume = mgr.getStreamVolume(AudioManager.STREAM_MUSIC);
      }

      this.soundPool.play(this.mySoundId, streamVolume, streamVolume,
          NORMAL_PRIORITY, -1, 1);
    }
  }

  private void stopExternalLocalAudio() {
    if (soundPool != null) {
      this.soundPool.stop(this.mySoundId);
    }
  }

  public boolean startRecordingVideo() {
    if (mInterface != null && mInterface.hasLengthLimit() && !mInterface.countdownImmediately()) {
      // Countdown wasn't started in onResume, start it now
      if (mInterface.getRecordingStart() == -1) {
        mInterface.setRecordingStart(System.currentTimeMillis());
      }
      startCounter();
    }

    final int orientation = Degrees.getActivityOrientation(getActivity());
    //noinspection ResourceType
    getActivity().setRequestedOrientation(orientation);
    mInterface.setDidRecord(true);
    playExternalLocalAudio();
    return true;
  }

  public void stopRecordingVideo(boolean reachedZero) {
    getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
  }

  @Override
  public final void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString("output_uri", mOutputUri);
  }

  @Override
  public final String getOutputUri() {
    return mOutputUri;
  }

  protected final void throwError(Exception e) {
    Activity act = getActivity();
    if (act != null) {
      act.setResult(RESULT_CANCELED, new Intent().putExtra(MaterialCamera.ERROR_EXTRA, e));
      act.finish();
    }
  }

  @Override
  public void onClick(View view) {
    final int id = view.getId();
    if (id == R.id.facing) {
      mInterface.toggleCameraPosition();
      setImageRes(
          mButtonFacing,
          mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK
              ? mInterface.iconFrontCamera()
              : mInterface.iconRearCamera());
      closeCamera();
      openCamera();
      setupFlashMode();
    } else if (id == R.id.video) {
      if (mIsRecording) {
        stopRecordingVideo(false);
        mIsRecording = false;
      } else {
        if (getArguments().getBoolean(CameraIntentKey.SHOW_PORTRAIT_WARNING, true)
            && Degrees.isPortrait(getActivity())) {
          new MaterialDialog.Builder(getActivity())
              .title(R.string.mcam_portrait)
              .content(R.string.mcam_portrait_warning)
              .positiveText(R.string.mcam_yes)
              .negativeText(android.R.string.cancel)
              .onPositive(
                  new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(
                        @NonNull MaterialDialog materialDialog,
                        @NonNull DialogAction dialogAction) {
                      mIsRecording = startRecordingVideo();
                    }
                  })
              .show();
        } else {
          mIsRecording = startRecordingVideo();
        }
      }
    } else if (id == R.id.stillshot) {
      takeStillshot();
    } else if (id == R.id.flash) {
      invalidateFlash(true);
    }
  }

  private void invalidateFlash(boolean toggle) {
    if (toggle) {
      mInterface.toggleFlashMode();
    }
    setupFlashMode();
    onPreferencesUpdated();
  }

  private void setupFlashMode() {
    if (mInterface.shouldHideFlash()) {
      mButtonFlash.setVisibility(View.GONE);
      return;
    } else {
      mButtonFlash.setVisibility(View.VISIBLE);
    }

    final int res;
    switch (mInterface.getFlashMode()) {
      case FLASH_MODE_AUTO:
        res = mInterface.iconFlashAuto();
        break;
      case FLASH_MODE_ALWAYS_ON:
        res = mInterface.iconFlashOn();
        break;
      case FLASH_MODE_OFF:
      default:
        res = mInterface.iconFlashOff();
    }

    setImageRes(mButtonFlash, res);
  }
}
