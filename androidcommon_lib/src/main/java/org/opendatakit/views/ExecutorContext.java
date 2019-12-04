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

package org.opendatakit.views;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.opendatakit.activities.IOdkDataActivity;
import org.opendatakit.database.data.OrderedColumns;
import org.opendatakit.database.service.DbHandle;
import org.opendatakit.database.service.UserDbInterface;
import org.opendatakit.exception.ServicesAvailabilityException;
import org.opendatakit.listener.DatabaseConnectionListener;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.utilities.ODKFileUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author mitchellsundt@gmail.com
 */
public class ExecutorContext implements DatabaseConnectionListener {
  private static final String TAG = "ExecutorContext";
    private static ExecutorContext currentContext = null;

    private static void updateCurrentContext(ExecutorContext ctxt) {
        if ( currentContext != null ) {
            ctxt.queueRequest(new ExecutorRequest(currentContext));
        }
        currentContext = ctxt;
        // register for database connection status changes
        ctxt.activity.registerDatabaseConnectionBackgroundListener(ctxt);
    }

    /**
     * The activity containing the web view.
     * Specifically, the API we need to access.
     */
    private final IOdkDataActivity activity;

  /**
   * The mutex used to guard all of the private data structures:
   *   worker, workQueue, activeConnections, mCacheOrderedDefns
   */
  private final Object mutex = new Object();

   /**
     * Our use of an executor is a bit odd:
     *
     * We need to handle database service disconnections.
     *
     * That requires direct management of the work queue.
     *
     * We still queue actions, but those actions need to pull
     * the request definitions off a work queue that is explicitly
     * managed by the ExecutorContext.
     *
     * The processors effectively record that there is (likely) work
     * to be processed. The work is held here.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /**
     * workQueue should only be accessed by synchronized methods, as it may be
     * accessed in multiple threads.
     */
    private final LinkedList<ExecutorRequest> workQueue = new LinkedList<ExecutorRequest>();

    private Map<String, DbHandle> activeConnections = new HashMap<String, DbHandle>();
    private Map<String, OrderedColumns> mCachedOrderedDefns = new HashMap<String, OrderedColumns>();

    private ExecutorContext(IOdkDataActivity fragment) {
        this.activity = fragment;
        updateCurrentContext(this);
    }

    public static synchronized ExecutorContext getContext(IOdkDataActivity fragment) {
      if ( currentContext != null && (currentContext.activity == fragment) && currentContext.isAlive()) {
        return currentContext;
      } else {
        // the constructor will update currentContext...
        return new ExecutorContext(fragment);
      }
    }

  /**
   * if we are not shutting down and there is work to be done then fire an ExecutorProcessor.
   */
  private void triggerExecutorProcessor() {
      // processor is most often NOT discarded
      ExecutorProcessor processor = activity.newExecutorProcessor(this);
      synchronized (mutex) {
        // we might have drained the queue -- or not.
        if ( !worker.isShutdown() && !worker.isTerminated() && !workQueue.isEmpty() ) {
          worker.execute(processor);
        }
      }
    }

  /**
   * if we are not shutting down then queue a request and fire an ExecutorProcessor.
   * @param request
   */
  public void queueRequest(ExecutorRequest request) {
      // processor is most often NOT discarded
      ExecutorProcessor processor = activity.newExecutorProcessor(this);
      synchronized (mutex) {
        if ( !worker.isShutdown() && !worker.isTerminated()) {
          // push the request
          workQueue.add(request);
          worker.execute(processor);
        }
      }
    }

  /**
   * @return the next ExecutorRequest or null if the queue is empty
   */
  public ExecutorRequest peekRequest() {
      synchronized (mutex) {
        if (workQueue.isEmpty()) {
          return null;
        } else {
          return workQueue.peekFirst();
        }
      }
    }

  /**
   * Remove the current item from the top of the work queue.
   *
   * @param trigger true if we should fire an ExecutorProcessor.
   */
  public void popRequest(boolean trigger) {
    // processor is most often NOT discarded
    ExecutorProcessor processor = (trigger ? activity.newExecutorProcessor(this) : null);
    synchronized (mutex) {
      if ( !workQueue.isEmpty() ) {
        workQueue.removeFirst();
      }
      if ( !worker.isShutdown() && !worker.isTerminated() && trigger && !workQueue.isEmpty() ) {
        // signal that we have work...
        worker.execute(processor);
      }
    }
  }

    /**
     * shutdown the worker. This is done within the mutex to ensure that the above methods
     * never throw an unexpected state exception.
     */
    void shutdownWorker() {
      WebLogger.getLogger(currentContext.getAppName()).i(TAG, "shutdownWorker - shutting down dataif Executor");
      Throwable t = null;
      synchronized (mutex) {
        if ( !worker.isShutdown() && !worker.isTerminated() ) {
          worker.shutdown();
        }
        try {
          worker.awaitTermination(3000L, TimeUnit.MILLISECONDS);
        } catch (Throwable th) {
          t = th;
        }
      }

      if ( t != null ) {
        WebLogger.getLogger(currentContext.getAppName()).w(TAG,
                "shutdownWorker - dataif Executor threw exception while shutting down");
        WebLogger.getLogger(currentContext.getAppName()).printStackTrace(t);
      }
      WebLogger.getLogger(currentContext.getAppName()).i(TAG, "shutdownWorker - dataif Executor has been shut down.");
    }

  /**
   * Get the connection on which this transaction is active.
   *
   * @param transId
   * @return DbHandle
   */
  public DbHandle getActiveConnection(String transId) {
    synchronized (mutex) {
      return activeConnections.get(transId);
    }
  }

  public void registerActiveConnection(String transId, DbHandle dbHandle) {
    boolean alreadyExists = false;
    synchronized (mutex) {
      if ( activeConnections.containsKey(transId) ) {
        alreadyExists = true;
      } else {
        activeConnections.put(transId, dbHandle);
      }
    }
    if ( alreadyExists ) {
      WebLogger.getLogger(currentContext.getAppName()).e(TAG,"transaction id " + transId + " already registered!");
      throw new IllegalArgumentException("transaction id already registered!");
    }
  }

  private String getFirstActiveTransactionId() {
    synchronized (mutex) {
      Set<String> transIds = activeConnections.keySet();
      if ( transIds.isEmpty() ) {
        return null;
      } else {
        return transIds.iterator().next();
      }
    }
  }

  public void removeActiveConnection(String transId) {
    synchronized (mutex) {
      activeConnections.remove(transId);
    }
  }

  public OrderedColumns getOrderedColumns(String tableId) {
    synchronized (mutex) {
      return mCachedOrderedDefns.get(tableId);
    }
  }

  public void putOrderedColumns(String tableId, OrderedColumns orderedColumns) {
    synchronized (mutex) {
      mCachedOrderedDefns.put(tableId, orderedColumns);
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // No direct access to data structures below this point

  /**
   * @return
   */
    public UserDbInterface getDatabase() {
        return activity.getDatabase();
    }

    public String getAppName() {
        return activity.getAppName();
    }

    public void releaseResources(String reason) {
      // TODO: rollback any transactions and close connections

      // the most reasonable error is to report a service availablity error
      // the recovery options for that (abort) are the only reasonable ones when we are
      // releasing resources.
   	  String errorMessage = ServicesAvailabilityException.class.getName() +
        ": releaseResources - shutting down worker (" + reason +
                   ") -- rolling back all transactions and releasing all connections";
      for(;;) {
        ExecutorRequest req = peekRequest();
        if ( req == null ) {
          break;
        }
        try {
           reportError(req.callbackJSON, req.callerID, null, errorMessage);
        } catch(Exception e) {
           WebLogger.getLogger(getAppName()).w(TAG, "releaseResources - exception while "
               + "cancelling outstanding requests");
        } finally {
           popRequest(false);
        }
      }

      WebLogger.getLogger(currentContext.getAppName()).i(TAG, "releaseResources - workQueue has been purged.");

      int activeConns = 0;
      for (;;) {
        String transId = getFirstActiveTransactionId();
        if ( transId == null ) {
          break;
        }
        DbHandle dbh = getActiveConnection(transId);
        removeActiveConnection(transId);
        if ( dbh == null ) {
          WebLogger.getLogger(getAppName()).w(TAG, "Unexpected failure to retrieve dbHandle for " + transId);
        }
        UserDbInterface dbInterface = currentContext.getDatabase();
        if ( dbInterface != null ) {
          try {
            WebLogger.getLogger(currentContext.getAppName()).i(TAG, "releaseResources - closing dbHandle " + dbh.toString());
            dbInterface.closeDatabase(currentContext.getAppName(), dbh);
            activeConns++;
          } catch (Throwable t) {
            WebLogger.getLogger(currentContext.getAppName()).w(TAG,
                    "releaseResources - Exception thrown while trying to close dbHandle");
            WebLogger.getLogger(currentContext.getAppName()).printStackTrace(t);
          }
        }
      }

      WebLogger.getLogger(currentContext.getAppName()).w(TAG,
              "releaseResources - closed " + activeConns + " associated dbHandles");
    }

    public void reportError(String callbackJSON, String callerID, String transId,
        String errorMessage) {
      if ( callbackJSON != null ) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("callbackJSON", callbackJSON);
        response.put("error", errorMessage);
        if (transId != null) {
          response.put("transId", transId);
        }
        String responseStr = null;
        try {
          responseStr = ODKFileUtils.mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
          WebLogger.getLogger(currentContext.getAppName()).e(TAG, "should never have a conversion error");
          WebLogger.getLogger(currentContext.getAppName()).printStackTrace(e);
          throw new IllegalStateException("should never have a conversion error");
        }
        activity.signalResponseAvailable(responseStr, callerID);
      }
    }


    public void reportSuccess(String callbackJSON, String callerID, String transId,
        ArrayList<List<Object>> data, Map<String,Object> metadata) {
        Map<String,Object> response = new HashMap<String,Object>();
        response.put("callbackJSON", callbackJSON);
        if ( transId != null ) {
            response.put("transId", transId);
        }
        if ( data != null ) {
            response.put("data", data);
        }
        if ( metadata != null ) {
            response.put("metadata", metadata);
        }
        String responseStr = null;
        try {
            responseStr = ODKFileUtils.mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
          WebLogger.getLogger(currentContext.getAppName()).e(TAG, "should never have a conversion error");
          WebLogger.getLogger(currentContext.getAppName()).printStackTrace(e);
          throw new IllegalStateException("should never have a conversion error");
        }
        activity.signalResponseAvailable(responseStr, callerID);
    }

    @Override
    public void databaseAvailable() {
      triggerExecutorProcessor();
    }

    @Override
    public void databaseUnavailable() {
        new ExecutorContext(activity);
    }

   public synchronized boolean isAlive() {
      return !(worker.isShutdown() || worker.isTerminated());
   }
}
