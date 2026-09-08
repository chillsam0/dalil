package app.organicmaps.sdk;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.Icon;
import app.organicmaps.sdk.downloader.Android7RootCertificateWorkaround;
import app.organicmaps.sdk.editor.OsmOAuth;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.location.SensorHelper;
import app.organicmaps.sdk.maplayer.isolines.IsolinesManager;
import app.organicmaps.sdk.maplayer.subway.SubwayManager;
import app.organicmaps.sdk.maplayer.traffic.TrafficManager;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.settings.StoragePathManager;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.ConnectionState;
import app.organicmaps.sdk.util.SharedPropertiesUtils;
import app.organicmaps.sdk.util.StorageUtils;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.sdk.util.log.LogsManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class OrganicMaps implements DefaultLifecycleObserver
{
  private static final String TAG = OrganicMaps.class.getSimpleName();

  @NonNull
  private final String mFlavor;
  @NonNull
  private final String mVersionName;

  @NonNull
  private final Context mContext;

  @NonNull
  private final SharedPreferences mPreferences;

  @NonNull
  private final IsolinesManager mIsolinesManager;
  @NonNull
  private final SubwayManager mSubwayManager;

  @NonNull
  private final LocationHelper mLocationHelper;
  @NonNull
  private final SensorHelper mSensorHelper;

  private volatile boolean mFrameworkInitialized;
  private volatile boolean mPlatformInitialized;

  @NonNull
  public LocationHelper getLocationHelper()
  {
    return mLocationHelper;
  }

  @NonNull
  public SensorHelper getSensorHelper()
  {
    return mSensorHelper;
  }

  @NonNull
  public SubwayManager getSubwayManager()
  {
    return mSubwayManager;
  }

  @NonNull
  public IsolinesManager getIsolinesManager()
  {
    return mIsolinesManager;
  }

  @NonNull
  public String getFlavor()
  {
    return mFlavor;
  }

  @NonNull
  public String getVersionName()
  {
    return mVersionName;
  }

  public OrganicMaps(@NonNull Context context, @NonNull String flavor, @NonNull String applicationId, int versionCode,
                     @NonNull String versionName)
  {
    mFlavor = flavor;
    mVersionName = versionName;
    mContext = context.getApplicationContext();
    mPreferences = mContext.getSharedPreferences(context.getString(R.string.pref_file_name), Context.MODE_PRIVATE);

    // Set configuration directory as early as possible.
    // Other methods may explicitly use Config, which requires settingsDir to be set.
    final String settingsPath = StorageUtils.getSettingsPath(mContext);
    if (!StorageUtils.createDirectory(settingsPath))
      throw new AssertionError("Can't create settingsDir " + settingsPath);
    Logger.d(TAG, "Settings path = " + settingsPath);
    nativeSetSettingsDir(settingsPath);

    Config.init(mPreferences, mFlavor, applicationId, versionCode, mVersionName);
    OsmOAuth.init(mPreferences);
    SharedPropertiesUtils.init(mPreferences);
    LogsManager.INSTANCE.initFileLogging(mContext, mPreferences);

    Android7RootCertificateWorkaround.initializeIfNeeded(mContext);

    Icon.loadDefaultIcons(mContext.getResources(), mContext.getPackageName());

    mSensorHelper = new SensorHelper(mContext);
    mLocationHelper = new LocationHelper(mContext, mSensorHelper);
    mIsolinesManager = new IsolinesManager();
    mSubwayManager = new SubwayManager(mContext);

    ConnectionState.INSTANCE.initialize(mContext);
  }

  /**
   * Initialize native core of application: platform and framework.
   *
   * @throws IOException - if failed to create directories. Caller must handle
   *                     the exception and do nothing with native code if initialization is failed.
   */
  public boolean init(@NonNull Runnable onComplete) throws IOException
  {
    initNativePlatform();
    return initNativeFramework(onComplete);
  }

  public boolean arePlatformAndCoreInitialized()
  {
    return mFrameworkInitialized && mPlatformInitialized;
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner)
  {
    nativeOnTransit(true);
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner)
  {
    nativeOnTransit(false);
  }

  @NonNull
  public SharedPreferences getPreferences()
  {
    return mPreferences;
  }

  private void initNativePlatform() throws IOException
  {
    if (mPlatformInitialized)
      return;

    final String apkPath = StorageUtils.getApkPath(mContext);
    Logger.d(TAG, "Apk path = " + apkPath);
    // Note: StoragePathManager uses Config, which requires SettingsDir to be set.
    final String writablePath = StoragePathManager.findMapsStorage(mContext);
    Logger.d(TAG, "Writable path = " + writablePath);
    final String privatePath = StorageUtils.getPrivatePath(mContext);
    Logger.d(TAG, "Private path = " + privatePath);
    final String tempPath = StorageUtils.getTempPath(mContext);
    Logger.d(TAG, "Temp path = " + tempPath);

    // If platform directories are not created it means that native part of app will not be able
    // to work at all. So, we just ignore native part initialization in this case, e.g. when the
    // external storage is damaged or not available (read-only).
    createPlatformDirectories(writablePath, privatePath, tempPath);

    unpackBundledSyriaMap(writablePath);

    nativeInitPlatform(mContext, apkPath, writablePath, privatePath, tempPath, mFlavor, BuildConfig.BUILD_TYPE,
                       /* isTablet */ false);
    Config.setStoragePath(writablePath);
    Config.setStatisticsEnabled(SharedPropertiesUtils.isStatisticsEnabled());

    mPlatformInitialized = true;
    Logger.i(TAG, "Platform initialized");
  }

  private void unpackBundledSyriaMap(@NonNull String writablePath)
  {
    try
    {
      String[] assets = mContext.getAssets().list("");
      boolean hasSyriaInAssets = false;
      if (assets != null)
      {
        for (String a : assets)
        {
          if ("Syria.mwm".equals(a))
          {
            hasSyriaInAssets = true;
            break;
          }
        }
      }
      if (!hasSyriaInAssets)
        return;

      String versionDir = "260714";
      try (InputStream is = mContext.getAssets().open("countries.json"))
      {
        byte[] buffer = new byte[512];
        int read = is.read(buffer);
        if (read > 0)
        {
          String header = new String(buffer, 0, read);
          int vIdx = header.indexOf("\"v\":");
          if (vIdx != -1)
          {
            int commaIdx = header.indexOf(",", vIdx);
            if (commaIdx != -1)
              versionDir = header.substring(vIdx + 4, commaIdx).replaceAll("[^0-9]", "");
          }
        }
      }
      catch (Exception ignored) {}

      File targetDir = new File(writablePath, versionDir);
      if (!targetDir.exists())
        targetDir.mkdirs();

      File targetFile = new File(targetDir, "Syria.mwm");
      if (!targetFile.exists() || targetFile.length() == 0)
      {
        Logger.i(TAG, "Unpacking bundled Syria.mwm to " + targetFile.getAbsolutePath());
        try (InputStream in = mContext.getAssets().open("Syria.mwm");
             OutputStream out = new FileOutputStream(targetFile))
        {
          byte[] buf = new byte[65536];
          int len;
          while ((len = in.read(buf)) > 0)
            out.write(buf, 0, len);
          out.flush();
        }
        Logger.i(TAG, "Unpacking bundled Syria.mwm completed: " + targetFile.length() + " bytes");
      }
    }
    catch (Exception e)
    {
      Logger.e(TAG, "Failed to unpack bundled Syria map: " + e.getMessage(), e);
    }
  }

  private boolean initNativeFramework(@NonNull Runnable onComplete)
  {
    if (mFrameworkInitialized)
      return false;

    nativeInitFramework(onComplete);

    initNativeStrings();
    SearchEngine.INSTANCE.initialize();
    BookmarkManager.loadBookmarks();
    TtsPlayer.INSTANCE.initialize(mContext);
    RoutingController.get().initialize(mLocationHelper);
    TrafficManager.INSTANCE.initialize();
    mSubwayManager.initialize();
    mIsolinesManager.initialize();
    ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

    Logger.i(TAG, "Framework initialized");
    mFrameworkInitialized = true;
    return true;
  }

  private void createPlatformDirectories(@NonNull String writablePath, @NonNull String privatePath,
                                         @NonNull String tempPath) throws IOException
  {
    SharedPropertiesUtils.emulateBadExternalStorage(mContext);

    StorageUtils.requireDirectory(writablePath);
    StorageUtils.requireDirectory(privatePath);
    StorageUtils.requireDirectory(tempPath);
  }

  private void initNativeStrings()
  {
    nativeAddLocalization("core_entrance", mContext.getString(R.string.core_entrance));
    nativeAddLocalization("core_exit", mContext.getString(R.string.core_exit));
    nativeAddLocalization("core_my_places", mContext.getString(R.string.core_my_places));
    nativeAddLocalization("core_my_position", mContext.getString(R.string.core_my_position));
    nativeAddLocalization("core_placepage_unknown_place", mContext.getString(R.string.core_placepage_unknown_place));
    nativeAddLocalization("open_in_app", mContext.getString(R.string.open_in_app));
    nativeAddLocalization("postal_code", mContext.getString(R.string.postal_code));
    nativeAddLocalization("wifi", mContext.getString(R.string.category_wifi));
    nativeAddLocalization("share_my_position", mContext.getString(R.string.share_my_position));
    nativeAddLocalization("share_open_in_om_or_browser", mContext.getString(R.string.share_open_in_om_or_browser));
    nativeAddLocalization("share_get_om", mContext.getString(R.string.share_get_om));
  }

  private static native void nativeSetSettingsDir(String settingsPath);

  private static native void nativeInitPlatform(Context context, String apkPath, String writablePath,
                                                String privatePath, String tmpPath, String flavorName, String buildType,
                                                boolean isTablet);

  private static native void nativeInitFramework(@NonNull Runnable onComplete);

  private static native void nativeAddLocalization(String name, String value);

  private static native void nativeOnTransit(boolean foreground);

  static
  {
    System.loadLibrary("organicmaps");
  }
}
