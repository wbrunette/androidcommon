/*
 * Copyright (C) 2015 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.application;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.webkit.WebView;
import org.opendatakit.consts.IntentConsts;
import org.opendatakit.consts.WebkitServerConsts;
import org.opendatakit.database.service.IDbInterface;
import org.opendatakit.database.service.InternalUserDbInterfaceAidlWrapperImpl;
import org.opendatakit.database.service.UserDbInterface;
import org.opendatakit.database.service.UserDbInterfaceImpl;
import org.opendatakit.listener.DatabaseConnectionListener;
import org.opendatakit.listener.InitializationListener;
import org.opendatakit.properties.CommonToolProperties;
import org.opendatakit.properties.PropertiesSingleton;
import org.opendatakit.task.InitializationTask;
import org.opendatakit.utilities.LocalizationUtils;
import org.opendatakit.webkitserver.service.IWebkitServerInterface;

import java.util.ArrayList;

public abstract class CommonApplication extends ToolAwareApplication implements
    InitializationListener {

  // Used for logging
  private static final String TAG = CommonApplication.class.getSimpleName();

  /**
   * Task instances that are preserved until the application dies.
   * 
   * @author mitchellsundt@gmail.com
   *
   */
  private static final class BackgroundTasks {
    InitializationTask mInitializationTask = null;

    BackgroundTasks() {
    }
  }

  /**
   * Service connections that are preserved until the application dies.
   * 
   * @author mitchellsundt@gmail.com
   *
   */
  private static final class BackgroundServices {

    private ServiceConnection webkitfilesServiceConnection = null;
    private IWebkitServerInterface webkitfilesService = null;
    private ServiceConnection databaseServiceConnection = null;
    private UserDbInterface databaseService = null;
    private boolean isDestroying = false;

    BackgroundServices() {
    }

    synchronized void clearDestroyingFlag() {
      Log.i(TAG, "isDestroying reset to false");
      isDestroying = false;
    }

    synchronized boolean isDestroyingFlag() {
      return isDestroying;
    }
    public synchronized UserDbInterface getDatabase() {
      return databaseService;
    }

    private synchronized IWebkitServerInterface getWebkitServer() {
      return webkitfilesService;
    }
    private void bindToService(final CommonApplication application,
        boolean useWebServer, boolean useDatabase ) {
      ServiceConnection webkitServerBinder = null;
      ServiceConnection databaseBinder = null;

      synchronized (this) {
        if ( !isDestroying ) {
          Log.i(TAG, "bindToService -- processing...");
          if ( useWebServer && webkitfilesService == null && webkitfilesServiceConnection == null ) {
            webkitfilesServiceConnection = webkitServerBinder = new ServiceConnection() {

              @Override public void onServiceConnected(ComponentName name, IBinder service) {
                doServiceConnected(application, name, service);
              }

              @Override public void onServiceDisconnected(ComponentName name) {
                doServiceDisconnected(application, name);
              }
            };
          }
          if ( useDatabase && databaseService == null && databaseServiceConnection == null ) {
            databaseServiceConnection = databaseBinder = new ServiceConnection() {
              @Override public void onServiceConnected(ComponentName name, IBinder service) {
                doServiceConnected(application, name, service);
              }

              @Override public void onServiceDisconnected(ComponentName name) {
                doServiceDisconnected(application, name);
              }
            };
          }
        } else {
          Log.i(TAG, "bindToService -- ignored -- isDestroying is true!");
        }
      }

      if ( webkitServerBinder != null ) {
        Log.i(TAG, "Attempting bind to WebkitServer service");
        Intent bind_intent = new Intent();
        bind_intent.setClassName(WebkitServerConsts.WEBKITSERVER_SERVICE_PACKAGE,
            WebkitServerConsts.WEBKITSERVER_SERVICE_CLASS);
        application.bindService(
            bind_intent,
            webkitServerBinder,
            Context.BIND_AUTO_CREATE);
      }

      if ( databaseBinder != null ) {
        Log.i(TAG, "Attempting bind to Database service");
        Intent bind_intent = new Intent();
        bind_intent.setClassName(IntentConsts.Database.DATABASE_SERVICE_PACKAGE,
                IntentConsts.Database.DATABASE_SERVICE_CLASS);
        application.bindService(
            bind_intent,
            databaseBinder,
            Context.BIND_AUTO_CREATE);
      }
    }

    private void doServiceConnected(CommonApplication application, ComponentName className,
        IBinder service) {
      if (className.getClassName().equals(WebkitServerConsts.WEBKITSERVER_SERVICE_CLASS)) {
        Log.i(TAG, "Bound to WebServer service");
        synchronized (this) {
          try {
            webkitfilesService = (service == null) ? null : IWebkitServerInterface.Stub.asInterface(service);
          } catch (Exception e) {
            webkitfilesService = null;
          }
        }
      }

      if (className.getClassName().equals(IntentConsts.Database.DATABASE_SERVICE_CLASS)) {
        Log.i(TAG, "Bound to Database service");
        synchronized (this) {
          try {
            databaseService = (service == null) ? null : new UserDbInterfaceImpl(
                new InternalUserDbInterfaceAidlWrapperImpl(
                  IDbInterface.Stub.asInterface(service)));
          } catch (Exception e) {
            databaseService = null;
          }
        }
        application.triggerDatabaseEvent(false);
      }
    }

    private void doServiceDisconnected(CommonApplication application, ComponentName className) {
      if (className.getClassName().equals(WebkitServerConsts.WEBKITSERVER_SERVICE_CLASS)) {
        ServiceConnection tmpWeb = null;
        synchronized (this) {
          if (isDestroying) {
            Log.i(TAG, "Unbound from WebServer service (intentionally)");
          } else {
            Log.w(TAG, "Unbound from WebServer service (unexpected)");
          }
          webkitfilesService = null;
          tmpWeb = webkitfilesServiceConnection;
          webkitfilesServiceConnection = null;
        }
        try {
          if ( tmpWeb != null ) {
            // expect to fail (because we are disconnected)
            application.unbindService(tmpWeb);
          }
        } catch ( Exception e ) {
          // ignore
          e.printStackTrace();
        }
      }

      if (className.getClassName().equals(IntentConsts.Database.DATABASE_SERVICE_CLASS)) {
        ServiceConnection tmpDb = null;
        synchronized (this) {
          if (isDestroying) {
            Log.i(TAG, "Unbound from Database service (intentionally)");
          } else {
            Log.w(TAG, "Unbound from Database service (unexpected)");
          }
          databaseService = null;
          tmpDb = databaseServiceConnection;
          databaseServiceConnection = null;
        }
        try {
          if ( tmpDb != null ) {
            // expect to fail (because we are disconnected)
            application.unbindService(tmpDb);
          }
        } catch ( Exception e ) {
          // ignore
          e.printStackTrace();
        }
        application.triggerDatabaseEvent(false);
      }

      // the bindToService() method decides whether to connect or not...
      application.bindToService();
    }

    private void shutdownServices(CommonApplication application) {
      Log.i(TAG, "shutdownServices - Releasing WebServer and database service");
      ServiceConnection tmpWeb = null;
      ServiceConnection tmpDb = null;
      synchronized (this) {
        isDestroying = true;
        webkitfilesService = null;
        databaseService = null;
        tmpWeb = webkitfilesServiceConnection;
        tmpDb = databaseServiceConnection;
        webkitfilesServiceConnection = null;
        databaseServiceConnection = null;
      }
      try {
        if (tmpWeb != null) {
          application.unbindService(tmpWeb);
        }
      } catch (Exception e) {
        // ignore
        e.printStackTrace();
      }

      try {
        if (tmpDb != null) {
          application.unbindService(tmpDb);
        }
      } catch (Exception e) {
        // ignore
        e.printStackTrace();
      }
      // release interfaces held by the view
      application.triggerDatabaseEvent(false);
    }
  }

  // handed across orientation changes
  private final BackgroundTasks mBackgroundTasks = new BackgroundTasks(); 

  // handed across orientation changes
  private final BackgroundServices mBackgroundServices = new BackgroundServices(); 

  // These are expected to be broken down and set up during orientation changes.
  private InitializationListener mInitializationListener = null;

  private boolean shuttingDown = false;
  
  public CommonApplication() {
    super();
  }
  
  @SuppressLint("NewApi")
  @Override
  public void onCreate() {
    shuttingDown = false;
    super.onCreate();

    if(android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      if (Application.getProcessName().equals("org.opendatakit.tables")) {
        WebView.setWebContentsDebuggingEnabled(true);
      }
    } else {
      WebView.setWebContentsDebuggingEnabled(true);
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    Log.i(TAG, "onConfigurationChanged");
  }

  @Override
  public void onTerminate() {
    cleanShutdown();
    super.onTerminate();
    Log.i(TAG, "onTerminate");
  }

  public abstract int getConfigZipResourceId();
  
  public abstract int getSystemZipResourceId();

  public boolean shouldRunInitializationTask(String appName) {
    PropertiesSingleton props = CommonToolProperties.get(this, appName);
    // Run task if either one is true
    return (props.shouldRunInitializationTask(this.getToolName()) ||
            props.shouldRunCommonInitializationTask());
  }

  public void clearRunInitializationTask(String appName) {
    PropertiesSingleton props = CommonToolProperties.get(this, appName);
    props.clearRunInitializationTask(this.getToolName());
    LocalizationUtils.clearTranslations();
  }

  private Activity activeActivity = null;
  private Activity databaseListenerActivity = null;
  
  public void onActivityPause(Activity activity) {
    if ( activeActivity == activity ) {
      mInitializationListener = null;
  
      if (mBackgroundTasks.mInitializationTask != null) {
        mBackgroundTasks.mInitializationTask.setInitializationListener(null);
      }
    }
  }
  
  public void onActivityDestroy(Activity activity) {
    if ( activeActivity == activity ) {
      activeActivity = null;
      
      mInitializationListener = null;
  
      if (mBackgroundTasks.mInitializationTask != null) {
        mBackgroundTasks.mInitializationTask.setInitializationListener(null);
      }

      final Handler handler = new Handler();
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          CommonApplication.this.testForShutdown();
        }
      }, 500);
    }
  }

  private void cleanShutdown() {
    try {
      shuttingDown = true;
      Log.i(TAG, "cleanShutdown (initiating)");
      shutdownServices();
    } finally {
      shuttingDown = false;
      Log.i(TAG, "cleanShutdown (resetting shuttingDown to false)");
    }
  }
  
  private void testForShutdown() {
    // no other activity has been started -- shut down
    if ( activeActivity == null ) {
      cleanShutdown();
    }
  }

  public void onActivityResume(Activity activity) {
    databaseListenerActivity = null;
    activeActivity = activity;
    
    if (mBackgroundTasks.mInitializationTask != null) {
      mBackgroundTasks.mInitializationTask.setInitializationListener(this);
    }
    
    // be sure the services are connected...
    mBackgroundServices.clearDestroyingFlag();

    // failsafe -- ensure that the services are active...
    bindToService();
  }

  // /////////////////////////////////////////////////////////////////////////
  // service interactions

  private void shutdownServices() {
    mBackgroundServices.shutdownServices(this);
  }
  
  private void bindToService() {
    if (!shuttingDown) {
      PackageManager pm = getPackageManager();

      Log.i(TAG, "bindToService -- useWebServer " + Boolean.toString(true)
          + " useDatabase " + Boolean.toString(true) );
      mBackgroundServices.bindToService(this, true, true);
    }
  }

  public UserDbInterface getDatabase() {
      return mBackgroundServices.getDatabase();
  }
  
  private IWebkitServerInterface getWebkitServer() {
      return mBackgroundServices.getWebkitServer();

  }

  // /////////////////////////////////////////////////////////////////////////
  // registrations

  /**
   * Called by an activity when it has been sufficiently initialized so
   * that it can handle a databaseAvailable() call.
   * 
   * @param activity
   */
  public void establishDatabaseConnectionListener(Activity activity) {
    databaseListenerActivity = activity;
    triggerDatabaseEvent(true);
  }

  public void establishDoNotFireDatabaseConnectionListener(Activity activity) {
    databaseListenerActivity = activity;
  }

  public void fireDatabaseConnectionListener() {
    triggerDatabaseEvent(true);
  }

  /**
   * If the given activity is active, then fire the callback based upon 
   * the availability of the database.
   * 
   * @param activity
   * @param listener
   */
  public void possiblyFireDatabaseCallback(Activity activity, DatabaseConnectionListener listener) {
    if (  activeActivity != null &&
        activeActivity == databaseListenerActivity &&
        databaseListenerActivity == activity ) {
      if ( this.getDatabase() == null ) {
        listener.databaseUnavailable();
      } else {
        listener.databaseAvailable();
      }
    }
  }
  
  private void triggerDatabaseEvent(boolean availableOnly) {
    if ( activeActivity != null &&
        activeActivity == databaseListenerActivity &&
        activeActivity instanceof DatabaseConnectionListener ) {
      if ( this.getDatabase() == null ) {
        if ( !availableOnly ) {
          ((DatabaseConnectionListener) activeActivity).databaseUnavailable();
        }
      } else {
        ((DatabaseConnectionListener) activeActivity).databaseAvailable();
      }
    }
  }
  
  public void establishInitializationListener(InitializationListener listener) {
    mInitializationListener = listener;
    // async task may have completed while we were reorienting...
    if (mBackgroundTasks.mInitializationTask != null
        && mBackgroundTasks.mInitializationTask.getStatus() == AsyncTask.Status.FINISHED) {
      this.initializationComplete(mBackgroundTasks.mInitializationTask.getOverallSuccess(),
          mBackgroundTasks.mInitializationTask.getResult());
    }
  }

  // ///////////////////////////////////////////////////
  // actions

  public synchronized boolean initializeAppName(String appName, InitializationListener listener) {
    mInitializationListener = listener;
    if (mBackgroundTasks.mInitializationTask != null
        && mBackgroundTasks.mInitializationTask.getStatus() != AsyncTask.Status.FINISHED) {
      // Toast.makeText(this.getActivity(),
      // getString(R.string.expansion_in_progress),
      // Toast.LENGTH_LONG).show();
      return true;
    } else if ( getDatabase() != null ) {
      InitializationTask cf = new InitializationTask();
      cf.setApplication(this);
      cf.setAppName(appName);
      cf.setInitializationListener(this);
      mBackgroundTasks.mInitializationTask = cf;
      mBackgroundTasks.mInitializationTask.execute((Void) null);
      return true;
    } else {
      return false;
    }
  }

  // /////////////////////////////////////////////////////////////////////////
  // clearing tasks
  //
  // NOTE: clearing these makes us forget that they are running, but it is
  // up to the task itself to eventually shutdown. i.e., we don't quite
  // know when they actually stop.


  public synchronized void clearInitializationTask() {
    mInitializationListener = null;
    if (mBackgroundTasks.mInitializationTask != null) {
      mBackgroundTasks.mInitializationTask.setInitializationListener(null);
      if (mBackgroundTasks.mInitializationTask.getStatus() != AsyncTask.Status.FINISHED) {
        mBackgroundTasks.mInitializationTask.cancel(true);
      }
    }
    mBackgroundTasks.mInitializationTask = null;
  }

  // /////////////////////////////////////////////////////////////////////////
  // cancel requests
  //
  // These maintain communications paths, so that we get a failure
  // completion callback eventually.


  public synchronized void cancelInitializationTask() {
    if (mBackgroundTasks.mInitializationTask != null) {
      if (mBackgroundTasks.mInitializationTask.getStatus() != AsyncTask.Status.FINISHED) {
        mBackgroundTasks.mInitializationTask.cancel(true);
      }
    }
  }

  // /////////////////////////////////////////////////////////////////////////
  // callbacks

  @Override
  public void initializationComplete(boolean overallSuccess, ArrayList<String> result) {
    if (mInitializationListener != null) {
      mInitializationListener.initializationComplete(overallSuccess, result);
    }
  }

  @Override
  public void initializationProgressUpdate(String status) {
    if (mInitializationListener != null) {
      mInitializationListener.initializationProgressUpdate(status);
    }
  }

}
